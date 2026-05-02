/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.core.codec

import lekkit.scev.codec.H264Native

/**
 * H.264 encoder bound to a single [width] × [height] frame size and
 * stream. Construct once, feed I420 frames via [encode], and close
 * when done.
 *
 * @param bitrateBps target bitrate, bits/sec. The native side runs
 *   OpenH264 in `RC_BITRATE_MODE` with `iMaxBitrate = 2 × target` and
 *   frame-skipping disabled, so the encoder will spike over the
 *   target on scene changes (scroll, mode switch) rather than
 *   dropping frames. Size wobble of ±2× on those frames is expected.
 * @param fps frames per second the encoder should pace against.
 *   Also drives `uiIntraPeriod = 2 × fps` (implicit IDR every ~2 s).
 */
class H264Encoder(
    val width: Int,
    val height: Int,
    bitrateBps: Int = DEFAULT_BITRATE_BPS,
    fps: Int = DEFAULT_FPS,
) : AutoCloseable {

    private var handle: Long = H264Native.createEncoder(width, height, bitrateBps, fps)
    private val nalBuffer: ByteArray

    init {
        require(handle != 0L) { "OpenH264 encoder init failed (${width}x${height} @ $bitrateBps bps)" }
        // Worst-case encoded size: the H.264 spec allows up to the
        // compressed equivalent of a raw frame. We provision the raw
        // size as a safe upper bound — an IDR frame on noisy content
        // can exceed the configured bitrate budget for one frame, and
        // we'd rather allocate once than handle truncation.
        nalBuffer = ByteArray(width * height * 3 / 2 + ENCODED_HEADROOM)
    }

    /**
     * Encode one YUV I420 frame. [yuvI420] must be exactly
     * `width * height * 3 / 2` bytes laid out as Y (W×H), U (W/2×H/2),
     * V (W/2×H/2). Returns the emitted H.264 NAL bytes (a fresh array
     * sized to the payload), or an empty array if the encoder
     * produced no output for this frame (rare — e.g. skipped B-frames
     * in some configs).
     */
    fun encode(yuvI420: ByteArray): ByteArray {
        require(yuvI420.size == width * height * 3 / 2) {
            "YUV input size mismatch: expected ${width * height * 3 / 2}, got ${yuvI420.size}"
        }
        check(handle != 0L) { "encoder already closed" }

        val written = H264Native.encodeFrame(handle, yuvI420, width, height, nalBuffer)
        when {
            written >= 0 -> return nalBuffer.copyOf(written)
            written == H264Native.ERR_OUTPUT_TOO_SMALL ->
                error("NAL output buffer too small (${nalBuffer.size} B) for frame encode")
            written == H264Native.ERR_ENCODE ->
                error("OpenH264 encoder returned non-zero status")
            written == H264Native.ERR_NULL_ARG ->
                error("OpenH264 JNI received null argument (logic bug)")
            else -> error("OpenH264 unknown encode error: $written")
        }
    }

    /**
     * Ask the encoder to emit the next frame as an IDR (carrying SPS +
     * PPS + I-slice). Call before [encode] when a late-joining client
     * is expected — without an IDR, that client's decoder can't
     * produce anything until the encoder's scheduled IDR interval
     * comes up (~100 frames by default, which is 5 s at 20 Hz).
     */
    fun forceIdr() {
        check(handle != 0L) { "encoder already closed" }
        H264Native.forceIntraFrame(handle)
    }

    override fun close() {
        if (handle != 0L) {
            H264Native.destroyEncoder(handle)
            handle = 0L
        }
    }

    companion object {
        /**
         * 4 Mbps default target. Screen content (terminals, editors)
         * is harder to compress than equivalent-resolution camera
         * video — lots of sharp edges + sudden full-frame changes on
         * scroll — so the old 1 Mbps target bled through as visible
         * compression artifacts and rate-controller frame drops. With
         * `iMaxBitrate = 2 ×` on the native side this peaks at 8 Mbps
         * on scroll bursts, which is fine over LAN Minecraft.
         */
        const val DEFAULT_BITRATE_BPS: Int = 4_000_000
        const val DEFAULT_FPS: Int = 30
        /** Safety pad on top of raw-frame-size for the encoded output buffer. */
        private const val ENCODED_HEADROOM: Int = 64 * 1024
    }
}

/* ----------------------------------------------------------------- */
/* Decoder                                                           */
/* ----------------------------------------------------------------- */

/**
 * H.264 decoder for a single stream. Unlike [H264Encoder] it has no
 * fixed frame size up front — the dimensions come out of the first
 * IDR packet and are reported in [DecodedFrame.width] / [height].
 *
 * The decoder is stateful; NAL access units must be fed in order.
 * Feeding SPS/PPS without a subsequent slice will return
 * [DecodedFrame.NONE] (no frame produced yet).
 */
class H264Decoder : AutoCloseable {

    private var handle: Long = H264Native.createDecoder()

    /**
     * Output buffer sized for a 1920×1080 I420 frame. Larger input
     * streams force a realloc on first arrival in [decode]; smaller
     * streams just use the leading portion. Tune if your workload has
     * a stable larger resolution.
     */
    private var yuvBuffer: ByteArray = ByteArray(1920 * 1080 * 3 / 2)

    /**
     * Filled with `[width, height, yStride, cStride]` on each
     * successful decode. Kept as a field to avoid re-allocating per
     * frame.
     */
    private val dims = IntArray(4)

    init {
        require(handle != 0L) { "OpenH264 decoder init failed" }
    }

    /**
     * Decode one access unit. Returns a fresh [DecodedFrame] with a
     * copy of the decoded YUV bytes on success, or `null` if the NAL
     * didn't produce a complete frame (SPS/PPS only, lost reference,
     * etc.). Failure modes (corrupt stream, decoder error) throw.
     */
    fun decode(nal: ByteArray): DecodedFrame? {
        check(handle != 0L) { "decoder already closed" }

        var ret = H264Native.decodeFrame(handle, nal, nal.size, yuvBuffer, dims)
        if (ret == H264Native.ERR_OUTPUT_TOO_SMALL) {
            // Grow. OpenH264 told us nothing about the target size, so
            // double until we fit. In practice this is a one-time cost
            // on the first frame for any realistic resolution.
            yuvBuffer = ByteArray(yuvBuffer.size * 2)
            ret = H264Native.decodeFrame(handle, nal, nal.size, yuvBuffer, dims)
        }
        return when {
            ret > 0 -> DecodedFrame(yuvBuffer.copyOf(ret), dims[0], dims[1])
            ret == H264Native.ERR_NO_FRAME -> null
            ret == H264Native.ERR_DECODE -> error("OpenH264 decoder reported dsError* status")
            ret == H264Native.ERR_NULL_ARG -> error("OpenH264 JNI received null argument (logic bug)")
            ret == H264Native.ERR_OUTPUT_TOO_SMALL -> error("YUV output buffer too small after grow")
            else -> error("OpenH264 unknown decode error: $ret")
        }
    }

    override fun close() {
        if (handle != 0L) {
            H264Native.destroyDecoder(handle)
            handle = 0L
        }
    }

    /**
     * A decoded I420 frame. [yuv] is a self-contained copy — the
     * decoder's internal buffer has already been advanced past these
     * bytes by the time you receive this.
     */
    data class DecodedFrame(
        val yuv: ByteArray,
        val width: Int,
        val height: Int,
    )
}
