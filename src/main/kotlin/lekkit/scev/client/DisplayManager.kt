/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client

import com.mojang.logging.LogUtils
import java.util.TreeMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import lekkit.scev.core.codec.BgraYuv
import lekkit.scev.core.codec.H264Decoder
import lekkit.scev.core.time.Micros
import lekkit.scev.network.DisplayPayload
import lekkit.scev.network.KeyframeRequestPayload
import lekkit.scev.server.MachineManager
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.network.PacketDistributor

/**
 * Client-side cache of framebuffer state per-machine, with A/V-sync-aware
 * jitter buffering for multiplayer video frames.
 *
 * Two paths into this cache:
 *
 * - **Singleplayer**: [get] creates a zero-copy [DisplayState] backed by
 *   the server-side `MachineState.getDisplay()`'s DMA buffer. No
 *   network, no jitter buffer, no PTS. Video is always latest.
 *
 * - **Multiplayer**: [acceptRemote] runs on the network thread, enqueues
 *   the payload into a per-UUID jitter buffer keyed by PTS, and hands
 *   the payload's PTS to [MediaClockRegistry] so the shared media clock
 *   stays live. [onClientTick] runs on the render thread and for each
 *   active machine picks the newest frame whose PTS ≤ `mediaClock.now()`,
 *   applying it to the cached DisplayState. Older frames are discarded;
 *   future frames stay buffered until their PTS comes due.
 *
 * The SP short-circuit sidesteps the jitter buffer entirely, so SP video
 * runs slightly ahead of SP audio (audio still goes through the full
 * Opus + memory-pipe path, ~60 ms prebuffer). Acceptable: A/V desync at
 * tens of ms is imperceptible, and SP users aren't the audience for
 * lipsync-critical content.
 */
object DisplayManager {
    private val LOG = LogUtils.getLogger()

    /**
     * When `true`, a client with a local [MachineManager] entry for a
     * UUID reads the server's DMA framebuffer directly via the
     * zero-copy path in [get], skipping the network round-trip (and
     * the codec) entirely.
     *
     * **Currently disabled.** The H.264 wire format is new and we want
     * singleplayer to exercise the encode → network → decode path so
     * in-game testing matches the MP code path end-to-end. Flip back
     * to `true` once the codec is stable if we care about squeezing
     * the last few ms of SP latency — video PTS is preserved either
     * way via [MediaClockRegistry].
     */
    private const val OPTIMIZE_SINGLEPLAYER = false

    /**
     * Cap on buffered-but-not-yet-presented video frames per machine.
     * Video at 5 Hz × ~1.2 MB (current raw-BGRA) × 30 frames ≈ 180 MB
     * of memory, worst case, if presentation completely stalls — which
     * it shouldn't, but the cap bounds the damage.
     */
    private const val MAX_QUEUED_FRAMES = 30

    /**
     * Frames older than this before presentation are discarded on
     * arrival — late arrivals from a dropped-behind network or a
     * post-reset straggler from a previous epoch. Roughly one second
     * of drift tolerance.
     */
    private val LATE_DROP_THRESHOLD = Micros(1_000_000L)

    private val displays = ConcurrentHashMap<UUID, DisplayState>()

    /**
     * Per-UUID H.264 decoder + reusable BGRA scratch. Decoders are
     * stateful (frames must be fed in order) and are lazy-created on
     * first payload for a given UUID. Evicted in [destroy] /
     * [recycleAll] alongside the [DisplayState] and jitter buffer.
     */
    private class Decoder {
        val h264: H264Decoder = H264Decoder()
        var bgraScratch: ByteArray = ByteArray(0)
    }
    private val decoders = ConcurrentHashMap<UUID, Decoder>()

    /**
     * Per-UUID "when did we last ping the server for a keyframe?" so
     * repeated decode failures on a broken stream don't generate a
     * request storm. One request per second per UUID is plenty —
     * server-side the request-to-IDR round-trip is a few ticks at
     * worst.
     */
    private val lastKeyframeRequestAt = ConcurrentHashMap<UUID, Long>()
    private const val KEYFRAME_REQUEST_COOLDOWN_MS = 1000L

