/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server

import com.mojang.logging.LogUtils
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.channels.Channel
import lekkit.rvvm.SoundHDA
import lekkit.rvvm.SoundSink
import lekkit.scev.common.tickEach
import lekkit.scev.network.SoundFramePayload
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.network.PacketDistributor

/**
 * Per-machine audio pipeline.
 *
 * Receives 48 kHz / 16-bit signed / mono PCM from RVVM's HDA worker
 * thread (or from the native ring polled on the tick thread), slices
 * into 20 ms frames, Opus-encodes, and on each server tick broadcasts
 * every queued frame as a [SoundFramePayload] to players within the
 * machine's audible radius.
 *
 * ## Threading
 *
 * Minecraft's player-list iteration and packet dispatch aren't safe on
 * non-server threads, so the pipeline hops through a single
 * thread-safe handoff — a [Channel] — from producer to consumer:
 *
 * ```
 *   RVVM HDA worker thread           Server tick thread
 *   ─────────────────────            ──────────────────
 *   onAudio(bytes)    ────►  [Channel.UNLIMITED]  ────►  tick() drains channel,
 *                                                        pollFromRing(), slices
 *                                                        frames, broadcasts.
 * ```
 *
 * The channel replaces the Java version's `synchronized(lock)` block.
 * Consumer-side state ([outBuffer], [pendingFrames], counters, Opus
 * encoder) is only touched from the tick thread, so no mutex is needed
 * there — the channel is the only cross-thread data structure.
 *
 * ## Frame size
 *
 * 20 ms of 48 kHz mono = 960 samples = 1920 bytes per frame. One frame
 * per server tick (20 TPS = 50 ms) in the steady state; backlogged
 * frames are drained in a single tick if the guest is ahead of the
 * server. Queue is capped — overflow drops the oldest frames to keep
 * latency bounded.
 *
 * ## Jukebox-like radius
 *
 * Vanilla jukeboxes use volume 4.0 with `SoundSource.RECORDS`; OpenAL's
 * linear distance attenuation makes that audible out to ~64 blocks. We
 * broadcast at the same radius and let the client-side streaming source
 * apply distance rolloff.
 */
