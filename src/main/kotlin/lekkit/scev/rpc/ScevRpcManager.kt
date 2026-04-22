/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.rpc

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
import lekkit.scev.common.tickEach
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
    private val stream = FrameStream(MAX_FRAME_BYTES)
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
    fun droppedFrames(): Long = stream.droppedFrames()

    /* ---------------- tick ---------------- */

    /** Visible for tests; normally driven by [onServerTick]. */
    fun tick() {
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
            for (i in 0 until kn) {
                val b = drainBuf[i]
                if (b == '\n'.code.toByte() || kernelConsoleLineLen == kernelConsoleLine.size) {
                    if (kernelConsoleLineLen > 0) {
                        val line = String(kernelConsoleLine, 0, kernelConsoleLineLen, StandardCharsets.UTF_8)
                        LOG.debug("[scev-kernel {}] {}", machineUuid, line)
                        kernelConsoleLineLen = 0
                    }
                } else if (b != '\r'.code.toByte()) {
                    kernelConsoleLine[kernelConsoleLineLen++] = b
                }
            }
            if (kn < drainBuf.size) break
        }
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
                scope.launch {
                    val response = _dispatcher.dispatch(frame)
                    writeFrame(response)
                    responsesOut++
                }
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
        }
    }

    /** Encode, COBS-frame, and push to the guest RX ring. */
    private fun writeFrame(f: RpcFrame) {
        val payload = RpcProtocol.encode(f)
        if (payload.size > MAX_FRAME_BYTES) {
            LOG.warn(
                "[scev-rpc] {} dropping outbound frame larger than max ({} > {})",
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

    /* ---------------- companion (static) ---------------- */

    companion object {
        private val LOG = LogUtils.getLogger()

        /** Max COBS-encoded frame size the framer will accumulate before resetting. */
        @JvmField val MAX_FRAME_BYTES: Int = 8192

        /** Scratch buffer size for draining serial TX per tick. */
        private const val DRAIN_CHUNK = 4096

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
            ScevRpcHandlers.registerDefaults(mgr._dispatcher, uuid)
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