    /**
     * Per-UUID jitter buffer. Each TreeMap is keyed by PTS so `floorEntry`
     * gets the newest frame ≤ now in log(n) time. Outer map is
     * ConcurrentHashMap for lock-free lookup; inner TreeMap mutations are
     * synchronized on the TreeMap instance itself.
     */
    private val pending = ConcurrentHashMap<UUID, TreeMap<Micros, DisplayPayload>>()

    /**
     * Fetch (and auto-create in singleplayer) the [DisplayState] for a
     * machine. Returns `null` if no state exists and we can't build one
     * from a local [MachineManager] entry.
     */
    @JvmStatic
    fun get(uuid: UUID): DisplayState? {
        var s = displays[uuid]

        // Evict stale singleplayer cache entries: the referenced
        // MachineState was destroyed (power-off) but this cache still
        // holds a DisplayState pointing at the dead backend. Without
        // eviction, the next lookup would keep returning the stale
        // entry and the client would show the old VM's final frame
        // indefinitely.
        if (s != null && s.isStale) {
            displays.remove(uuid)
            s.destroy()
            s = null
        }

        if (OPTIMIZE_SINGLEPLAYER && s == null) {
            val ms = MachineManager.getMachineState(uuid)
            if (ms != null && ms.display != null) {
                s = DisplayState(ms)
                displays[uuid] = s
            }
        }
        return s
    }

    /**
     * Create a fresh remote-buffer DisplayState (or resize an existing
     * one if the dimensions changed). Called from the client-tick
     * apply path in multiplayer.
     */
    @JvmStatic
    fun createOrResize(uuid: UUID, width: Int, height: Int): DisplayState {
        val existing = displays[uuid]
        if (existing != null && (existing.width != width || existing.height != height)) {
            existing.destroy()
            displays.remove(uuid)
        } else if (existing != null) {
            return existing
        }
        val fresh = DisplayState(uuid, width, height)
        displays[uuid] = fresh
        return fresh
    }

    @JvmStatic
    fun destroy(uuid: UUID) {
        displays.remove(uuid)?.destroy()
        pending.remove(uuid)
        decoders.remove(uuid)?.h264?.close()
        MediaClockRegistry.remove(uuid)
    }

    /**
     * Network handler entry for [lekkit.scev.network.DisplayDisposePayload]
     * — server has torn down the per-machine encoder, this client should
     * stop showing the last frame. Drops the cached state + buffered
     * frames so the next render falls back to "no display" (black).
     */
    @JvmStatic
    fun dispose(uuid: UUID) {
        destroy(uuid)
    }

    /** Tear down every cached display + jitter buffer. Called on client disconnect. */
    @JvmStatic
    fun recycleAll() {
        displays.values.forEach { it.destroy() }
        displays.clear()
        pending.clear()
        decoders.values.forEach { it.h264.close() }
        decoders.clear()
        MediaClockRegistry.clear()
    }

    /**
     * Network handler entry — called on the Netty network thread for
     * every arriving [DisplayPayload]. Updates the media clock and
     * enqueues into the jitter buffer (SP also goes through this in
     * the current `OPTIMIZE_SINGLEPLAYER=false` config).
     *
     * Stream end is signalled by [lekkit.scev.network.DisplayDisposePayload]
     * via [dispose], not by an in-band size-0 sentinel.
     */
    @JvmStatic
    fun acceptRemote(payload: DisplayPayload) {
        val uuid = payload.machineUuid

        if (OPTIMIZE_SINGLEPLAYER && MachineManager.getMachineState(uuid) != null) {
            // In SP the client renderer reads the server-side
            // MachineState directly via get(). Buffering this payload
            // would just waste memory.
            return
        }

        MediaClockRegistry.onIncoming(uuid, payload.ptsMicros.value)

        // Late-arrival drop: if the frame's PTS is already well
        // behind the media clock, it missed its window. A TreeMap
        // pick-newest-before-now would include it otherwise and we'd
        // render an out-of-order frame that's older than a frame we
        // already showed.
        val clock = MediaClockRegistry.get(uuid)
        val now = clock.currentPts()
        if (now - payload.ptsMicros > LATE_DROP_THRESHOLD) return

        val buffer = pending.computeIfAbsent(uuid) { TreeMap() }
        synchronized(buffer) {
            buffer[payload.ptsMicros] = payload
            while (buffer.size > MAX_QUEUED_FRAMES) {
                buffer.pollFirstEntry()  // drop oldest under pressure
            }
        }
    }