class SoundStreamManager private constructor(
    private val machineUuid: UUID,
) : SoundSink {

    /** Bound HDA device — populated right after construction via [bindDevice]. */
    private var soundDevice: SoundHDA? = null

    /**
     * Cross-thread PCM ingress. HDA worker thread produces via
     * [onAudio]; the tick thread drains in [tick]. UNLIMITED buffer:
     * flow control happens at the frame-drop level below, not at the
     * channel level — backpressure on the HDA thread would block the
     * emulator.
     */
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)

    /** Scratch buffer for native ring polls. Sized to hold several
     *  ticks' worth of 48 kHz mono PCM without reallocating. */
    private val pollBuf = ByteArray(32 * 1024)

    /** Unconsumed PCM bytes awaiting frame boundary. Tick-thread only. */
    private val outBuffer = ByteArrayOutputStream()

    /** Complete frames ready to ship. Tick-thread only. */
    private val pendingFrames = ArrayDeque<ByteArray>(MAX_QUEUED_FRAMES)

    private var totalPcmBytesIn: Long = 0
    private var totalFramesOut: Long = 0
    private var droppedFrames: Long = 0

    /**
     * Per-stream Opus encoder. Lazy: created on first frame emission and
     * destroyed when the manager is unregistered. Opus encoders carry
     * stateful prediction, so one instance per source, on one thread.
     */
    private var opusEncoder: OpusCodec.Encoder? = null

    /** Track whether we've logged the first PCM callback yet. */
    private var loggedFirstPcm = false

    /**
     * Associate this manager with a [SoundHDA] device. Called by
     * `RvvmMachineBackend.initialize` right after the device is
     * attached; the manager polls the device's native ring buffer on
     * each server tick.
     */
    fun bindDevice(device: SoundHDA) {
        this.soundDevice = device
    }

    /* =========================================================== */
    /* SoundSink — runs on the RVVM HDA worker thread.             */
    /* =========================================================== */

    /**
     * Direct-callback ingestion path — kept for tests and for the JVM-
     * attach variant of the HDA sink wiring. Production uses the native
     * ring, drained via [tick] on the server tick thread.
     *
     * Sends PCM straight to the cross-thread [incoming] channel and
     * returns immediately. The tick thread picks it up on the next tick
     * and turns it into frames.
     */
    override fun onAudio(pcm: ByteArray?) {
        if (pcm == null || pcm.isEmpty()) return
        incoming.trySend(pcm)
    }

    /* =========================================================== */
    /* Server tick — runs on the main server thread.               */
    /* =========================================================== */

    /**
     * Drain the bound device's native ring buffer + the cross-thread
     * channel into staging buffers, slice any complete frames, and ship
     * them as network payloads to nearby players. Runs on the server
     * tick thread so Minecraft API calls (PacketDistributor,
     * ServerLevel) are safe here.
     */
    fun tick() {
        pollFromRing()
        drainIncoming()

        if (pendingFrames.isEmpty()) return

        val state = MachineManager.getMachineState(machineUuid) ?: return
        val level = state.level ?: return
        val pos = state.pos ?: return

        // Lazy-init encoder on first dispatch — machines without sound
        // cards never allocate one.
        val encoder = opusEncoder ?: OpusCodec.Encoder().also { opusEncoder = it }

        // Per-machine media clock shared with the video emit path.
        // Sample-counted PTS is drift-free against the stream's own
        // audio rate — the client can render video against this clock.
        val clock = state.clock

        val x = pos.x + 0.5
        val y = pos.y + 0.5
        val z = pos.z + 0.5
        while (true) {
            val frame = pendingFrames.removeFirstOrNull() ?: break
            val pts = clock.nextAudioPts(FRAME_SAMPLES)
            val encoded = try {
                encoder.encode(frame)
            } catch (e: RuntimeException) {
                LOG.warn("Opus encode failed for machine {}, dropping frame", machineUuid, e)
                continue
            }
            PacketDistributor.sendToPlayersNear(
                level, null, x, y, z, AUDIBLE_RADIUS_BLOCKS,
                SoundFramePayload(machineUuid, pts, encoded),
            )
            totalFramesOut++
        }
    }

    /** Drain the HDA native ring into [outBuffer], slicing frames as they complete. */
    private fun pollFromRing() {
        val device = soundDevice ?: return
        if (!device.hasRing()) return
        while (true) {
            val n = device.poll(pollBuf)
            if (n <= 0) break
            appendPcm(pollBuf, 0, n)
            if (n < pollBuf.size) break  // ring drained
        }
    }

    /** Drain whatever [onAudio] pushed onto the channel since last tick. */
    private fun drainIncoming() {
        while (true) {
            val chunk = incoming.tryReceive().getOrNull() ?: break
            appendPcm(chunk, 0, chunk.size)
        }
    }

    /** Append PCM bytes to [outBuffer] and slice any complete frames. */
    private fun appendPcm(src: ByteArray, off: Int, len: Int) {
        if (!loggedFirstPcm) {
            LOG.info("[scev-audio] first PCM for {} ({} bytes)", machineUuid, len)
            loggedFirstPcm = true
        }
        totalPcmBytesIn += len.toLong()
        outBuffer.write(src, off, len)
        drainFrames()
    }

    /**
     * Slice as many complete [FRAME_BYTES] chunks as possible from
     * [outBuffer] and enqueue them. Overflow (queue full) drops the
     * oldest pending frame — preserves latency, loses audio.
     */
    private fun drainFrames() {
        while (outBuffer.size() >= FRAME_BYTES) {
            val buf = outBuffer.toByteArray()
            val frame = buf.copyOfRange(0, FRAME_BYTES)
            outBuffer.reset()
            val leftover = buf.size - FRAME_BYTES
            if (leftover > 0) outBuffer.write(buf, FRAME_BYTES, leftover)

            if (pendingFrames.size >= MAX_QUEUED_FRAMES) {
                pendingFrames.removeFirst()
                droppedFrames++
            }
            pendingFrames.addLast(frame)
        }
    }

    /* =========================================================== */
    /* Inspection — test-only accessors.                           */
    /* =========================================================== */

    fun totalPcmBytesIn(): Long = totalPcmBytesIn
    fun totalFramesOut(): Long = totalFramesOut
    fun droppedFrames(): Long = droppedFrames
    fun pendingFrameCount(): Int = pendingFrames.size

    /** Test-only: drain and return the next queued frame without dispatching. */
    fun pollFrame(): ByteArray? = pendingFrames.removeFirstOrNull()

    companion object {
        private val LOG = LogUtils.getLogger()

        /**
         * Rate at which the guest HDA codec produces PCM. Matches the
         * `CODEC_PARAM_SUPP_PCM_SIZE_RATES` response in RVVM's
         * `sound-hda.c` (advertises only 48 kHz) and the stream worker's
         * pacing constant. The three MUST stay in lockstep: changing one
         * and forgetting the others leads to 4× slow playback and
         * crackling.
         */
        const val GUEST_SAMPLE_RATE_HZ: Int = 48_000

        /**
         * Rate the client plays at. Matches the guest rate — we ship PCM
         * unprocessed to the client's OpenAL source.
         */
        const val CLIENT_SAMPLE_RATE_HZ: Int = GUEST_SAMPLE_RATE_HZ
        const val BYTES_PER_SAMPLE: Int = 2 // 16-bit signed LE

        const val FRAME_MS: Int       = 20
        const val FRAME_SAMPLES: Int  = (CLIENT_SAMPLE_RATE_HZ * FRAME_MS) / 1000 // 960
        const val FRAME_BYTES: Int    = FRAME_SAMPLES * BYTES_PER_SAMPLE          // 1920

        /**
         * Maximum queued frames before we drop the oldest. 50 frames × 20 ms =
         * 1 s of latency cushion. If the server tick stalls for longer than
         * that, we prefer "sound skips, stays in sync" over "sound keeps
         * playing but is N seconds behind video".
         */
        const val MAX_QUEUED_FRAMES: Int = 50

        /**
         * Audibility radius in blocks — matches vanilla jukebox volume 4.0
         * with OpenAL's default rolloff.
         */
        const val AUDIBLE_RADIUS_BLOCKS: Double = 64.0

        /** Registry of live managers, one per machine UUID. */
        private val MANAGERS = ConcurrentHashMap<UUID, SoundStreamManager>()

        /**
         * Create and register a manager for the given machine UUID. The
         * caller is responsible for installing this as the sink on the
         * RVVM HDA device (via [lekkit.rvvm.SoundHDA]) and for calling
         * [unregister] at machine teardown.
         */
        @JvmStatic
        fun create(machineUuid: UUID): SoundStreamManager {
            val mgr = SoundStreamManager(machineUuid)
            MANAGERS[machineUuid] = mgr
            return mgr
        }

        /** Release a manager. Safe to call with an unknown UUID. */
        @JvmStatic
        fun unregister(machineUuid: UUID) {
            val mgr = MANAGERS.remove(machineUuid) ?: return
            mgr.incoming.close()
            mgr.opusEncoder?.close()
            mgr.opusEncoder = null
        }

        /** Read-only snapshot for tests and debug. */
        @JvmStatic
        fun liveManagerCount(): Int = MANAGERS.size

        /** For tests: retrieve a manager by UUID, or `null` if unregistered. */
        @JvmStatic
        fun get(machineUuid: UUID): SoundStreamManager? = MANAGERS[machineUuid]

        /** Event listener invoked by NeoForge on each server tick. */
        @SubscribeEvent
        @JvmStatic
        fun onServerTick(@Suppress("UNUSED_PARAMETER") event: ServerTickEvent.Post) {
            MANAGERS.tickEach("scev-audio", LOG) { _, mgr -> mgr.tick() }
        }

        /**
         * 4:1 box-filter downsampler. Unused by production (we ship 48 kHz
         * straight through) but kept available as a utility — the math is
         * unit-tested and trivially reusable if bandwidth ever becomes a
         * concern and a better resampler isn't yet written.
         *
         * Input: [len] bytes (must be a multiple of 8) of 16-bit signed
         * LE PCM. Output: [len] / 4 bytes, each output sample the
         * arithmetic mean of 4 consecutive input samples.
         */
        @JvmStatic
        fun downsample4to1(input: ByteArray, off: Int, len: Int): ByteArray {
            val ratio = 4
            val groupBytes = ratio * 2
            require(len % groupBytes == 0) {
                "downsample input must be multiple of $groupBytes bytes, got $len"
            }
            val outLen = len / ratio
            val out = ByteArray(outLen)
            val groups = len / groupBytes
            for (g in 0 until groups) {
                val base = off + g * groupBytes
                var sum = 0
                for (i in 0 until ratio) {
                    val lo = input[base + i * 2].toInt() and 0xFF
                    val hi = input[base + i * 2 + 1].toInt() // sign-extended
                    sum += (hi shl 8) or lo
                }
                var avg = sum / ratio
                if (avg > 32767) avg = 32767
                if (avg < -32768) avg = -32768
                out[g * 2]     = (avg and 0xFF).toByte()
                out[g * 2 + 1] = ((avg shr 8) and 0xFF).toByte()
            }
            return out
        }
    }
}
