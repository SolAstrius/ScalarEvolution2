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
 *
 * **Embedded-frame recovery.** When the guest TTY briefly opens in cooked
 * mode (the few-microsecond window between `open(/dev/ttyS1)` and
 * `tcsetattr` setting raw mode), the kernel echoes inbound bytes back to
 * us with `ECHOCTL` substitution: every `0x00` delimiter is rewritten as
 * the literal pair `0x5e 0x40` (`^@`), every `0x02` as `0x5e 0x42`, etc.
 * The host then sees one giant chunk of echo trash with no real `0x00`s
 * inside it, finally terminated by the guest's first real outbound frame
 * (whose own trailing `0x00` is the first delimiter we actually see). A
 * straight COBS decode of `[trash]+[real frame]` fails, taking the real
 * frame down with it.
 *
 * If [recoveryValidator] is non-null, after a normal COBS decode failure
 * the framer scans forward through the failed bytes attempting to decode
 * each `[k..end]` suffix; the first suffix that decodes AND passes the
 * validator is emitted instead of being dropped. The trash bytes never
 * pass the validator (they're caret-encoded literals, not valid COBS),
 * so the scan slides off them and lands on the real frame. Cost is
 * O(failed-frame-size²), but only fires on already-broken frames; happy
 * path is untouched.
 */
class FrameStream @JvmOverloads constructor(
    private val maxFrameBytes: Int,
    private val recoveryValidator: ((plain: ByteArray, len: Int) -> Boolean)? = null,
) {
    private val acc = ByteArray(maxFrameBytes)
    private var accLen = 0
    private val recoveryScratch = ByteArray(maxFrameBytes)

    @get:JvmName("droppedFrames")
    var droppedFrames: Long = 0L
        private set

    @get:JvmName("recoveredFrames")
    var recoveredFrames: Long = 0L
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
                    val recoveredLen = tryRecover(encodedLen)
                    if (recoveredLen < 0) {
                        droppedFrames++
                    } else {
                        recoveredFrames++
                        val recovered = ByteArray(recoveredLen)
                        System.arraycopy(recoveryScratch, 0, recovered, 0, recoveredLen)
                        val list = out ?: ArrayList<ByteArray>(2).also { out = it }
                        list.add(recovered)
                    }
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

    /**
     * After a regular COBS decode failure on `acc[0..encodedLen]`, walk
     * forward looking for the first offset whose suffix both COBS-decodes
     * and passes [recoveryValidator]. Returns the decoded length on
     * success (decoded bytes live in [recoveryScratch]), or -1 if no
     * valid sub-frame is found or recovery is disabled.
     */
    private fun tryRecover(encodedLen: Int): Int {
        val validator = recoveryValidator ?: return -1
        var k = 1
        while (k < encodedLen) {
            val n = Cobs.decode(acc, k, encodedLen - k, recoveryScratch, 0)
            if (n > 0 && validator(recoveryScratch, n)) return n
            k++
        }
        return -1
    }
}
