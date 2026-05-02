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
