/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.component.api

/**
 * POSIX errno constants with the values the Linux kernel uses.
 *
 * Scev wants the guest's view of failures to look like ordinary POSIX
 * errors (`ENOMEDIUM` for "no book in the routing detector",
 * `EACCES` for "locked", etc.). These constants are the bridge: a
 * host-side [PeripheralException] carries an errno value picked from
 * here, and scev's FS layer surfaces it as the read/write syscall's
 * error code on the guest.
 *
 * Only the subset scev actually uses is listed. Add more if a
 * peripheral lights up a case we haven't covered.
 *
 * Declared as `const val` in a top-level `object` so Java and Scala
 * see `Errno.EACCES` as a `public static final int` identically, and
 * Kotlin callers get `Errno.EACCES` the same way.
 */
object Errno {
    // ---------------- File/syscall basics ----------------

    /** No such file or directory. Stale device path. */
    const val ENOENT: Int = 2

    /** I/O error — catch-all when no more specific code fits. */
    const val EIO: Int = 5

    /** Try again (resource temporarily unavailable). */
    const val EAGAIN: Int = 11

    /** Permission denied. Locked peripherals. */
    const val EACCES: Int = 13

    /** Device or resource busy. Caller acted while something's in flight. */
    const val EBUSY: Int = 16

    /** No such device. Peripheral detached mid-call. */
    const val ENODEV: Int = 19

    /** Invalid argument. Malformed inputs / out-of-domain values. */
    const val EINVAL: Int = 22

    /** Read-only filesystem / read-only property written to. */
    const val EROFS: Int = 30

    /** Numerical result out of range. */
    const val ERANGE: Int = 34

    /** File name too long. Over-length string setters. */
    const val ENAMETOOLONG: Int = 36

    /** Function not implemented. Unsupported operations. */
    const val ENOSYS: Int = 38

    // ---------------- Peripheral-specific ----------------

    /**
     * No medium found. Our most-used peripheral-specific code:
     * "the printer has no paper", "the routing detector has no book
     * in its slot", "the disk drive has no disk inserted." Not
     * standard POSIX but widely used by Linux block-layer drivers
     * since forever, so shells interpret it naturally.
     */
    const val ENOMEDIUM: Int = 123
}
