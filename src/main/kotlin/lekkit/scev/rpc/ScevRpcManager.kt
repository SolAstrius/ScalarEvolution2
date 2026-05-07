/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.rpc

import lekkit.scev.core.rpc.MsgValue
import lekkit.scev.core.rpc.RpcHandler
import lekkit.scev.core.rpc.RpcProtocol

import com.mojang.logging.LogUtils
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Consumer
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import lekkit.scev.common.ServerScope
import lekkit.scev.core.util.tickEach
import lekkit.scev.machine.SerialDevice
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent

/**
 * Per-machine RPC pipeline.
 *
 * Owns the RPC-side [SerialDevice], the [FrameStream] that turns its
 * bytes into complete COBS frames, and the [RpcDispatcher] that routes
 * decoded requests to handlers. One manager per live machine;
 * registered in [MANAGERS] by UUID so the server tick listener can
 * iterate them all.
 *
 * Lifecycle:
 * ```
 *   RvvmMachineBackend.initialize → create(uuid, serial)
 *                                   handlers registered
 *   ...server runs, tick drains serial, dispatches requests...
 *   RvvmMachineBackend.close      → unregister(uuid)
 * ```
 *
 * Threading + async dispatch:
 * - [onServerTick] runs on the main server thread, calls [tick] on
 *   every registered manager.
 * - [tick] drains the serial TX ring, feeds bytes to the framer, and
 *   dispatches decoded requests by `launch`ing a coroutine on [scope].
 *   The scope's dispatcher is [ServerScope.dispatcher] — a
 *   tick-thread-aware dispatcher whose `isDispatchNeeded` returns
 *   false when the caller is already on the server thread. Result:
 *   sync handlers complete inline during `launch`, their response is
 *   written to the serial RX ring before `launch` returns, no extra
 *   hop. Suspending handlers (peripheral pullEvent loops, delays,
 *   remote I/O) yield their coroutines; on resume the dispatcher
 *   routes the continuation back to the server thread for the
 *   [writeFrame] call.
 * - [sendEvent] is callable from any thread. It launches a coroutine
 *   on [scope] that writes the frame — inline if on the server thread,
 *   otherwise queued via [ServerScope.dispatcher].
 * - On [unregister], [scope] is cancelled and any in-flight handlers
 *   for that machine are torn down without delivering a response.
 *   Guest clients time out on their side; same failure mode as a real
 *   CC computer that got killed mid-call.
 * - On server stop, [ServerScope] cancels its own scope, which
 *   cascades to every per-machine [scope] parented to it.
 */
