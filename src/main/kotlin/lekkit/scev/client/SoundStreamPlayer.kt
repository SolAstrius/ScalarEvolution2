/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client

import com.mojang.logging.LogUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import lekkit.scev.common.Millis
import lekkit.scev.network.SoundFramePayload
import lekkit.scev.server.MachineManager
import lekkit.scev.server.OpusCodec
import lekkit.scev.server.SoundStreamManager
import net.minecraft.client.Minecraft
import org.lwjgl.openal.AL10

/**
 * Client-side receiver for [SoundFramePayload]. Maintains one OpenAL
 * streaming source per machine UUID, positioned at the hosting block's
 * world coordinates.
 *
 * **Thread split.**
 *
 * - [acceptRemote] runs on Netty's network thread. It must NOT touch
 *   OpenAL (the audio context is owned by the render thread) or any
 *   Minecraft-client state. It only drives the media clock and appends
 *   the Opus-encoded bytes to the per-source backlog.
 * - [clientTick] runs on the Minecraft render thread. All OpenAL calls
 *   live here: source/buffer allocation, decode + upload, unqueue,
 *   play/resume, positional placement, idle teardown.
 *
 * The two rendezvous on a per-[Source] monitor (`@Synchronized` on
 * Source's methods — Kotlin lowers to `synchronized(this)`). The outer
 * map is a [ConcurrentHashMap], so concurrent `acceptRemote` calls for
 * different UUIDs don't serialize against each other — an improvement
 * over the single-monitor Java version.
 *
 * **Lifecycle.** A source appears on the first frame for a given UUID.
 * It's torn down when:
 * - [destroy] is called explicitly (server signalled dispose)
 * - [destroyAll] runs on client disconnect
 * - The source has been silent (empty queue) for [IDLE_DESTROY] — freed
 *   automatically from [clientTick]
 *
 * **Format.** Matches [SoundStreamManager]: 48 kHz / 16-bit signed LE /
 * mono. OpenAL's mono format (`AL_FORMAT_MONO16`) is 3D-positional-audio
 * friendly by default.
 */
object SoundStreamPlayer {
    private val LOG = LogUtils.getLogger()

    /** OpenAL's format enum for 16-bit signed mono. */
    private const val AL_FORMAT = AL10.AL_FORMAT_MONO16

    /** Sample rate delivered by the server. */
    private const val SAMPLE_RATE_HZ = SoundStreamManager.CLIENT_SAMPLE_RATE_HZ

    /**
     * Number of OpenAL buffers in the ring per source. Each buffer holds
     * one packet's worth of PCM (20 ms at 48 kHz mono = 1920 bytes).
     *
     * Sizing: server tick jitter under Minecraft client load can hit
     * ±30-40 ms, so a 60 ms (3-buffer) ring underruns constantly — user
     * hears 20 ms of audio followed by 30 ms of silence, on repeat.
     * Sized at 16 = 320 ms nominal buffer, enough headroom that the
     * queue never empties between packets even when the client drops a
     * frame for GC.
     */
    private const val BUFFERS_PER_SOURCE = 16

    /**
     * Don't start playback until at least this many buffers are queued.
     * Trades ~60 ms of initial latency for smooth first playback;
     * without it the source starts on the first 20 ms buffer, drains,
     * and the first thing the user hears is a stutter.
     */
    private const val PREBUFFER_THRESHOLD = 3

    /**
     * If a source has been silent (empty queue) for this long, destroy
     * it. Keeps the OpenAL resource count bounded even if the server
     * stops telling us about a machine.
     */
    private val IDLE_DESTROY = Millis(2_000L)

    /**
     * Maximum bytes of queued Opus packets per machine before we drop
     * the oldest. 5 seconds of Opus at the current ~250 B / 20 ms
     * sizing = ~62 500 B; headroom × 5 for client tick stalls.
     * Preserves "latency over completeness" semantics — if the client
     * falls behind, skip rather than build up a growing delay.
     */
    private const val MAX_QUEUED_BYTES = 5 * OpusCodec.MAX_ENCODED_BYTES * 50 /* frames/sec */

