/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server

import org.concentus.OpusApplication
import org.concentus.OpusDecoder
import org.concentus.OpusEncoder
import org.concentus.OpusException
import org.concentus.OpusSignal

/**
 * Thin wrapper over the [Concentus](https://github.com/lostromb/concentus)
 * pure-Java Opus codec for our fixed-format case: 48 kHz, mono, 20 ms frames
 * (960 samples / 1920 bytes of PCM, encoded to ~250 bytes of Opus at 128 kbps
 * VBR).
 *
 * **Why Opus.** Raw 16-bit PCM at 48 kHz mono is 96 KB/s (768 kbps) per
 * listener. Ten nearby players → ~8 Mbps server upload, untenable for
 * consumer broadband. Opus at 128 kbps VBR is 6× smaller (~16 KB/s per
 * listener, ~1.3 Mbps for 10) and is near-transparent on music and
 * indistinguishable from the source on voice.
 *
 * **Why Concentus.** Pure-Java port of libopus — no native binary, no
 * platform matrix, no LWJGL module-name dance. Encode is ~2-3× slower than
 * the C reference, which at 50 frames/sec × 20 ms complexity-10 is fractions
 * of a core per active stream. Decode cost is negligible. The bit-exact
 * port guarantees interop with any standards-compliant Opus tool.
 *
 * **Threading.** Each [Encoder] / [Decoder] instance must be used from a
 * single thread. The per-machine encoder lives on the server tick thread;
 * the per-machine decoder lives on the client render thread. Both are
 * created lazily and held for the life of the stream.
 */
object OpusCodec {
    /** Sample rate Opus is configured for. Must match `SoundStreamManager.CLIENT_SAMPLE_RATE_HZ`. */
    const val SAMPLE_RATE: Int = 48_000

    /** 1 channel = mono. */
    const val CHANNELS: Int = 1

    /** 20 ms frames: 48000 × 0.020 = 960 samples. Opus only accepts a fixed set of frame sizes. */
    const val FRAME_SAMPLES: Int = 960

    /** Raw PCM bytes per frame: 960 samples × 2 bytes. */
    const val PCM_BYTES_PER_FRAME: Int = FRAME_SAMPLES * 2

    /**
     * Upper bound on encoded packet size. At 128 kbps VBR / 20 ms the typical
     * is ~250 B; the codec's hard ceiling for 120 ms of 48 kHz mono is ~4 KB.
     * 1 KB is well above steady-state and below any pathological case.
     */
    const val MAX_ENCODED_BYTES: Int = 1024

    /** Encoder bitrate in bits/sec. 128 kbps = near-transparent mono music. */
    const val BITRATE_BPS: Int = 128_000

    /**
     * Encoder bound to a single server-side audio stream. Maintains Opus's
     * internal state (predictor, codebook, etc.) across frames, so frames
     * MUST be fed in order and on one thread.
     */
    class Encoder : AutoCloseable {
        private val encoder: OpusEncoder = try {
            OpusEncoder(SAMPLE_RATE, CHANNELS, OpusApplication.OPUS_APPLICATION_AUDIO)
        } catch (e: OpusException) {
            throw IllegalStateException("opus encoder init failed", e)
        }
        private val outBuf = ByteArray(MAX_ENCODED_BYTES)

        init {
            // 128 kbps — near-transparent music quality. Bandwidth delta per
            // listener is +8 KB/s, negligible.
            encoder.bitrate = BITRATE_BPS
            // VBR: borrows bits from quiet frames (silence, steady tones)
            // and spends them on transients.
            encoder.useVBR = true
            // Force CELT (music) mode consistently instead of letting
            // OPUS_SIGNAL_AUTO switch between CELT and SILK mid-stream —
            // mode flips on guest audio are audible as a spectral dip.
            encoder.signalType = OpusSignal.OPUS_SIGNAL_MUSIC
            // Max complexity; encoding once per server tick is cheap even
            // in pure Java.
            encoder.complexity = 10
        }

        /**
         * Encode a full PCM frame. Input must be exactly [PCM_BYTES_PER_FRAME]
         * bytes of 16-bit signed LE mono. Returns the Opus-encoded byte
         * array (fresh, caller owns it).
         */
        fun encode(pcm: ByteArray): ByteArray {
            require(pcm.size == PCM_BYTES_PER_FRAME) {
                "expected $PCM_BYTES_PER_FRAME bytes, got ${pcm.size}"
            }
            val encoded = try {
                encoder.encode(pcm, 0, FRAME_SAMPLES, outBuf, 0, outBuf.size)
            } catch (e: OpusException) {
                throw IllegalStateException("opus encode error", e)
            }
            return outBuf.copyOfRange(0, encoded)
        }

        // Concentus objects are pure-Java — GC handles teardown. close() is
        // kept on the API surface for parity with the AutoCloseable contract
        // and so a future migration back to a native-backed codec doesn't
        // require call-site churn.
        override fun close() {}
    }

    /**
     * Decoder for one client-side stream. Maintains per-stream Opus state —
     * frames must be decoded in order on one thread.
     */
    class Decoder : AutoCloseable {
        private val decoder: OpusDecoder = try {
            OpusDecoder(SAMPLE_RATE, CHANNELS)
        } catch (e: OpusException) {
            throw IllegalStateException("opus decoder init failed", e)
        }
        private val outBuf = ByteArray(PCM_BYTES_PER_FRAME)

        /** Decode one packet back to a full PCM frame. Returns a fresh byte array. */
        fun decode(opusPacket: ByteArray): ByteArray {
            require(opusPacket.size <= MAX_ENCODED_BYTES) { "packet too large: ${opusPacket.size}" }
            val samples = try {
                decoder.decode(opusPacket, 0, opusPacket.size, outBuf, 0, FRAME_SAMPLES, false)
            } catch (e: OpusException) {
                throw IllegalStateException("opus decode error", e)
            }
            // Fixed 20 ms frames: a different sample count means the packet
            // carries a non-matching frame duration.
            check(samples == FRAME_SAMPLES) { "unexpected decoded sample count: $samples" }
            return outBuf.copyOfRange(0, PCM_BYTES_PER_FRAME)
        }

        override fun close() {} // see Encoder.close
    }
}
