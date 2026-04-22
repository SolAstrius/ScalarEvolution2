/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.bus;

/**
 * Kinds of peripherals a {@link PeripheralBusElement} can contribute to
 * the bus. Kept deliberately small — new kinds land when a new peripheral
 * block genuinely needs a distinct routing (e.g. audio, haptics).
 */
public enum PeripheralDeviceKind {
    /** A physical keyboard. Binds player keystrokes to the VM's HID keyboard. */
    KEYBOARD,

    /**
     * A mouse / pointing device. Currently carried piggyback on keyboard
     * blocks that declare {@code hasMouse}, but kept separate so a future
     * standalone mouse block slots in without an API change.
     */
    MOUSE,

    /**
     * A screen that mirrors the VM's framebuffer. CRT, VT100, and future
     * display blocks all report this kind.
     */
    DISPLAY,
}
