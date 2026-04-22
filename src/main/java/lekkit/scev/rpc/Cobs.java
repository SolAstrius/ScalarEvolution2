/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.rpc;

/**
 * Consistent-Overhead Byte Stuffing framing.
 *
 * <p>COBS lets us send binary frames over a raw byte stream (an NS16550A
 * UART) while still having a single delimiter byte ({@code 0x00}) that
 * unambiguously marks frame boundaries. Any bit error in the middle of a
 * frame shows up as a junk frame; parsing resyncs on the next delimiter.
 * No escape bookkeeping, O(n) encode/decode, ~0.4% worst-case overhead.
 *
 * <p>Algorithm (encode): walk the input; every time we hit a {@code 0x00}
 * or 254 consecutive non-zero bytes, emit a "code" byte telling the
 * decoder how many bytes until the next zero-or-code. A trailing
 * {@code 0x00} is appended as the frame delimiter.
 *
 * <p>See <a href="https://en.wikipedia.org/wiki/Consistent_Overhead_Byte_Stuffing">
 * Wikipedia</a> and RFC-era spec for details.
 */
public final class Cobs {
    private Cobs() {}

    /**
     * Encode {@code in} into {@code out} and append a {@code 0x00}
     * delimiter. {@code out} must have at least {@link #maxEncodedSize}
     * bytes of capacity starting at {@code outOff}.
     *
     * @return the number of bytes written to {@code out}, including the
     *         trailing delimiter.
     */
    public static int encode(byte[] in, int inOff, int inLen, byte[] out, int outOff) {
        int outStart = outOff;
        int codeIdx = outOff++;   // reserve slot for first code byte
        int code = 1;

        for (int i = 0; i < inLen; i++) {
            byte b = in[inOff + i];
            if (b == 0) {
                out[codeIdx] = (byte) code;
                codeIdx = outOff++;
                code = 1;
            } else {
                out[outOff++] = b;
                code++;
                if (code == 0xFF) {
                    out[codeIdx] = (byte) code;
                    codeIdx = outOff++;
                    code = 1;
                }
            }
        }
        out[codeIdx] = (byte) code;
        out[outOff++] = 0;        // frame delimiter
        return outOff - outStart;
    }

    /** Upper bound on encoded size for a given plaintext length (includes delimiter). */
    public static int maxEncodedSize(int plainLen) {
        // Worst case: one code byte per 254 input bytes, plus initial code, plus delimiter.
        return plainLen + (plainLen / 254) + 2;
    }

    /**
     * Decode a COBS frame (up to but NOT including the trailing
     * {@code 0x00}) from {@code in} into {@code out}.
     *
     * @param in      encoded bytes; a leading delimiter, if any, must be
     *                stripped by the caller.
     * @param inOff   start of the encoded data.
     * @param inLen   length up to (but excluding) the terminating
     *                {@code 0x00}.
     * @return the number of plaintext bytes written to {@code out}, or
     *         -1 if the frame is malformed.
     */
    public static int decode(byte[] in, int inOff, int inLen, byte[] out, int outOff) {
        int outStart = outOff;
        int i = 0;
        while (i < inLen) {
            int code = in[inOff + i] & 0xFF;
            if (code == 0) return -1; // stray zero inside a frame
            i++;
            int chunk = code - 1;
            if (i + chunk > inLen) return -1; // overrun
            for (int j = 0; j < chunk; j++) {
                out[outOff++] = in[inOff + i + j];
            }
            i += chunk;
            if (code < 0xFF && i < inLen) {
                // Implicit zero between chunks (not emitted when the code
                // itself was 0xFF — that signals "254 non-zero bytes, no
                // implicit zero follows").
                out[outOff++] = 0;
            }
        }
        return outOff - outStart;
    }
}
