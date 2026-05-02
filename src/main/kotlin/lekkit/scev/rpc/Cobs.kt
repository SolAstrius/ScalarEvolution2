/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.rpc

/**
 * Consistent-Overhead Byte Stuffing framing.
 *
 * COBS lets us send binary frames over a raw byte stream (an NS16550A
 * UART) while still having a single delimiter byte (`0x00`) that
 * unambiguously marks frame boundaries. Any bit error in the middle of a
 * frame shows up as a junk frame; parsing resyncs on the next delimiter.
 * No escape bookkeeping, O(n) encode/decode, ~0.4% worst-case overhead.
 *
 * Algorithm (encode): walk the input; every time we hit a `0x00` or 254
 * consecutive non-zero bytes, emit a "code" byte telling the decoder how
 * many bytes until the next zero-or-code. A trailing `0x00` is appended
 * as the frame delimiter.
 *
 * See [Wikipedia](https://en.wikipedia.org/wiki/Consistent_Overhead_Byte_Stuffing)
 * and the RFC-era spec for details.
 */
object Cobs {

    /**
     * Encode [in][inBuf] into [out] and append a `0x00` delimiter. [out]
     * must have at least [maxEncodedSize] bytes of capacity starting at
     * [outOff].
     *
     * @return the number of bytes written to [out], including the trailing
     *         delimiter.
     */
    @JvmStatic
    fun encode(inBuf: ByteArray, inOff: Int, inLen: Int, out: ByteArray, outOff: Int): Int {
        val outStart = outOff
        var o = outOff
        var codeIdx = o++       // reserve slot for first code byte
        var code = 1

        for (i in 0 until inLen) {
            val b = inBuf[inOff + i]
            if (b.toInt() == 0) {
                out[codeIdx] = code.toByte()
                codeIdx = o++
                code = 1
            } else {
                out[o++] = b
                code++
                if (code == 0xFF) {
                    out[codeIdx] = code.toByte()
                    codeIdx = o++
                    code = 1
                }
            }
        }
        out[codeIdx] = code.toByte()
        out[o++] = 0            // frame delimiter
        return o - outStart
    }

    /** Upper bound on encoded size for a given plaintext length (includes delimiter). */
    @JvmStatic
    fun maxEncodedSize(plainLen: Int): Int {
        // Worst case: one code byte per 254 input bytes, plus initial code, plus delimiter.
        return plainLen + (plainLen / 254) + 2
    }

    /**
     * Decode a COBS frame (up to but NOT including the trailing `0x00`)
     * from [in][inBuf] into [out].
     *
     * @param inBuf encoded bytes; a leading delimiter, if any, must be
     *              stripped by the caller.
     * @param inOff start of the encoded data.
     * @param inLen length up to (but excluding) the terminating `0x00`.
     * @return the number of plaintext bytes written to [out], or -1 if the
     *         frame is malformed.
     */
    @JvmStatic
    fun decode(inBuf: ByteArray, inOff: Int, inLen: Int, out: ByteArray, outOff: Int): Int {
        val outStart = outOff
        var o = outOff
        var i = 0
        while (i < inLen) {
            val code = inBuf[inOff + i].toInt() and 0xFF
            if (code == 0) return -1                 // stray zero inside a frame
            i++
            val chunk = code - 1
            if (i + chunk > inLen) return -1         // overrun
            for (j in 0 until chunk) {
                out[o++] = inBuf[inOff + i + j]
            }
            i += chunk
            if (code < 0xFF && i < inLen) {
                // Implicit zero between chunks (not emitted when the code
                // itself was 0xFF — that signals "254 non-zero bytes, no
                // implicit zero follows").
                out[o++] = 0
            }
        }
        return o - outStart
    }
}