    private val sources = ConcurrentHashMap<UUID, Source>()

    /* =================================================================== */
    /* Per-source state                                                    */
    /* =================================================================== */

    /**
     * All mutable fields live under the monitor of [Source] itself —
     * network-thread and render-thread mutations rendezvous here. The
     * outer [sources] map is a ConcurrentHashMap so cross-UUID traffic
     * doesn't serialize against this monitor.
     */
    private class Source {
        /** OpenAL source handle. `-1` until allocated on the render thread. */
        var sourceId: Int = -1

        /** Fresh, unused buffer IDs ready to be filled. */
        val freeBuffers: ArrayDeque<Int> = ArrayDeque(BUFFERS_PER_SOURCE)

        /**
         * Opus-encoded packets queued for decode + OpenAL upload. Each
         * entry is one Opus frame (~250 B at 128 kbps) → decodes to
         * 1920 B of 48 kHz mono 16-bit PCM.
         */
        val pendingOpus: ArrayDeque<ByteArray> = ArrayDeque()

        /** Byte-count of [pendingOpus], tracked to enforce [MAX_QUEUED_BYTES]. */
        var pendingBytes: Int = 0

        /** `Millis.wall()` of the last accepted frame. Drives idle-destroy. */
        var lastFrameAt: Millis = Millis.wall()

        /**
         * Per-source Opus decoder. Stateful across frames — one per
         * stream, only used on the render thread. Lazy-inited on first
         * decode; freed in [freeResources].
         */
        var decoder: OpusCodec.Decoder? = null

        /**
         * Network-thread side: append a freshly-arrived Opus frame,
         * evicting oldest bytes to stay under [MAX_QUEUED_BYTES].
         */
        @Synchronized
        fun enqueueOpus(frame: ByteArray) {
            while (pendingOpus.isNotEmpty() && pendingBytes + frame.size > MAX_QUEUED_BYTES) {
                val evicted = pendingOpus.removeFirst()
                pendingBytes -= evicted.size
            }
            pendingOpus.addLast(frame)
            pendingBytes += frame.size
            lastFrameAt = Millis.wall()
        }

        /**
         * Drop every pending packet (reset byte count). Called when we
         * fail to allocate an OpenAL source — without this the packets
         * would accumulate forever.
         */
        @Synchronized
        fun clearPending() {
            pendingOpus.clear()
            pendingBytes = 0
        }

        /**
         * Pop one queued Opus packet or `null` if the queue is empty.
         * Render-thread side; decrements [pendingBytes] under the
         * monitor so the network thread sees a consistent pair.
         */
        @Synchronized
        fun popOpus(): ByteArray? {
            val frame = pendingOpus.removeFirstOrNull() ?: return null
            pendingBytes -= frame.size
            return frame
        }

        @Synchronized fun pendingBytesSnapshot(): Int = pendingBytes
        @Synchronized fun lastFrameAtSnapshot(): Millis = lastFrameAt
        @Synchronized fun pendingIsEmpty(): Boolean = pendingOpus.isEmpty()
    }

    /* =================================================================== */
    /* Network thread entry — buffer the frame, don't touch OpenAL.        */
    /* =================================================================== */

    /**
     * Receive a PCM frame from the server. Runs on the Netty network
     * thread.
     */
    @JvmStatic
    fun acceptRemote(payload: SoundFramePayload) {
        if (payload.pcm.isEmpty()) return

        // Drive the shared per-machine media clock off arriving audio
        // PTS. Audio is the master: its PTS is drift-free (sample-
        // counted server side), so anchoring the MediaClock here keeps
        // video — which consults the same clock via DisplayManager —
        // locked to audio. Safe to call from the network thread.
        MediaClockRegistry.onIncoming(payload.machineUuid, payload.ptsMicros.value)

        val source = sources.getOrPut(payload.machineUuid) { Source() }
        source.enqueueOpus(payload.pcm)
    }

