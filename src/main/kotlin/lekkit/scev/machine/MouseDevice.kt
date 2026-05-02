/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.machine

/**
 * Abstract HID mouse.
 *
 * Coordinates are virtual-pixel coordinates inside a resolution set
 * via [resolution]; relative [move] is in device units. Buttons follow
 * the HID bitmask convention: `1 = left, 2 = right, 4 = middle`.
 */
interface MouseDevice {
    /** Declare the virtual display dimensions for coordinate translation. */
    fun resolution(width: Int, height: Int)

    /** Place the pointer at an absolute coordinate. */
    fun place(x: Int, y: Int)

    /** Move the pointer by a relative delta. */
    fun move(dx: Int, dy: Int)

    /** Press one or more buttons (HID bitmask). */
    fun press(hidButtons: Byte)

    /** Release one or more buttons. */
    fun release(hidButtons: Byte)

    /** Scroll wheel: positive = up, negative = down. */
    fun scroll(delta: Byte)
}
