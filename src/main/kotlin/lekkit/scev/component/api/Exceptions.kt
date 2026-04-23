/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.component.api

/**
 * Root of the scev peripheral exception hierarchy. Every subclass
 * carries an [errno] value that scev surfaces to the guest as the
 * POSIX error code on the failing syscall.
 *
 * Authors writing components throw these directly from their
 * `@Property` / `@Action` methods; scev catches them in the
 * reflection dispatcher and maps [errno] onto the wire.
 *
 * Use the specific subclasses when they fit. For uncategorised
 * failures, throw [PeripheralException] directly with an explicit
 * errno (preferring [Errno.EIO] or [Errno.EINVAL] as the default).
 *
 * Kotlin `open class` → Java sees a normal exception class. Java and
 * Scala callers catch these the same way Kotlin does.
 */
open class PeripheralException(
    val errno: Int,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * No medium found. Thrown when the peripheral requires a physical
 * item that isn't currently loaded: the printer's paper slot is
 * empty, the routing detector has no book, the disk drive has no
 * disk.
 *
 * Maps to [Errno.ENOMEDIUM] on the guest.
 */
class NoMediumException(
    message: String = "no medium",
    cause: Throwable? = null,
) : PeripheralException(Errno.ENOMEDIUM, message, cause)

/**
 * Peripheral is locked against this operation. Used by things like
 * the Railcraft routing detector's `isSecure` flag, sealed inventory
 * slots, password-protected devices.
 *
 * Maps to [Errno.EACCES] on the guest.
 */
class LockedException(
    message: String = "locked",
    cause: Throwable? = null,
) : PeripheralException(Errno.EACCES, message, cause)

/**
 * Peripheral is busy — another operation is in flight, or a
 * cool-down is active. Scripts should retry after a short delay.
 *
 * Maps to [Errno.EBUSY] on the guest.
 */
class BusyException(
    message: String = "busy",
    cause: Throwable? = null,
) : PeripheralException(Errno.EBUSY, message, cause)

/**
 * Attempt to write a read-only property or action. Should not occur
 * if the component descriptor is correct; surfaces as [Errno.EROFS]
 * for defensive handling.
 */
class ReadOnlyException(
    message: String = "read-only",
    cause: Throwable? = null,
) : PeripheralException(Errno.EROFS, message, cause)

/**
 * Malformed input — parse failure on an action's argument file,
 * out-of-range value on a bounded property, unknown enum value.
 *
 * Maps to [Errno.EINVAL].
 */
class InvalidArgumentException(
    message: String = "invalid argument",
    cause: Throwable? = null,
) : PeripheralException(Errno.EINVAL, message, cause)

/**
 * Numeric value outside the advertised range. Distinct from
 * [InvalidArgumentException] so scripts can react differently to
 * "0.9 < stop_level ≤ 1.0 enforced" than to "gibberish."
 *
 * Maps to [Errno.ERANGE].
 */
class OutOfRangeException(
    message: String = "out of range",
    cause: Throwable? = null,
) : PeripheralException(Errno.ERANGE, message, cause)

/**
 * The peripheral no longer exists — the block was broken or the
 * capability went stale between when scev resolved the peer and when
 * it dispatched the call.
 *
 * Maps to [Errno.ENODEV].
 */
class PeripheralGoneException(
    message: String = "no such device",
    cause: Throwable? = null,
) : PeripheralException(Errno.ENODEV, message, cause)
