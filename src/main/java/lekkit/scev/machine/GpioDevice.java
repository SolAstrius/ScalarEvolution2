/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.machine;

/**
 * Abstract GPIO device — a 6-bit pin field addressable by the guest VM.
 *
 * <p>The bit-to-pin mapping is <b>block-relative</b>, not world-oriented.
 * Firmware inside the VM sees a stable port layout regardless of how the
 * host block was placed in the world:
 *
 * <pre>
 *   bit 0 = FRONT   (the face the block points at)
 *   bit 1 = BACK
 *   bit 2 = LEFT
 *   bit 3 = RIGHT
 *   bit 4 = TOP
 *   bit 5 = BOTTOM
 * </pre>
 *
 * <p>The translation between this guest-facing layout and world-oriented
 * redstone queries happens at the block-entity boundary in
 * {@link lekkit.scev.blockentity.ComputerCaseBlockEntity}, using
 * {@link GpioPinMap}. Implementations of this interface are themselves
 * orientation-agnostic — they just store / forward 6-bit values.
 */
public interface GpioDevice {
    /**
     * Read the pin state. Bit indices match the block-relative layout in the
     * class-level javadoc. Implementations mask to the low 6 bits.
     */
    int readPins();

    /**
     * Write the pin state. Same encoding as {@link #readPins}; implementations
     * must mask input to the low 6 bits.
     */
    void writePins(int pins);
}