    /* =================================================================== */
    /* Render thread entry — all OpenAL calls live here.                   */
    /* =================================================================== */

    /**
     * Must be called from the Minecraft render thread. Processes every
     * source: flushes queued PCM into OpenAL buffers, recycles buffers
     * OpenAL has finished with, updates positional data, and culls idle
     * sources.
     */
    @JvmStatic
    fun clientTick() {
        val mc = Minecraft.getInstance() ?: return
        val now = Millis.wall()
        val toDestroy: MutableList<UUID> = mutableListOf()

        for ((uuid, source) in sources) {
            if (!tickSource(mc, uuid, source, now)) {
                toDestroy += uuid
            }
        }
        for (uuid in toDestroy) {
            sources.remove(uuid)?.let(::freeResources)
        }
    }

    /**
     * Returns `true` to keep the source, `false` if it's been silent
     * long enough to tear down.
     */
    private fun tickSource(
        mc: Minecraft,
        uuid: UUID,
        source: Source,
        now: Millis,
    ): Boolean {
        // Lazy-allocate the OpenAL source on first touch from this
        // thread. gen* calls are only valid on the audio thread.
        if (source.sourceId == -1) {
            val newSource = AL10.alGenSources()
            if (newSource == 0 || AL10.alGetError() != AL10.AL_NO_ERROR) {
                source.clearPending()  // out of OpenAL sources, drop queue
                return true
            }
            repeat(BUFFERS_PER_SOURCE) { source.freeBuffers.addLast(AL10.alGenBuffers()) }
            configureSource(newSource)
            source.sourceId = newSource
        }
        if (source.decoder == null) {
            source.decoder = OpusCodec.Decoder()
        }

        positionSource(mc, uuid, source)
        recycleProcessedBuffers(source)
        decodeAndQueue(uuid, source)
        maybeStartPlayback(source)

        // Idle teardown: if the queue has been empty AND OpenAL has
        // nothing queued, and it's been IDLE_DESTROY since the last
        // frame arrived, free the source.
        val idle = source.pendingIsEmpty()
                && AL10.alGetSourcei(source.sourceId, AL10.AL_BUFFERS_QUEUED) == 0
                && (now - source.lastFrameAtSnapshot()) > IDLE_DESTROY
        return !idle
    }

    /**
     * Place the source at the hosting block's world coords if we know
     * them (always the case once `powerOn` has run server-side), else
     * peg it to the listener's camera so the player at least hears
     * something.
     */
    private fun positionSource(mc: Minecraft, uuid: UUID, source: Source) {
        val state = MachineManager.getMachineState(uuid)
        val pos = state?.pos
        if (pos != null) {
            AL10.alSource3f(
                source.sourceId, AL10.AL_POSITION,
                pos.x + 0.5f, pos.y + 0.5f, pos.z + 0.5f,
            )
            return
        }
        val camera = mc.gameRenderer?.mainCamera?.position ?: return
        AL10.alSource3f(
            source.sourceId, AL10.AL_POSITION,
            camera.x.toFloat(), camera.y.toFloat(), camera.z.toFloat(),
        )
    }

    private fun recycleProcessedBuffers(source: Source) {
        var processed = AL10.alGetSourcei(source.sourceId, AL10.AL_BUFFERS_PROCESSED)
        while (processed > 0) {
            source.freeBuffers.addLast(AL10.alSourceUnqueueBuffers(source.sourceId))
            processed--
        }
    }

