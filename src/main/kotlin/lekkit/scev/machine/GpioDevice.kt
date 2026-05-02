/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.machine

/**
 * Abstract GPIO device — a 6-bit pin field addressable by the guest VM.
 *
 * The bit-to-pin mapping is **block-relative**, not world-oriented.
 * Firmware inside the VM sees a stable port layout regardless of how
 * the host block was placed in the world:
 *
 * ```
 *   bit 0 = FRONT   (the face the block points at)
 *   bit 1 = BACK
 *   bit 2 = LEFT
 *   bit 3 = RIGHT
 *   bit 4 = TOP
 *   bit 5 = BOTTOM
 * ```
 *
 * The translation between this guest-facing layout and world-oriented
 * redstone queries happens at the block-entity boundary in
 * [lekkit.scev.blockentity.ComputerCaseBlockEntity], using
 * [GpioPinMap]. Implementations of this interface are themselves
 * orientation-agnostic — they just store / forward 6-bit values.
 */
interface GpioDevice {
    /**
     * Read the pin state. Bit indices match the block-relative layout
     * in the class-level kdoc. Implementations mask to the low 6 bits.
     */
    fun readPins(): Int

    /**
     * Write the pin state. Same encoding as [readPins]; implementations
     * must mask input to the low 6 bits.
     */
    fun writePins(pins: Int)
}
