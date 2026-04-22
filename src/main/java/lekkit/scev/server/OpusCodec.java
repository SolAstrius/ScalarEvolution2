/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server;

import org.concentus.OpusApplication;
import org.concentus.OpusDecoder;
import org.concentus.OpusEncoder;
import org.concentus.OpusException;
import org.concentus.OpusSignal;

/**
 * Thin wrapper over the <a href="https://github.com/lostromb/concentus">Concentus</a>
 * pure-Java Opus codec for our fixed-format case: 48 kHz, mono, 20 ms
 * frames (960 samples / 1920 bytes of PCM, encoded to ~250 bytes of
 * Opus at 128 kbps VBR).
 *
 * <p><b>Why Opus:</b> raw 16-bit PCM at 48 kHz mono is 96 KB/s
 * (768 kbps) per listener. With 10 nearby players that's ~8 Mbps of
 * server upload, untenable for consumer broadband. Opus at 128 kbps
 * VBR is 6× smaller (~16 KB/s per listener, ~1.3 Mbps for 10
 * listeners) and is near-transparent on music and indistinguishable
 * from the source on voice.
 *
 * <p><b>Why Concentus:</b> pure-Java port of libopus — no native
 * binary, no platform matrix, no LWJGL module-name dance. Encode is
 * ~2-3× slower than the C reference, which at 50 frames/sec × 20 ms
 * complexity-10 is fractions of a core per active stream. Decode cost
 * is negligible for the same reason. The bit-exact port guarantees
 * interoperability with any standards-compliant Opus tool (e.g.
 * opus-tools on the host).
 *
 * <p><b>Threading:</b> each {@link Encoder} / {@link Decoder} instance
 * must be used from a single thread. The per-machine encoder lives on
 * the server tick thread; the per-machine decoder lives on the client
 * render thread. Both are created lazily and held for the life of the
 * stream.
 */
public final class OpusCodec {
    /** Sample rate Opus is configured for. Must match {@code SoundStreamManager.CLIENT_SAMPLE_RATE_HZ}. */
    public static final int SAMPLE_RATE = 48_000;

    /** 1 channel = mono. */
    public static final int CHANNELS = 1;

    /** 20 ms frames: 48000 × 0.020 = 960 samples. Opus only accepts a fixed set of frame sizes. */
    public static final int FRAME_SAMPLES = 960;

    /** Raw PCM bytes per frame: 960 samples × 2 bytes. Same as {@code SoundStreamManager.FRAME_BYTES}. */
    public static final int PCM_BYTES_PER_FRAME = FRAME_SAMPLES * 2;

    /**
     * Upper bound on encoded packet size. At 128 kbps VBR / 20 ms
     * frames the typical size is ~250 bytes; the codec's hard ceiling
     * for 120 ms of 48 kHz mono content is ~4 KB. We provision 1 KB —
     * well above the typical steady-state and comfortably below any
     * risk of pathological output.
     */
    public static final int MAX_ENCODED_BYTES = 1024;

    /** Encoder bitrate in bits/sec. 128 kbps = near-transparent mono music. */
    public static final int BITRATE_BPS = 128_000;

    private OpusCodec() {}

    /**
     * Encoder bound to a single server-side audio stream. Maintains
     * Opus's internal state (predictor, codebook, etc.) across frames,
     * so frames MUST be fed in order and on one thread.
     */
    public static final class Encoder implements AutoCloseable {
        private final OpusEncoder encoder;
        private final byte[] outBuf = new byte[MAX_ENCODED_BYTES];

        public Encoder() {
            try {
                encoder = new OpusEncoder(SAMPLE_RATE, CHANNELS, OpusApplication.OPUS_APPLICATION_AUDIO);
            } catch (OpusException e) {
                throw new IllegalStateException("opus encoder init failed", e);
            }
            // 128 kbps — doubled from the previous 64 kbps setting for
            // near-transparent music quality. Bandwidth delta per
            // listener is +8 KB/s, negligible.
            encoder.setBitrate(BITRATE_BPS);
            // VBR: borrows bits from quiet frames (silence, steady
            // tones) and spends them on transients. Typical packet
            // size still ~250 B; the hard ceiling is MAX_ENCODED_BYTES.
            encoder.setUseVBR(true);
            // Hint to force CELT (music) mode consistently instead of
            // letting the OPUS_SIGNAL_AUTO detector switch between
            // CELT and SILK mid-stream — mode flips on guest audio are
            // audible as a quick spectral dip.
            encoder.setSignalType(OpusSignal.OPUS_SIGNAL_MUSIC);
            // Max complexity; encoding once per frame on a server
            // tick is cheap even in pure Java.
            encoder.setComplexity(10);
        }

        /**
         * Encode a full PCM frame. Input must be exactly
         * {@link #PCM_BYTES_PER_FRAME} bytes of 16-bit signed LE mono.
         * Returns the Opus-encoded byte array (fresh, caller owns it).
         */
        public byte[] encode(byte[] pcm) {
            if (pcm.length != PCM_BYTES_PER_FRAME) {
                throw new IllegalArgumentException("expected " + PCM_BYTES_PER_FRAME
                        + " bytes, got " + pcm.length);
            }
            int encoded;
            try {
                encoded = encoder.encode(pcm, 0, FRAME_SAMPLES, outBuf, 0, outBuf.length);
            } catch (OpusException e) {
                throw new IllegalStateException("opus encode error", e);
            }
            byte[] result = new byte[encoded];
            System.arraycopy(outBuf, 0, result, 0, encoded);
            return result;
        }

        @Override
        public void close() {
            // Concentus objects are pure-Java — GC handles teardown.
            // close() is kept on the API surface for parity with the
            // AutoCloseable contract and so future migrations back to
            // a native-backed codec don't require call-site churn.
        }
    }

    /**
     * Decoder for one client-side stream. Like the encoder, maintains
     * per-stream Opus state — frames must be decoded in order on one
     * thread.
     */
    public static final class Decoder implements AutoCloseable {
        private final OpusDecoder decoder;
        private final byte[] outBuf = new byte[PCM_BYTES_PER_FRAME];

        public Decoder() {
            try {
                decoder = new OpusDecoder(SAMPLE_RATE, CHANNELS);
            } catch (OpusException e) {
                throw new IllegalStateException("opus decoder init failed", e);
            }
        }

        /**
         * Decode one packet back to a full PCM frame. Returns a fresh
         * byte array of exactly {@link #PCM_BYTES_PER_FRAME} bytes.
         */
        public byte[] decode(byte[] opusPacket) {
            if (opusPacket.length > MAX_ENCODED_BYTES) {
                throw new IllegalArgumentException("packet too large: " + opusPacket.length);
            }
            int samples;
            try {
                samples = decoder.decode(opusPacket, 0, opusPacket.length,
                        outBuf, 0, FRAME_SAMPLES, false);
            } catch (OpusException e) {
                throw new IllegalStateException("opus decode error", e);
            }
            if (samples != FRAME_SAMPLES) {
                // Fixed 20 ms frames: a different sample count means
                // the packet carries a non-matching frame duration.
                throw new IllegalStateException("unexpected decoded sample count: " + samples);
            }
            byte[] pcm = new byte[PCM_BYTES_PER_FRAME];
            System.arraycopy(outBuf, 0, pcm, 0, PCM_BYTES_PER_FRAME);
            return pcm;
        }

        @Override
        public void close() {
            // See Encoder#close.
        }
    }
}
