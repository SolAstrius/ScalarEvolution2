/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.rpc

/**
 * Stateful reader that consumes arbitrary byte chunks and emits complete
 * COBS-decoded frames.
 *
 * Usage:
 *
 * ```
 *   val s = FrameStream(4096)
 *   val chunk = serial.pollTx()    // may contain 0, 1, many, or partial frames
 *   for (plain in s.feed(chunk, 0, chunk.size)) {
 *       handle(plain)
 *   }
 * ```
 *
 * Accumulates bytes up to the next `0x00` delimiter, decodes the COBS
 * block, hands back the plaintext. Bad frames (COBS decode failure,
 * too-large accumulation) reset the accumulator and return nothing — the
 * sync byte at the next delimiter resumes clean parsing.
 *
 * [maxFrameBytes] caps per-frame encoded size. Anything larger is a
 * protocol violation and is dropped silently; the caller can notice via
 * [droppedFrames].
 */
class FrameStream(private val maxFrameBytes: Int) {
    private val acc = ByteArray(maxFrameBytes)
    private var accLen = 0

    @get:JvmName("droppedFrames")
    var droppedFrames: Long = 0L
        private set

    /**
     * Feed bytes; return any completed plaintext frames. Empty list when
     * the bytes didn't complete any frame.
     */
    fun feed(buf: ByteArray, off: Int, len: Int): List<ByteArray> {
        var out: MutableList<ByteArray>? = null
        val end = off + len
        var i = off
        while (i < end) {
            // Find the next delimiter.
            var zero = -1
            for (j in i until end) {
                if (buf[j].toInt() == 0) { zero = j; break }
            }
            if (zero < 0) {
                // No delimiter — accumulate rest and wait for more.
                val take = end - i
                if (accLen + take > maxFrameBytes) {
                    droppedFrames++
                    accLen = 0           // discard; resume on next delimiter
                    return out ?: emptyList()
                }
                System.arraycopy(buf, i, acc, accLen, take)
                accLen += take
                break
            }
            // We have a complete COBS block in acc[0..accLen] + buf[i..zero).
            val tail = zero - i
            if (accLen + tail > maxFrameBytes) {
                droppedFrames++
            } else if (accLen + tail > 0) {
                System.arraycopy(buf, i, acc, accLen, tail)
                val encodedLen = accLen + tail
                val plain = ByteArray(encodedLen)    // decoded ≤ encoded
                val n = Cobs.decode(acc, 0, encodedLen, plain, 0)
                if (n < 0) {
                    droppedFrames++
                } else {
                    val trimmed = if (n == plain.size) plain else plain.copyOf(n)
                    val list = out ?: ArrayList<ByteArray>(2).also { out = it }
                    list.add(trimmed)
                }
            }
            // Reset accumulator and advance past delimiter.
            accLen = 0
            i = zero + 1
        }
        return out ?: emptyList()
    }
}
