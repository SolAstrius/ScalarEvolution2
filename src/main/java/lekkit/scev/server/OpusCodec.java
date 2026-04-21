/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.util.opus.Opus;

/**
 * Thin wrapper over LWJGL's libopus bindings for our fixed-format case:
 * 48 kHz, mono, 20 ms frames (960 samples / 1920 bytes of PCM, encoded
 * to ~160 bytes of Opus at 64 kbps).
 *
 * <p><b>Why Opus:</b> raw 16-bit PCM at 48 kHz mono is 96 KB/s
 * (768 kbps) per listener. With 10 nearby players that's ~8 Mbps of
 * server upload, too heavy for consumer broadband. Opus at 64 kbps is
 * 12× smaller (8 KB/s per listener, ~1 Mbps for 10 listeners) and
 * sounds indistinguishable from the source on a casual listen.
 *
 * <p><b>Threading:</b> Each {@link Encoder}/{@link Decoder} instance
 * must be used from a single thread. The per-machine encoder lives on
 * the server tick thread; the per-machine decoder lives on the client
 * render thread. Both are created lazily and closed explicitly.
 */
public final class OpusCodec {
    /** Sample rate Opus is configured for. Must match SoundStreamManager.CLIENT_SAMPLE_RATE_HZ. */
    public static final int SAMPLE_RATE = 48_000;

    /** 1 channel = mono. */
    public static final int CHANNELS = 1;

    /** 20 ms frames: 48000 × 0.020 = 960 samples. Opus only accepts a fixed set of frame sizes. */
    public static final int FRAME_SAMPLES = 960;

    /** Raw PCM bytes per frame: 960 samples × 2 bytes. Same as SoundStreamManager.FRAME_BYTES. */
    public static final int PCM_BYTES_PER_FRAME = FRAME_SAMPLES * 2;

    /**
     * Upper bound on encoded packet size. Opus docs guarantee a frame
     * encoded at bitrate B for duration D has at most B·D/8 bytes, plus
     * a small per-packet overhead. At 64 kbps / 20 ms that's ~160 bytes;
     * we round up for safety — under-allocation here would silently
     * truncate the packet.
     */
    public static final int MAX_ENCODED_BYTES = 1024;

    /** Encoder bitrate in bits/sec. 64 kbps = high-quality mono music. */
    public static final int BITRATE_BPS = 64_000;

    private OpusCodec() {}

    /**
     * Encoder bound to a single server-side audio stream. Maintains
     * Opus's internal state (predictor, codebook, etc.) across frames,
     * so frames MUST be fed in order and on one thread.
     */
    public static final class Encoder implements AutoCloseable {
        private long handle;
        private final ShortBuffer pcmBuf = BufferUtils.createShortBuffer(FRAME_SAMPLES);
        private final ByteBuffer outBuf  = BufferUtils.createByteBuffer(MAX_ENCODED_BYTES);

        public Encoder() {
            IntBuffer err = BufferUtils.createIntBuffer(1);
            this.handle = Opus.opus_encoder_create(SAMPLE_RATE, CHANNELS,
                    Opus.OPUS_APPLICATION_AUDIO, err);
            if (err.get(0) != Opus.OPUS_OK || handle == 0) {
                throw new IllegalStateException("opus_encoder_create failed: " + err.get(0));
            }
            // Tune for music: 64 kbps, constant bitrate (CBR) so packet
            // size is predictable, complexity 10 (max) since encoding
            // once per frame on a server tick is cheap.
            Opus.opus_encoder_ctl(handle, Opus.OPUS_SET_BITRATE(BITRATE_BPS));
            Opus.opus_encoder_ctl(handle, Opus.OPUS_SET_VBR(0));
            Opus.opus_encoder_ctl(handle, Opus.OPUS_SET_COMPLEXITY(10));
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
            pcmBuf.clear();
            // Convert LE byte[] to short[] for Opus
            ByteBuffer src = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < FRAME_SAMPLES; i++) {
                pcmBuf.put(src.getShort());
            }
            pcmBuf.flip();
            outBuf.clear();
            int encoded = Opus.opus_encode(handle, pcmBuf, FRAME_SAMPLES, outBuf);
            if (encoded < 0) {
                throw new IllegalStateException("opus_encode error: " + encoded);
            }
            byte[] result = new byte[encoded];
            outBuf.rewind();
            outBuf.get(result, 0, encoded);
            return result;
        }

        @Override
        public void close() {
            if (handle != 0) {
                Opus.opus_encoder_destroy(handle);
                handle = 0;
            }
        }
    }

    /**
     * Decoder for one client-side stream. Like the encoder, maintains
     * per-stream Opus state — frames must be decoded in order on one
     * thread.
     */
    public static final class Decoder implements AutoCloseable {
        private long handle;
        private final ByteBuffer  pktBuf = BufferUtils.createByteBuffer(MAX_ENCODED_BYTES);
        private final ShortBuffer pcmBuf = BufferUtils.createShortBuffer(FRAME_SAMPLES);

        public Decoder() {
            IntBuffer err = BufferUtils.createIntBuffer(1);
            this.handle = Opus.opus_decoder_create(SAMPLE_RATE, CHANNELS, err);
            if (err.get(0) != Opus.OPUS_OK || handle == 0) {
                throw new IllegalStateException("opus_decoder_create failed: " + err.get(0));
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
            pktBuf.clear();
            pktBuf.put(opusPacket).flip();
            pcmBuf.clear();
            int samples = Opus.opus_decode(handle, pktBuf, pcmBuf, FRAME_SAMPLES, 0);
            if (samples < 0) {
                throw new IllegalStateException("opus_decode error: " + samples);
            }
            if (samples != FRAME_SAMPLES) {
                // Could happen if packet was a different frame duration.
                // For our fixed 20 ms setup this shouldn't occur, but
                // guard against it.
                throw new IllegalStateException("unexpected decoded sample count: " + samples);
            }
            byte[] pcm = new byte[PCM_BYTES_PER_FRAME];
            ByteBuffer dst = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
            pcmBuf.position(0);
            for (int i = 0; i < FRAME_SAMPLES; i++) {
                dst.putShort(pcmBuf.get());
            }
            return pcm;
        }

        @Override
        public void close() {
            if (handle != 0) {
                Opus.opus_decoder_destroy(handle);
                handle = 0;
            }
        }
    }
}
