/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.core.codec

/**
 * Color-space converters between Minecraft's BGRA framebuffer bytes and
 * H.264's expected YUV I420 planar layout. BT.601 limited range
 * (Y: 16-235, UV: 16-240) — what OpenH264's encoder assumes by default.
 *
 * **Format recap.**
 * - **BGRA**: interleaved 4 bytes/pixel, memory order B G R A. This is
 *   what RVVM's framebuffer DMA buffer exposes — little-endian
 *   `A8R8G8B8` written with native byte order, which lands as B G R A
 *   in memory.
 * - **YUV I420**: planar, 12 bpp average. Y at full W×H, then U and V
 *   each at (W/2)×(H/2) — 4:2:0 chroma subsampling.
 *
 * Even [width] and [height] are required. H.264's block structure
 * doesn't accommodate odd dimensions gracefully; callers should pad
 * or round before feeding frames in.
 *
 * These are mechanical pixel loops, not SIMD-optimized. For 640×480 @
 * 30 fps that's ~9 Mpix/s, well within one core's budget on the JVM.
 * Revisit with a native conversion path or JNI intrinsic if
 * conversion lands on a profile.
 */
object BgraYuv {

    /**
     * Convert [width] × [height] BGRA bytes into YUV I420, writing into
     * [outYuv]. [outYuv] must have capacity `width * height * 3 / 2`
     * (Y + U + V concatenated).
     *
     * Chroma is sub-sampled by **averaging** the 4 pixels of each 2×2
     * block. An earlier version picked the top-left pixel instead
     * ("half the work, indistinguishable on UI content") — that claim
     * was wrong for sharp glyph edges. Example failure: a green stroke
     * whose top-left pixel happened to be the black background made
     * every decoded pixel in that 2×2 block take a neutral (gray)
     * chroma, so green glyph pixels decoded as gray. Averaging avoids
     * that by spreading the 4-pixel contribution across the block's
     * single chroma sample.
     */
    @JvmStatic
    fun bgraToI420(bgra: ByteArray, width: Int, height: Int, outYuv: ByteArray) {
        require(width % 2 == 0 && height % 2 == 0) { "dimensions must be even: ${width}x${height}" }
        require(bgra.size >= width * height * 4) { "BGRA too small: ${bgra.size} for ${width}x${height}" }
        require(outYuv.size >= width * height * 3 / 2) { "YUV out too small: ${outYuv.size}" }

        val ySize = width * height
        val cSize = (width / 2) * (height / 2)
        val uOff = ySize
        val vOff = ySize + cSize
        val cStride = width / 2

        // Y plane: every pixel.
        for (row in 0 until height) {
            val rowOff = row * width
            for (col in 0 until width) {
                val p = (rowOff + col) * 4
                val b = bgra[p].toInt() and 0xFF
                val g = bgra[p + 1].toInt() and 0xFF
                val r = bgra[p + 2].toInt() and 0xFF
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                outYuv[rowOff + col] = y.coerceIn(0, 255).toByte()
            }
        }

        // U and V planes: box-average the 4 pixels of each 2×2 block.
        // The BT.601 transform is linear so averaging RGB then
        // transforming = transforming then averaging, modulo rounding.
        // We average RGB first to keep the inner multiplies out of the
        // hot loop's per-pixel path.
        for (cRow in 0 until height / 2) {
            val row0 = 2 * cRow
            val row1 = row0 + 1
            for (cCol in 0 until width / 2) {
                val col0 = 2 * cCol
                val col1 = col0 + 1
                val p00 = (row0 * width + col0) * 4
                val p01 = (row0 * width + col1) * 4
                val p10 = (row1 * width + col0) * 4
                val p11 = (row1 * width + col1) * 4
                val b = ((bgra[p00].toInt() and 0xFF) + (bgra[p01].toInt() and 0xFF) +
                         (bgra[p10].toInt() and 0xFF) + (bgra[p11].toInt() and 0xFF) + 2) shr 2
                val g = ((bgra[p00 + 1].toInt() and 0xFF) + (bgra[p01 + 1].toInt() and 0xFF) +
                         (bgra[p10 + 1].toInt() and 0xFF) + (bgra[p11 + 1].toInt() and 0xFF) + 2) shr 2
                val r = ((bgra[p00 + 2].toInt() and 0xFF) + (bgra[p01 + 2].toInt() and 0xFF) +
                         (bgra[p10 + 2].toInt() and 0xFF) + (bgra[p11 + 2].toInt() and 0xFF) + 2) shr 2
                val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                val cIdx = cRow * cStride + cCol
                outYuv[uOff + cIdx] = u.coerceIn(0, 255).toByte()
                outYuv[vOff + cIdx] = v.coerceIn(0, 255).toByte()
            }
        }
    }

