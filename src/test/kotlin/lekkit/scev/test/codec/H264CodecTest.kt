/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.codec

import kotlin.math.abs
import lekkit.scev.core.codec.H264Decoder
import lekkit.scev.core.codec.H264Encoder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * MVP end-to-end test for the OpenH264-backed codec.
 *
 * - Generates synthetic YUV I420 frames with known content.
 * - Pushes them through [H264Encoder] at 320×240, 30 fps, 500 kbps.
 * - Feeds the emitted NAL units back through [H264Decoder].
 * - Asserts: every frame decodes, dimensions round-trip, and pixel
 *   content is within a tolerance consistent with lossy H.264
 *   encoding (mean absolute Y error < 8 on an 8-bit Y channel, ~3%).
 *
 * Content pattern: a horizontal Y gradient that shifts each frame,
 * with flat U/V at neutral (128). The gradient is predictable enough
 * to compare pixel-for-pixel; the frame-shift forces the encoder to
 * emit real motion (not a still that could be encoded as a single
 * IDR and reused).
 */
class H264CodecTest {

    companion object {
        private const val WIDTH = 320
        private const val HEIGHT = 240
        private const val FRAMES = 10

        /**
         * Make frame N: Y plane is a horizontal gradient offset by
         * (N × 4), i.e. the pattern scrolls 4 pixels each frame. U/V
         * are flat 128 (neutral chroma). Deterministic → reproducible
         * test.
         */
        private fun makeFrame(index: Int): ByteArray {
            val ySize = WIDTH * HEIGHT
            val cSize = (WIDTH / 2) * (HEIGHT / 2)
            val frame = ByteArray(ySize + 2 * cSize)
            for (row in 0 until HEIGHT) {
                val rowBase = row * WIDTH
                for (col in 0 until WIDTH) {
                    frame[rowBase + col] = ((col + index * 4) and 0xFF).toByte()
                }
            }
            for (i in 0 until 2 * cSize) {
                frame[ySize + i] = 128.toByte()
            }
            return frame
        }

        /** Mean absolute difference across the Y plane of two I420 frames. */
        private fun meanYError(a: ByteArray, b: ByteArray, w: Int, h: Int): Double {
            val n = w * h
            var sum = 0L
            for (i in 0 until n) {
                sum += abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF))
            }
            return sum.toDouble() / n
        }
    }

    @Test
    @DisplayName("Encoder/decoder round-trip: 10 synthetic YUV frames decode back with low error")
    fun encodeDecodeRoundtrip() {
        val originals = (0 until FRAMES).map { makeFrame(it) }

        val encoded: List<ByteArray>
        H264Encoder(WIDTH, HEIGHT, bitrateBps = 500_000, fps = 30).use { encoder ->
            encoded = originals.map { encoder.encode(it) }
        }

        // Every frame should have emitted *some* bytes. (OpenH264 in
        // this configuration never skips frames; a zero-byte emit
        // would be a config bug worth catching.)
        encoded.forEachIndexed { i, nal ->
            assertTrue(nal.isNotEmpty(), "frame $i encoded to zero bytes")
        }
        // First frame is an IDR — it carries SPS/PPS, so it's
        // typically the largest. Cheap sanity check.
        assertTrue(encoded[0].size > encoded.drop(1).maxOf { it.size } / 2,
            "frame 0 unexpectedly small (${encoded[0].size} B); expected it to be an IDR")

        val decoded: List<H264Decoder.DecodedFrame?>
        H264Decoder().use { decoder ->
            decoded = encoded.map { decoder.decode(it) }
        }

        // All frames should have produced output. OpenH264's
        // no-delay decoder emits one frame per access unit provided
        // the slice is non-empty.
        decoded.forEachIndexed { i, d ->
            assertNotNull(d, "frame $i failed to decode")
        }

        decoded.forEachIndexed { i, frame ->
            assertEquals(WIDTH,  frame!!.width,  "frame $i width")
            assertEquals(HEIGHT, frame.height, "frame $i height")

            val err = meanYError(originals[i], frame.yuv, WIDTH, HEIGHT)
            assertTrue(err < 8.0,
                "frame $i mean Y error $err exceeds 8.0 (H.264 too lossy or encode/decode desync)")
        }
    }

    @Test
    @DisplayName("Encoder emits a larger IDR followed by smaller P-frames on a flat-motion stream")
    fun idrSizeSanity() {
        // Send 5 identical frames. The first is IDR (carries SPS/PPS
        // + full I-slice); subsequent are P-frames referencing the
        // IDR. On a perfectly static sequence OpenH264 encodes the P
        // frames as near-pure skip-block motion vectors, so they
        // don't shrink dramatically below the IDR — but the IDR is
        // still the largest frame in the group, which is the
        // invariant worth asserting (wouldn't be true if e.g. the
        // encoder mistakenly inserted an IDR every frame).
        val frame = makeFrame(0)
        H264Encoder(WIDTH, HEIGHT, bitrateBps = 500_000, fps = 30).use { encoder ->
            val sizes = (0 until 5).map { encoder.encode(frame).size }
            val maxP = sizes.drop(1).max()
            assertTrue(sizes[0] > maxP,
                "expected IDR larger than every P-frame; sizes=$sizes")
        }
    }

    @Test
    @DisplayName("Decoder roundtrips a different resolution (176×144) without config churn")
    fun smallResolutionRoundtrip() {
        // Exercise encoder state on a non-square non-320×240 input so
        // a hardcoded assumption (e.g. forgetting to pass height to
        // the JNI) surfaces as a garbled decode rather than a false
        // green on the main test.
        val w = 176
        val h = 144
        val ySize = w * h
        val cSize = (w / 2) * (h / 2)
        val frame = ByteArray(ySize + 2 * cSize).also {
            for (i in 0 until ySize) it[i] = (i and 0xFF).toByte()
            for (i in 0 until 2 * cSize) it[ySize + i] = 128.toByte()
        }

        val encoded = H264Encoder(w, h, bitrateBps = 200_000, fps = 30).use { encoder ->
            encoder.encode(frame)
        }
        val decoded = H264Decoder().use { decoder ->
            decoder.decode(encoded)
        }
        assertNotNull(decoded, "176×144 frame failed to decode")
        assertEquals(w, decoded!!.width)
        assertEquals(h, decoded.height)
    }
}