    private fun decodeAndQueue(uuid: UUID, source: Source) {
        val decoder = source.decoder ?: return
        while (source.freeBuffers.isNotEmpty()) {
            val opus = source.popOpus() ?: break
            val pcm: ByteArray = try {
                decoder.decode(opus)
            } catch (e: RuntimeException) {
                LOG.warn("Opus decode failed for machine {}, dropping frame", uuid, e)
                continue
            }
            val nativeBuf = ByteBuffer.allocateDirect(pcm.size)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(pcm)
                .apply { rewind() }
            val bufId = source.freeBuffers.removeFirst()
            AL10.alBufferData(bufId, AL_FORMAT, nativeBuf, SAMPLE_RATE_HZ)
            AL10.alSourceQueueBuffers(source.sourceId, bufId)
        }
    }

    /**
     * Start (or resume) playback once we have enough buffers queued to
     * survive one tick of jitter. Before: we started on the first 20 ms
     * buffer and immediately drained, which sounded like
     * "[20 ms audio] [30 ms silence] [20 ms audio] …" — unmistakably
     * stuttery. After an underrun OpenAL transitions to `AL_STOPPED`;
     * we re-prebuffer to [PREBUFFER_THRESHOLD] before kicking it again.
     */
    private fun maybeStartPlayback(source: Source) {
        val state = AL10.alGetSourcei(source.sourceId, AL10.AL_SOURCE_STATE)
        if (state == AL10.AL_PLAYING) return
        val queued = AL10.alGetSourcei(source.sourceId, AL10.AL_BUFFERS_QUEUED)
        if (queued >= PREBUFFER_THRESHOLD) {
            AL10.alSourcePlay(source.sourceId)
        }
    }

    /* =================================================================== */
    /* External teardown                                                   */
    /* =================================================================== */

    /** Destroy the streaming source for a specific machine, if any. */
    @JvmStatic
    fun destroy(machineUuid: UUID) {
        sources.remove(machineUuid)?.let(::freeResources)
    }

    /** Destroy every streaming source. Called on client disconnect. */
    @JvmStatic
    fun destroyAll() {
        val snapshot = ArrayList(sources.values)
        sources.clear()
        snapshot.forEach(::freeResources)
    }

    /** Test-only: how many sources are currently live. */
    @JvmStatic
    fun liveSourceCount(): Int = sources.size

    /** Test-only: how many bytes are queued for a given machine (`-1` if no source). */
    @JvmStatic
    fun pendingBytes(machineUuid: UUID): Int =
        sources[machineUuid]?.pendingBytesSnapshot() ?: -1

    /* =================================================================== */
    /* Helpers                                                             */
    /* =================================================================== */

    /**
     * Configure positional-audio parameters on a fresh OpenAL source so
     * the client's listener hears attenuation with distance — matches
     * vanilla's jukebox feel.
     */
    private fun configureSource(sourceId: Int) {
        AL10.alSourcef(sourceId, AL10.AL_GAIN, 1.0f)
        AL10.alSourcef(sourceId, AL10.AL_PITCH, 1.0f)
        AL10.alSourcef(sourceId, AL10.AL_REFERENCE_DISTANCE, 4.0f)
        AL10.alSourcef(sourceId, AL10.AL_MAX_DISTANCE, 64.0f)
        AL10.alSourcef(sourceId, AL10.AL_ROLLOFF_FACTOR, 1.0f)
        AL10.alSourcei(sourceId, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE)
    }

    private fun freeResources(source: Source) {
        if (source.sourceId != -1) {
            AL10.alSourceStop(source.sourceId)
            val queued = AL10.alGetSourcei(source.sourceId, AL10.AL_BUFFERS_QUEUED)
            repeat(queued) {
                source.freeBuffers.addLast(AL10.alSourceUnqueueBuffers(source.sourceId))
            }
            AL10.alDeleteSources(source.sourceId)
        }
        for (buf in source.freeBuffers) AL10.alDeleteBuffers(buf)
        source.freeBuffers.clear()
        source.decoder?.close()
        source.decoder = null
    }
}