    /**
     * Convert [width] × [height] YUV I420 bytes into BGRA (A = 0xFF),
     * writing into [outBgra]. BGRA byte order matches what the rest
     * of the ScalarEvolution client-side display pipeline expects —
     * `DisplayState.remoteBuffer` is interpreted as BGRA by
     * `PixelConverter.bgraToRgba` before upload to `NativeImage`.
     *
     * U/V are sampled nearest-neighbour from their 2×2 block (no
     * bilinear upsample). Good enough for video-on-a-monitor-block
     * quality.
     */
    @JvmStatic
    fun i420ToBgra(yuv: ByteArray, width: Int, height: Int, outBgra: ByteArray) {
        i420ToColorInterleaved(yuv, width, height, outBgra, rFirst = false)
    }

    /**
     * Convert [width] × [height] YUV I420 bytes into RGBA (A = 0xFF),
     * writing into [outRgba]. Useful for callers that already have
     * the bytes in the same order `NativeImage(RGBA)` stores.
     *
     * U/V are sampled nearest-neighbour from their 2×2 block (no
     * bilinear upsample). Good enough for video-on-a-monitor-block
     * quality.
     */
    @JvmStatic
    fun i420ToRgba(yuv: ByteArray, width: Int, height: Int, outRgba: ByteArray) {
        i420ToColorInterleaved(yuv, width, height, outRgba, rFirst = true)
    }

    private fun i420ToColorInterleaved(
        yuv: ByteArray,
        width: Int,
        height: Int,
        out: ByteArray,
        rFirst: Boolean,
    ) {
        require(width % 2 == 0 && height % 2 == 0) { "dimensions must be even: ${width}x${height}" }
        require(yuv.size >= width * height * 3 / 2) { "YUV too small: ${yuv.size}" }
        require(out.size >= width * height * 4) { "color out too small: ${out.size}" }

        val ySize = width * height
        val cSize = (width / 2) * (height / 2)
        val uOff = ySize
        val vOff = ySize + cSize
        val cStride = width / 2

        val r0 = if (rFirst) 0 else 2
        val b0 = if (rFirst) 2 else 0

        for (row in 0 until height) {
            val cRow = row / 2
            val rowOff = row * width
            for (col in 0 until width) {
                val y = yuv[rowOff + col].toInt() and 0xFF
                val cIdx = cRow * cStride + (col / 2)
                val u = yuv[uOff + cIdx].toInt() and 0xFF
                val v = yuv[vOff + cIdx].toInt() and 0xFF

                // BT.601 limited-range → 8-bit RGB.
                val c = y - 16
                val d = u - 128
                val e = v - 128
                val r = ((298 * c + 409 * e + 128) shr 8).coerceIn(0, 255)
                val g = ((298 * c - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255)
                val b = ((298 * c + 516 * d + 128) shr 8).coerceIn(0, 255)

                val p = (rowOff + col) * 4
                out[p + r0] = r.toByte()
                out[p + 1]  = g.toByte()
                out[p + b0] = b.toByte()
                out[p + 3]  = 0xFF.toByte()
            }
        }
    }
}
