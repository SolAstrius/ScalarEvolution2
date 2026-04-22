/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.codec

import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * JVM surface over the OpenH264-backed `libscev_h264` JNI library. Two
 * stateful per-stream objects:
 *
 * - [H264Encoder]: YUV I420 frame → concatenated H.264 NAL units. The
 *   first emitted frame is an IDR carrying SPS/PPS; subsequent frames
 *   are P (or periodic IDR per the encoder's internal schedule).
 * - [H264Decoder]: one access-unit worth of NAL bytes → YUV I420 frame.
 *
 * Threading is single-threaded per instance — OpenH264's encoder /
 * decoder state isn't thread-safe. Each [H264Encoder] / [H264Decoder]
 * should be bound to one logical stream and used from one consumer.
 *
 * Lifecycle is explicit via [AutoCloseable.close]; native resources
 * are not GC-rooted. A leaked instance leaks OpenH264 state and its
 * internal reference buffers — not fatal, but measurable under long
 * runs.
 */

/* ----------------------------------------------------------------- */
/* Native surface                                                    */
/* ----------------------------------------------------------------- */

/**
 * Direct `external fun` mirror of the JNI entry points in
 * `native/openh264-jni/scev_h264.c`. The JNI symbols match the
 * `Java_lekkit_scev_codec_H264Native_<name>` convention that
 * `@JvmStatic` on an `object` generates — do not rename or repackage
 * without regenerating the C side.
 *
 * Error codes used by the `encodeFrame` / `decodeFrame` return values
 * are duplicated from the C source for callers that want to branch
 * on specific failure modes (e.g. late-frame drop handling).
 */
object H264Native {
    const val ERR_INIT = -1
    const val ERR_ENCODE = -2
    const val ERR_OUTPUT_TOO_SMALL = -3
    const val ERR_DECODE = -4
    const val ERR_NO_FRAME = -5
    const val ERR_NULL_ARG = -6

    @JvmStatic external fun createEncoder(width: Int, height: Int, bitrate: Int, fps: Int): Long
    @JvmStatic external fun encodeFrame(handle: Long, yuvIn: ByteArray, width: Int, height: Int, nalOut: ByteArray): Int
    @JvmStatic external fun destroyEncoder(handle: Long)
    @JvmStatic external fun forceIntraFrame(handle: Long): Int

    @JvmStatic external fun createDecoder(): Long
    @JvmStatic external fun decodeFrame(handle: Long, nalIn: ByteArray, nalLen: Int, yuvOut: ByteArray, outDims: IntArray): Int
    @JvmStatic external fun destroyDecoder(handle: Long)

    init {
        loadBundledLibrary()
    }

    /**
     * Resolve `natives/<classifier>/libscev_h264.<ext>` from the mod
     * jar, drop it into a temp file, and `System.load` it. Same
     * mechanism as `NativeLoader` uses for librvvm, but kept inline
     * here so this class has no dependency on the server-side loader
     * (e.g. for client-only decode paths).
     */
    private fun loadBundledLibrary() {
        val classifier = detectClassifier()
            ?: error("libscev_h264: unsupported OS/arch (no native bundled)")
        val libName = System.mapLibraryName("scev_h264")
        val resourcePath = "/natives/$classifier/$libName"

        val input = H264Native::class.java.getResourceAsStream(resourcePath)
            ?: error("libscev_h264: no bundled native at $resourcePath")

        input.use { stream ->
            val tempDir = Files.createTempDirectory("scev-h264-")
            val tempLib = tempDir.resolve(libName)
            Files.copy(stream, tempLib, StandardCopyOption.REPLACE_EXISTING)
            tempLib.toFile().deleteOnExit()
            System.load(tempLib.toAbsolutePath().toString())
        }
    }

    private fun detectClassifier(): String? {
        val osn = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val normArch = when {
            arch == "amd64" || arch == "x86_64" -> "x86_64"
            arch == "aarch64" || arch == "arm64" -> "aarch64"
            else -> return null
        }
        return when {
            osn.contains("linux")   -> "linux-$normArch"
            osn.contains("mac")     -> "macos-$normArch"
            osn.contains("windows") -> "windows-$normArch"
            else -> null
        }
    }
}

/* ----------------------------------------------------------------- */
/* Encoder                                                           */
/* ----------------------------------------------------------------- */

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