class ScevRpcManager private constructor(
    private val machineUuid: UUID,
    private val serial: SerialDevice,
) {
    private val stream = FrameStream(MAX_FRAME_BYTES, ::looksLikeRpcFrame)
    private val _dispatcher = RpcDispatcher()

    /**
     * Optional kernel-console UART. If set, drained each tick and its
     * bytes are logged at DEBUG so they don't accumulate and back up
     * the guest printk path.
     */
    private var kernelConsole: SerialDevice? = null
    private val kernelConsoleLine = ByteArray(512)
    private var kernelConsoleLineLen = 0

    /**
     * Bounded ring buffer of the last [KERNEL_CONSOLE_TAIL] guest-
     * console lines, populated by [drainKernelConsole]. Exposed via
     * [kernelConsoleTail] so tests (and eventually in-game diagnostics)
     * can inspect recent boot output without scraping SLF4J log files.
     *
     * Synchronized by the server thread: both the writer (server tick
     * calling [tick]) and typical readers (GameTests running on the
     * server thread) are serialized by Minecraft's tick loop. If a
     * non-server-thread reader ever appears, wrap the accessor in a
     * copy-under-lock.
     */
    private val kernelConsoleTailBuf: ArrayDeque<String> = ArrayDeque(KERNEL_CONSOLE_TAIL)

    /**
     * Bounded byte ring of recent guest TX. Shipped to late-joining
     * VT100 viewers so they replay the whole session through their own
     * mlterm and arrive at the same screen state as everyone who's
     * been watching from the start. DCS-aware wrap so sixel / ReGIS
     * payloads aren't truncated mid-stream.
     *
     * Lives on [ScevRpcManager] (not on [TerminalBlockEntity]) so the
     * buffer survives if every block viewer disconnects but the guest
     * keeps producing output — when someone next opens a screen they
     * still get the recent backlog.
     */
    private val replayBuffer: SerialReplayBuffer = SerialReplayBuffer()

    /**
     * Per-machine [SupervisorJob] parented to [ServerScope.scope]'s
     * job. Cancelling this job:
     *   - stops this machine's in-flight handler coroutines
     *   - does NOT affect other machines' managers
     *   - does NOT affect the parent server scope.
     *
     * The parent linkage runs the other way too: when the server stops
     * and [ServerScope] cancels its scope, every child [job] is
     * cancelled automatically.
     */
    private val job: CompletableJob = SupervisorJob(ServerScope.scope.coroutineContext[Job])
    private val scope = CoroutineScope(job + ServerScope.dispatcher)

    private val drainBuf = ByteArray(DRAIN_CHUNK)
    private val encodeBuf = ByteArray(Cobs.maxEncodedSize(MAX_FRAME_BYTES))

    /**
     * Per-machine chunk cache for oversized Response payloads. Sized
     * via the [ChunkStore] defaults — 4 outstanding streams, 8 MiB
     * per stream, 32 MiB across the store, 30 s TTL. Drained by the
     * guest via `read_chunk` / `discard_chunk` and swept each tick.
     */
    internal val chunkStore = ChunkStore(machineUuid)

    /**
     * Currently-running request handlers keyed by guest request id.
     * Populated by [handleIncoming]'s Request branch when a coroutine
     * launches; cleared when it completes (success, failure, or
     * cancellation). [cancelRequest] looks up and cancels by id —
     * useful for guests that timed out client-side and want to free
     * the host's coroutine instead of letting it run to completion.
     *
     * Idempotent against unknown ids (returns false): cancellation is
     * best-effort, and the natural race "request completed between
     * the guest deciding to cancel and the host processing the
     * cancel" should be a no-op, not an error.
     */
    private val inflight: java.util.concurrent.ConcurrentMap<Long, kotlinx.coroutines.Job> =
        java.util.concurrent.ConcurrentHashMap()

    /**
     * Cancel a still-running request handler. Returns true if there
     * was an in-flight request to cancel, false otherwise (already
     * completed, never existed, …). The cancelled coroutine produces
     * no Response on the wire — guests that called this method have
     * already given up on the original id, and a synthesized
     * [lekkit.scev.core.rpc.RpcErrors.GENERIC] reply would just be
     * noise.
     */
    fun cancelRequest(id: Long): Boolean {
        val job = inflight.remove(id) ?: return false
        job.cancel(kotlinx.coroutines.CancellationException("cancelled by guest request"))
        return true
    }

    /** Snapshot of currently-running request ids (for diagnostics / tests). */
    fun inflightRequestIds(): Set<Long> = inflight.keys.toSet()

    /* ---------------- counters ---------------- */

    private var requestsIn: Long = 0
    private var responsesOut: Long = 0
    private var eventsOut: Long = 0
    private var decodeFailures: Long = 0

    /* ---------------- public API ---------------- */

    fun dispatcher(): RpcDispatcher = _dispatcher
    fun machineUuid(): UUID = machineUuid

    /**
     * Attach a kernel-console serial device to drain (and log at
     * DEBUG) each tick. Optional — without it, guest printk
     * accumulates in the UART ring and eventually drops oldest bytes
     * at the JNI layer.
     */
    fun attachKernelConsole(console: SerialDevice) {
        this.kernelConsole = console
    }

    /**
     * Snapshot of the last [KERNEL_CONSOLE_TAIL] guest-console lines
     * seen by [drainKernelConsole], oldest first. Returns an empty list
     * if no console is attached or nothing has been logged.
     *
     * Intended for integration tests and future diagnostics overlays
     * that need to inspect recent boot output synchronously. Not a
     * substitute for real log capture — lines dropped here stay in the
     * SLF4J log regardless.
     */
    fun kernelConsoleTail(): List<String> = kernelConsoleTailBuf.toList()

    /**
     * Queue an event for the guest. Safe to call from any thread —
     * the launch hops to the server thread via [ServerScope.dispatcher]
     * if necessary, runs inline otherwise.
     */
    fun sendEvent(evt: RpcFrame.Event) {
        scope.launch {
            writeFrame(evt)
            eventsOut++
        }
    }

    /* ---------------- instrumentation ---------------- */

    fun requestsIn(): Long = requestsIn
    fun responsesOut(): Long = responsesOut
    fun eventsOut(): Long = eventsOut
    fun decodeFailures(): Long = decodeFailures
    fun droppedFrames(): Long = stream.droppedFrames

    /* ---------------- tick ---------------- */

    /** Visible for tests; normally driven by [onServerTick]. */
    fun tick() {
        // Sweep idle chunk streams older than the TTL so abandoned
        // reads (guest crashed mid-fetch, never called discard) don't
        // leak memory.
        chunkStore.tickEvictExpired(System.currentTimeMillis())

        // Drain kernel-console UART — bytes go to a line-buffered DEBUG
        // log so boot messages are available in dev and invisible in
        // prod. Lines longer than the buffer are truncated (rare; a
        // kernel log line exceeding 512 chars is a bug anyway).
        drainKernelConsole()

        // Drain serial TX, decode frames, dispatch. Handler coroutines
        // that complete inline (sync path) write their response to the
        // serial RX ring before the launch returns; suspending handlers
        // post their response when they resume.
        while (true) {
            val n = serial.pollTx(drainBuf)
            if (n <= 0) break
            val frames = stream.feed(drainBuf, 0, n)
            for (payload in frames) handleIncoming(payload)
            if (n < drainBuf.size) break  // ring drained
        }
    }

    private fun drainKernelConsole() {
        val console = kernelConsole ?: return
        while (true) {
            val kn = console.pollTx(drainBuf)
            if (kn <= 0) break
            // Capture into the replay ring BEFORE fanning out — that
            // way the late-joiner replay path covers everything live
            // viewers have seen, and the ordering is consistent (a
            // viewer subscribed mid-tick still gets ring-then-live
            // bytes in a coherent sequence).
            replayBuffer.write(drainBuf, kn)

            // Fan out raw bytes to every registered sink. The line-
            // buffered logger below stays subscribed by default;
            // additional sinks (e.g. an open VT100 terminal block)
            // get the same chunks and can interpret them however
            // they want (the terminal keeps CR + LF for proper VT
            // cursor positioning, the logger strips them).
            for (sink in consoleSinks) {
                try {
                    sink.onConsoleBytes(drainBuf, kn)
                } catch (t: Throwable) {
                    LOG.warn("[scev-kernel {}] console sink threw, dropping: {}",
                        machineUuid, t.toString())
                }
            }
            for (i in 0 until kn) {
                val b = drainBuf[i]
                if (b == '\n'.code.toByte() || kernelConsoleLineLen == kernelConsoleLine.size) {
                    if (kernelConsoleLineLen > 0) {
                        // Buffer the line for crash-diagnostic tail dumps
                        // but DO NOT emit it via SLF4J — every kernel
                        // printk would drown the Java console at DEBUG
                        // level, and the player can read live output on
                        // the actual VT100 terminal block. The tail buf
                        // is what we want preserved; the log call was
                        // duplicating that data into log4j with no
                        // additional value.
                        val line = String(kernelConsoleLine, 0, kernelConsoleLineLen, StandardCharsets.UTF_8)
                        if (kernelConsoleTailBuf.size == KERNEL_CONSOLE_TAIL) {
                            kernelConsoleTailBuf.removeFirst()
                        }
                        kernelConsoleTailBuf.addLast(line)
                        kernelConsoleLineLen = 0
                    }
                } else if (b != '\r'.code.toByte()) {
                    kernelConsoleLine[kernelConsoleLineLen++] = b
                }
            }
            if (kn < drainBuf.size) break
        }
    }

    /* ---------------- kernel console pub/sub ---------------- */

    /** Registered fan-out subscribers for guest TX bytes. CopyOnWrite
     *  because subscribe/unsubscribe is rare (block placement /
     *  removal) but the drain loop iterates this every server tick. */
    private val consoleSinks: MutableList<KernelConsoleSink> =
        java.util.concurrent.CopyOnWriteArrayList()

    /** Subscribe to drained kernel-console TX. Idempotent — adding
     *  the same instance twice is treated as one subscription. */
    fun addConsoleSink(sink: KernelConsoleSink) {
        if (!consoleSinks.contains(sink)) consoleSinks.add(sink)
    }

    /** Unsubscribe. No-op if the sink wasn't registered. */
    fun removeConsoleSink(sink: KernelConsoleSink) {
        consoleSinks.remove(sink)
    }

    /**
     * Snapshot of the replay ring — every guest TX byte still in
     * scope, oldest first. Intended for "VT100 menu just opened, send
     * the player up to ~256 KiB of recent backlog so they replay
     * through their local mlterm and see the current screen instead
     * of black."
     */
    fun consoleReplaySnapshot(): ByteArray = replayBuffer.snapshot()

    /**
     * Push player-typed bytes into the guest's kernel-console RX
     * queue. Intended for in-game terminal blocks (VT100). Returns
     * the number of bytes the UART accepted; partial writes are
     * possible if the RX ring is near-full.
     *
     * No-op (returns 0) if no kernel console is attached.
     */
    fun feedKernelConsoleInput(bytes: ByteArray): Int {
        val console = kernelConsole ?: return 0
        return console.feedRx(bytes)
    }

    private fun handleIncoming(payload: ByteArray) {
        val frame = RpcProtocol.decode(payload)
        if (frame == null) {
            decodeFailures++
            if (decodeFailures <= 4 || decodeFailures % 64 == 0L) {
                LOG.debug(
                    "[scev-rpc] {} decode failure #{} on {}-byte frame",
                    machineUuid, decodeFailures, payload.size,
                )
            }
            return
        }
        when (frame) {
            is RpcFrame.Request -> {
                requestsIn++
                // Tick thread is already the dispatcher's "home" thread,
                // so isDispatchNeeded=false and this launch body runs
                // inline — sync handlers complete and writeFrame() lands
                // in the serial RX ring before launch returns. Suspend
                // points inside dispatch() resume via the dispatcher,
                // which hops back to the server thread for writeFrame().
                //
                // Capture the launched Job so [cancelRequest] can
                // interrupt long-running handlers when the guest gives
                // up on the call. Removed in the finally so completion
                // races (handler done, then cancel arrives) leave the
                // map empty as expected.
                val reqId = frame.id
                val job = scope.launch {
                    try {
                        val response = _dispatcher.dispatch(frame)
                        writeFrame(response)
                        responsesOut++
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // Guest cancelled mid-flight. No Response — the
                        // guest has already moved on. Re-throw so the
                        // coroutine state is clean for the parent.
                        throw e
                    } finally {
                        inflight.remove(reqId)
                    }
                }
                inflight[reqId] = job
            }
            is RpcFrame.Response -> {
                // Guest shouldn't be sending responses to us — we don't
                // initiate calls into the guest yet. Log + drop.
                LOG.debug("[scev-rpc] {} received unexpected Response frame", machineUuid)
            }
            is RpcFrame.Event -> {
                // Events from guest are not part of v1 either.
                LOG.debug("[scev-rpc] {} received unexpected Event frame", machineUuid)
            }
            is RpcFrame.Chunked -> {
                // Chunked is host-emitted only — guest sees these and
                // drains via read_chunk. Receiving one from the guest
                // means something went very wrong upstream.
                LOG.debug("[scev-rpc] {} received unexpected Chunked frame", machineUuid)
            }
        }
    }

    /** Encode, COBS-frame, and push to the guest RX ring.
     *
     *  When a Response is too large to fit, hand the encoded bytes to
     *  the per-machine [chunkStore] and emit a tiny [RpcFrame.Chunked]
     *  marker in its place. The guest then drains the response via
     *  `read_chunk(streamId, offset, max)` calls and decodes the
     *  reassembled bytes locally as a regular Response. If the payload
     *  also exceeds the per-stream cap, fall back to a
     *  `frame_too_large` error response — that's genuinely pathological
     *  and a chunk store full of it would tank everyone else's headroom.
     *
     *  Events that exceed the cap get logged and dropped: no id to
     *  correlate against, and the event-aware code paths already
     *  tolerate drops. Chunked frames themselves are always tiny
     *  (4 ints) so the recursion bottoms out trivially.
     */
    private fun writeFrame(f: RpcFrame) {
        val payload = RpcProtocol.encode(f)
        if (payload.size > MAX_FRAME_BYTES) {
            if (f is RpcFrame.Response) {
                val streamId = chunkStore.add(payload, System.currentTimeMillis())
                if (streamId != null) {
                    LOG.debug(
                        "[scev-rpc] {} chunking outbound response id={} ({} bytes) as stream {}",
                        machineUuid, f.id, payload.size, streamId,
                    )
                    writeFrame(RpcFrame.chunked(f.id, streamId, payload.size.toLong()))
                    return
                }
                LOG.warn(
                    "[scev-rpc] {} response id={} too large to chunk ({} bytes)",
                    machineUuid, f.id, payload.size,
                )
                writeFrame(
                    RpcFrame.error(
                        f.id,
                        lekkit.scev.core.rpc.RpcErrors.FRAME_TOO_LARGE,
                        "response payload ${payload.size} bytes exceeds per-stream cap",
                    )
                )
                return
            }
            LOG.warn(
                "[scev-rpc] {} dropping outbound non-Response frame larger than max ({} > {})",
                machineUuid, payload.size, MAX_FRAME_BYTES,
            )
            return
        }
        val enc = Cobs.encode(payload, 0, payload.size, encodeBuf, 0)
        val out = ByteArray(enc)
        System.arraycopy(encodeBuf, 0, out, 0, enc)
        val fed = serial.feedRx(out)
        if (fed < out.size) {
            // RX ring near-full. Rare; means guest isn't draining.
            // Dropping the partial frame is safer than letting the
            // guest decode a truncated one — retries time out cleanly.
            LOG.debug(
                "[scev-rpc] {} RX ring near-full, dropped partial frame ({}/{} fed)",
                machineUuid, fed, out.size,
            )
        }
    }

    /**
     * Validator passed to [FrameStream] for embedded-frame recovery.
     * Returns true when [b] looks like the start of a real RPC frame —
     * a msgpack `fixarray` (3 or 4 elements) whose first element is one
     * of the protocol tag bytes. Trash from cooked-mode TTY echo never
     * matches: caret-encoded literals don't COBS-decode to msgpack
     * arrays starting with a tag byte at any offset.
     */
    private fun looksLikeRpcFrame(b: ByteArray, len: Int): Boolean {
        if (len < 2) return false
        // 0x93 = fixarray-3 (event), 0x94 = fixarray-4 (request/response).
        val hdr = b[0].toInt() and 0xff
        if (hdr != 0x93 && hdr != 0x94) return false
        // First element must be positive fixint 0/1/2 — the protocol tag.
        val tag = b[1].toInt() and 0xff
        return tag == RpcFrame.TAG_REQ || tag == RpcFrame.TAG_RSP || tag == RpcFrame.TAG_EVT
    }

    /* ---------------- companion (static) ---------------- */

    companion object {
        private val LOG = LogUtils.getLogger()

        /**
         * Max plaintext frame size the framer will accumulate before
         * resetting. AdvancedPeripherals' bigger describe responses
         * (ME bridge, energy detector) easily clear 16 KB; an entire
         * AE2 inventory listing returned through `call` can run to
         * hundreds of KB and is genuinely pathological — those want
         * paging or chunking, which is the next protocol step.
         *
         * 64 KiB is the pragmatic middle: covers the realistic
         * describe/list/call surface, costs ~192 KiB per machine
         * across the three buffers (acc, recoveryScratch, encodeBuf),
         * and on the guest stack-allocates without overflowing musl's
         * 8 MiB default.
         */
        @JvmField val MAX_FRAME_BYTES: Int = 65536

        /** Scratch buffer size for draining serial TX per tick. */
        private const val DRAIN_CHUNK = 4096

        /**
         * Max number of kernel-console lines retained in [kernelConsoleTailBuf].
         * 256 covers boot plus a few seconds of post-boot chatter — enough
         * for integration tests to look for DHCP lease completion / udev
         * messages without inflating server heap by a meaningful amount
         * (each line is < 512 bytes by construction, so ~128 KB total per
         * machine worst-case).
         */
        private const val KERNEL_CONSOLE_TAIL = 256

        private val MANAGERS = ConcurrentHashMap<UUID, ScevRpcManager>()

        /**
         * Listeners fired after a manager is created and default
         * handlers are installed. Lets compat layers (e.g. CC:
         * Tweaked) replace handlers with richer implementations.
         *
         * COW semantics: write-on-register is rare (mod init), iterate
         * on every create.
         */
        private val CREATE_LISTENERS: MutableList<Consumer<ScevRpcManager>> =
            CopyOnWriteArrayList()

        @JvmStatic
        fun addCreateListener(listener: Consumer<ScevRpcManager>) {
            CREATE_LISTENERS.add(listener)
        }

        /**
         * Create + register a manager for a machine. Returns the new
         * manager so the caller can register handlers on its
         * [RpcDispatcher]. If a manager already exists for this UUID,
         * returns the existing one (shouldn't happen in practice).
         */
        @JvmStatic
        fun create(uuid: UUID, serial: SerialDevice): ScevRpcManager {
            MANAGERS[uuid]?.let { existing ->
                LOG.warn("[scev-rpc] manager for {} already registered — reusing", uuid)
                return existing
            }
            val mgr = ScevRpcManager(uuid, serial)
            ScevRpcHandlers.registerDefaults(mgr._dispatcher, uuid, mgr.chunkStore)
            // Cancel is registered here rather than in registerDefaults
            // because it touches per-manager inflight state (the
            // request-id → Job map lives on the manager). Returns
            // {cancelled: bool} so guests can tell race-on-completion
            // (false; coroutine was already done) apart from real
            // cancels (true).
            mgr._dispatcher.register(RpcProtocol.METHOD_CANCEL) { args ->
                val id = (args.getOrNull(0) as? MsgValue.Int)?.value
                    ?: throw RpcHandler.RpcException(
                        "expected integer argument: id",
                        lekkit.scev.core.rpc.RpcErrors.BAD_ARGS,
                    )
                MsgValue.ofMap(
                    linkedMapOf(
                        MsgValue.of("cancelled") to MsgValue.of(mgr.cancelRequest(id)),
                    )
                )
            }
            MANAGERS[uuid] = mgr
            for (listener in CREATE_LISTENERS) {
                try {
                    listener.accept(mgr)
                } catch (e: RuntimeException) {
                    LOG.warn("[scev-rpc] create listener threw for {}", uuid, e)
                }
            }
            return mgr
        }

        @JvmStatic
        fun unregister(uuid: UUID) {
            val mgr = MANAGERS.remove(uuid) ?: return
            // Cancel scope so in-flight async handlers stop.
            // Suspended coroutines throw CancellationException at their
            // next suspension point; we let it propagate out of dispatch
            // without writing a response for the dead machine.
            mgr.scope.cancel()
        }

        @JvmStatic
        fun get(uuid: UUID): ScevRpcManager? = MANAGERS[uuid]

        @JvmStatic
        fun liveManagerCount(): Int = MANAGERS.size

        /* ---------------- server tick dispatch ---------------- */

        @SubscribeEvent
        @JvmStatic
        fun onServerTick(@Suppress("UNUSED_PARAMETER") event: ServerTickEvent.Post) {
            MANAGERS.tickEach("scev-rpc", LOG) { _, mgr -> mgr.tick() }
        }
    }
}
