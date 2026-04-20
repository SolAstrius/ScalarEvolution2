/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.machine;

/**
 * Abstract GPIO device. A 6-bit pin field, one bit per Minecraft
 * {@link net.minecraft.core.Direction}. Reads and writes are packed into the
 * low 6 bits of an int.
 */
public interface GpioDevice {
    /**
     * Read the pin state. Bit N corresponds to {@code Direction.ordinal() == N}.
     * Only the low 6 bits are meaningful.
     */
    int readPins();

    /**
     * Write the pin state. Same encoding as {@link #readPins}; implementations
     * must mask input to the low 6 bits.
     */
    void writePins(int pins);
}
