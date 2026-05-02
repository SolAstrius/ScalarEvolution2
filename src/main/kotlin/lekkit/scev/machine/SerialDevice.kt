/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.machine

/**
 * Byte-oriented pipe to a guest NS16550A UART. Abstract over the
 * production `lekkit.rvvm.NS16550ABridge` wrapper so tests can swap in
 * an in-memory fake without JNI.
 *
 * Semantics:
 * - [pollTx] drains up to `buf.size` bytes of guest-produced output
 *   into `buf`. Returns the count. Non-blocking; returns 0 when the
 *   guest hasn't written anything.
 * - [feedRx] pushes bytes into the guest's RX queue; returns the count
 *   accepted (may be less than input length if the RX ring is
 *   near-full).
 *
 * Both methods are thread-safe against each other (the underlying ring
 * buffer is spinlock-guarded in C). Callers should not assume ordering
 * across pollTx/feedRx calls from different threads.
 */
interface SerialDevice {
    /** Drain guest TX. Returns number of bytes written into [buf]. */
    fun pollTx(buf: ByteArray): Int

    /** Push bytes into guest RX. Returns number accepted. */
    fun feedRx(buf: ByteArray): Int
}
