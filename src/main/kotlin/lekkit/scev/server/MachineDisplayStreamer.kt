/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server

import java.nio.ByteBuffer
import java.util.UUID
import lekkit.scev.core.codec.BgraYuv
import lekkit.scev.core.codec.H264Encoder
import lekkit.scev.core.time.MachineClock
import lekkit.scev.machine.FramebufferView
import lekkit.scev.network.DisplayDisposePayload
import lekkit.scev.network.DisplayPayload
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.neoforged.neoforge.network.PacketDistributor

/**
 * Per-machine framebuffer-to-network pipeline. Owns an [H264Encoder]
 * sized to the current framebuffer dimensions, encodes each tick's
 * frame, and broadcasts the resulting NAL bytes inside a
 * [DisplayPayload] to every player within
 * [DISPLAY_BROADCAST_RADIUS] of the machine.
 *
 * Lifetime is bound to the host block-entity. Pulled out of
 * `ComputerCaseBlockEntity` to keep the BE focused on inventory +
 * IMachineHandle + redstone, and so the encode + broadcast logic is
 * testable in isolation. The BE owns one of these as a field, lazily
 * allocates it (or recreates) when the framebuffer first appears /
 * resizes, and disposes on power-off / chunk unload.
 *
 * **Threading.** All methods run on the server tick thread —
 * `tick`/`dispose`/`close` are called from `serverTick` and from
 * `powerOff`/`setRemoved` which are also server-side.
 */
class MachineDisplayStreamer : AutoCloseable {

    /**
     * Per-machine encoder. Lazily initialised on the first frame of a
     * given dimension; re-created if the framebuffer resizes (VM
     * switched graphics mode). `null` until first use.
     */
    private var encoder: H264Encoder? = null
    private var encoderWidth: Int = -1
    private var encoderHeight: Int = -1

    /**
     * Reusable YUV I420 scratch buffer. Sized `width * height * 3 / 2`
     * to match the encoder dimensions. Reallocated on resize.
     */
    private var yuvScratch: ByteArray? = null

    /**
     * Frames emitted since the last forced IDR. Drives the periodic-
     * keyframe heuristic: force an IDR every [IDR_INTERVAL_FRAMES] so a
     * late-joining client's decoder recovers within bounded time
     * without needing a client→server keyframe-request protocol.
     *
     * A proper "new watcher detected" trigger (à la oc2r's
     * ProjectorLoadBalancer) would be tighter — 0 ms recovery for the
     * specific new client, no bandwidth cost steady-state — but
     * requires keep-alive pings and per-watcher state we don't have
     * yet.
     */
    private var framesSinceIdr: Int = 0