    /**
     * Client tick entry: for each machine with buffered video, decode
     * every frame whose PTS ≤ `mediaClock.now()` in order and present
     * the last one. Frames newer stay buffered until their PTS comes due.
     *
     * H.264 is a referenced codec: a P-frame references the previous
     * reference picture, which in turn may reference earlier frames all
     * the way back to an IDR. Skipping any frame between IDR and "now"
     * on a lag-catchup breaks the reference chain and every subsequent
     * P-frame reports `dsError*` until the next IDR arrives — and if
     * the client is still catching up, that next IDR also gets skipped,
     * creating a sustained decode storm. So we decode the whole run to
     * keep the decoder's reference state consistent, and only emit the
     * latest decoded frame to the display pipeline.
     */
    @JvmStatic
    fun onClientTick(@Suppress("UNUSED_PARAMETER") event: ClientTickEvent.Post) {
        if (pending.isEmpty()) return
        for ((uuid, buffer) in pending) {
            val clock = MediaClockRegistry.get(uuid)
            val now = clock.currentPts()
            val toApply: List<DisplayPayload> = synchronized(buffer) {
                val due = buffer.headMap(now, true)
                if (due.isEmpty()) return@synchronized emptyList()
                val frames = ArrayList(due.values)
                due.clear()
                frames
            }
            if (toApply.isNotEmpty()) apply(uuid, toApply)
        }
    }

    /**
     * Decode every payload in order (maintaining decoder reference-chain
     * state) and display only the latest successfully decoded frame. If
     * any individual decode fails we request a keyframe but keep
     * draining the queue — a later payload may be an IDR that resets
     * the decoder mid-run.
     */
    private fun apply(uuid: UUID, payloads: List<DisplayPayload>) {
        // `payload.pixels` is H.264 NAL bytes. Decode all due payloads
        // into YUV I420 to keep the decoder's reference state in sync
        // with the encoder, then color-convert only the latest to BGRA.
        // Discarded intermediate decodes still have to run: H.264 P-
        // frames reference the previous reference picture, so we can't
        // "skip ahead" without breaking the chain.
        val decoder = decoders.computeIfAbsent(uuid) { Decoder() }
        var latest: H264Decoder.DecodedFrame? = null
        var sawError = false
        for (payload in payloads) {
            val decoded = try {
                decoder.h264.decode(payload.pixels)
            } catch (e: IllegalStateException) {
                LOG.debug("[scev-h264] decode error for {}: {}", uuid, e.message)
                sawError = true
                continue
            }
            if (decoded != null) latest = decoded
        }

        if (latest == null) {
            // Either all frames threw (reference chain broken) or none
            // produced a complete picture (SPS/PPS-only, first-packet
            // P-frame, etc.). Nudge the server for an IDR so the next
            // keyframe resyncs the decoder.
            maybeRequestKeyframe(uuid)
            return
        }
        if (sawError) {
            // At least one frame in the run failed; the run recovered
            // on a later IDR but we're still at risk of drifting if the
            // underlying cause repeats. Proactively request a fresh
            // keyframe so the next run starts from a clean IDR.
            maybeRequestKeyframe(uuid)
        }

        val w = latest.width
        val h = latest.height
        val bgraSize = w * h * 4
        if (decoder.bgraScratch.size != bgraSize) {
            decoder.bgraScratch = ByteArray(bgraSize)
        }
        BgraYuv.i420ToBgra(latest.yuv, w, h, decoder.bgraScratch)

        val state = createOrResize(uuid, w, h)
        state.updateRemoteBuffer(decoder.bgraScratch)
    }

    /**
     * Fire-and-forget request to the server for an IDR on [uuid]'s
     * stream. Rate-limited per UUID to one request per
     * [KEYFRAME_REQUEST_COOLDOWN_MS] so a sustained broken stream
     * doesn't hammer the server.
     */
    private fun maybeRequestKeyframe(uuid: UUID) {
        val now = System.currentTimeMillis()
        val prev = lastKeyframeRequestAt[uuid]
        if (prev != null && now - prev < KEYFRAME_REQUEST_COOLDOWN_MS) return
        lastKeyframeRequestAt[uuid] = now
        PacketDistributor.sendToServer(KeyframeRequestPayload(uuid))
    }
}
