/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.machine;

/**
 * Byte-oriented pipe to a guest NS16550A UART. Abstract over the
 * production {@link lekkit.rvvm.NS16550ABridge} wrapper so tests can
 * swap in an in-memory fake without JNI.
 *
 * <p>Semantics:
 * <ul>
 *   <li>{@link #pollTx(byte[])} drains up to {@code buf.length} bytes
 *       of guest-produced output into {@code buf}. Returns the count.
 *       Non-blocking; returns 0 when the guest hasn't written anything.</li>
 *   <li>{@link #feedRx(byte[])} pushes bytes into the guest's RX queue;
 *       returns the count accepted (may be less than input length if the
 *       RX ring is near-full).</li>
 * </ul>
 *
 * <p>Both methods are thread-safe against each other (the underlying ring
 * buffer is spinlock-guarded in C). Callers should not assume ordering
 * across pollTx/feedRx calls from different threads.
 */
public interface SerialDevice {
    /** Drain guest TX. Returns number of bytes written into {@code buf}. */
    int pollTx(byte[] buf);

    /** Push bytes into guest RX. Returns number accepted. */
    int feedRx(byte[] buf);
}