    /**
     * Encode the framebuffer into one H.264 NAL packet and broadcast
     * to nearby players. Called once per server tick by the host BE.
     *
     * No-op when the framebuffer has odd dimensions (H.264 4:2:0 chroma
     * subsampling requires even width/height) — odd is a misconfiguration
     * worth noticing rather than silently compensating for.
     */
    fun tick(level: ServerLevel, pos: BlockPos, machineUuid: UUID, fb: FramebufferView, clock: MachineClock) {
        val width = fb.width()
        val height = fb.height()
        if ((width and 1) != 0 || (height and 1) != 0) return

        val len = fb.byteSize()
        val pixels = ByteArray(len)
        val src: ByteBuffer = fb.pixels()
        // Defensive: pixels() resets position to 0 on each call; stable length.
        src.get(pixels, 0, minOf(len, src.remaining()))

        // Encode BGRA -> YUV I420 -> H.264 NAL units. Encoder is per-machine,
        // re-created only if the framebuffer dimensions change between frames.
        var enc = encoder
        if (enc == null || encoderWidth != width || encoderHeight != height) {
            enc?.close()
            enc = H264Encoder(width, height, H264Encoder.DEFAULT_BITRATE_BPS, /* fps */ 20)
            encoder = enc
            encoderWidth = width
            encoderHeight = height
            yuvScratch = ByteArray(width * height * 3 / 2)
            // A newly-created encoder naturally emits its first frame as an
            // IDR — reset the counter so we don't redundantly force a second
            // IDR on frame 1.
            framesSinceIdr = 0
        } else if (VideoKeyframeRequests.consume(machineUuid)) {
            // A client asked for a keyframe (late-joiner opening the screen,
            // post-desync recovery) — force IDR immediately so the next
            // emitted frame resyncs their decoder.
            enc.forceIdr()
            framesSinceIdr = 0
        } else if (++framesSinceIdr >= IDR_INTERVAL_FRAMES) {
            // Periodic forced IDR as a safety net for cases the client's
            // explicit request couldn't cover (dropped request packet,
            // decoder state drift, message-order corner cases). The
            // consume() branch above is the hot path; this only fires when
            // no one's asked recently.
            enc.forceIdr()
            framesSinceIdr = 0
        }

        BgraYuv.bgraToI420(pixels, width, height, yuvScratch!!)
        val nal = enc.encode(yuvScratch!!)
        if (nal.isEmpty()) return  // encoder skipped this frame

        // PTS read at capture time, from the same clock the audio path uses.
        // Video frames on the client are presented against the current
        // MediaClock position, which the audio stream drives.
        // DisplayPayload.pixels carries H.264 NAL bytes (not raw BGRA);
        // width/height still describe the decoded frame so the client
        // allocates its DisplayState correctly.
        sendToNearby(level, pos, DisplayPayload.create(
            machineUuid,
            clock.nowPtsMicrosLong(),
            width.toShort(),
            height.toShort(),
            nal,
        ))
    }

    /**
     * Power-off teardown: broadcast a [DisplayDisposePayload] so every
     * watching client clears its cached DisplayState (the BlockEntity
     * renderer + MachineScreen both fall back to black on `null` cache),
     * then close the encoder.
     *
     * Always broadcasts, including in singleplayer. We can't rely on
     * `DisplayManager`'s stale-check eviction — that fires only on the
     * `OPTIMIZE_SINGLEPLAYER` zero-copy path, which is currently
     * disabled, so without the broadcast the client keeps the last
     * frame forever. Loopback cost on the integrated server is one
     * 16-byte UUID and negligible.
     */
    fun dispose(level: ServerLevel, pos: BlockPos, machineUuid: UUID) {
        sendDisposeToNearby(level, pos, DisplayDisposePayload(machineUuid))
        close()
    }

    /**
     * Release the encoder without sending any broadcast. Called from
     * `setRemoved` (chunk unload) — broadcasting a dispose there would be
     * counterproductive: clients re-render once the chunk reloads and the
     * BE comes back, and there's no reason to invalidate their cache in
     * the meantime.
     */
    override fun close() {
        encoder?.close()
        encoder = null
        encoderWidth = -1
        encoderHeight = -1
        yuvScratch = null
    }

    private fun sendToNearby(level: ServerLevel, pos: BlockPos, payload: DisplayPayload) {
        PacketDistributor.sendToPlayersNear(level, null,
            pos.x + 0.5, pos.y + 0.5, pos.z + 0.5,
            DISPLAY_BROADCAST_RADIUS.toDouble(),
            payload)
    }

    private fun sendDisposeToNearby(level: ServerLevel, pos: BlockPos, payload: DisplayDisposePayload) {
        PacketDistributor.sendToPlayersNear(level, null,
            pos.x + 0.5, pos.y + 0.5, pos.z + 0.5,
            DISPLAY_BROADCAST_RADIUS.toDouble(),
            payload)
    }

    companion object {
        /** Periodic forced-IDR cadence — 2 s at 20 Hz. */
        const val IDR_INTERVAL_FRAMES: Int = 40

        /** Radius (blocks) a [DisplayPayload] is broadcast within. */
        const val DISPLAY_BROADCAST_RADIUS: Int = 16
    }
}
