/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.rpc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Stateful reader that consumes arbitrary byte chunks and emits complete
 * COBS-decoded frames.
 *
 * <p>Usage:
 *
 * <pre>
 *   FrameStream s = new FrameStream(4096);
 *   byte[] chunk = serial.pollTx();      // may contain 0, 1, many, or partial frames
 *   for (byte[] plain : s.feed(chunk, 0, chunk.length)) {
 *       handle(plain);
 *   }
 * </pre>
 *
 * <p>Accumulates bytes up to the next {@code 0x00} delimiter, decodes the
 * COBS block, hands back the plaintext. Bad frames (COBS decode failure,
 * too-large accumulation) reset the accumulator and return nothing —
 * the sync byte at the next delimiter resumes clean parsing.
 *
 * <p>{@code maxFrameBytes} caps per-frame encoded size. Anything larger
 * is a protocol violation and is dropped silently; the caller can notice
 * via {@link #droppedFrames()}.
 */
public final class FrameStream {
    private final byte[] acc;
    private int accLen;
    private final int maxFrameBytes;
    private long droppedFrames;

    public FrameStream(int maxFrameBytes) {
        this.maxFrameBytes = maxFrameBytes;
        this.acc = new byte[maxFrameBytes];
    }

    public long droppedFrames() { return droppedFrames; }

    /**
     * Feed bytes; return any completed plaintext frames. Empty list when
     * the bytes didn't complete any frame.
     */
    public List<byte[]> feed(byte[] buf, int off, int len) {
        List<byte[]> out = null;
        int end = off + len;
        int i = off;
        while (i < end) {
            // Find the next delimiter.
            int zero = -1;
            for (int j = i; j < end; j++) {
                if (buf[j] == 0) { zero = j; break; }
            }
            if (zero < 0) {
                // No delimiter — accumulate rest and wait for more.
                int take = end - i;
                if (accLen + take > maxFrameBytes) {
                    droppedFrames++;
                    accLen = 0;           // discard; resume on next delimiter
                    return out == null ? List.of() : out;
                }
                System.arraycopy(buf, i, acc, accLen, take);
                accLen += take;
                break;
            }
            // We have a complete COBS block in acc[0..accLen] + buf[i..zero).
            int tail = zero - i;
            if (accLen + tail > maxFrameBytes) {
                droppedFrames++;
            } else if (accLen + tail > 0) {
                System.arraycopy(buf, i, acc, accLen, tail);
                int encodedLen = accLen + tail;
                byte[] plain = new byte[encodedLen];    // decoded ≤ encoded
                int n = Cobs.decode(acc, 0, encodedLen, plain, 0);
                if (n < 0) {
                    droppedFrames++;
                } else {
                    byte[] trimmed = (n == plain.length) ? plain : Arrays.copyOf(plain, n);
                    if (out == null) out = new ArrayList<>(2);
                    out.add(trimmed);
                }
            }
            // Reset accumulator and advance past delimiter.
            accLen = 0;
            i = zero + 1;
        }
        return out == null ? List.of() : out;
    }
}
