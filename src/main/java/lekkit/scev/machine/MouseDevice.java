/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.machine;

/**
 * Abstract HID mouse.
 *
 * <p>Coordinates are virtual-pixel coordinates inside a resolution set via
 * {@link #resolution(int, int)}; relative {@link #move(int, int)} is in
 * device units. Buttons follow the HID bitmask convention:
 * {@code 1 = left, 2 = right, 4 = middle}.
 */
public interface MouseDevice {
    /** Declare the virtual display dimensions for coordinate translation. */
    void resolution(int width, int height);

    /** Place the pointer at an absolute coordinate. */
    void place(int x, int y);

    /** Move the pointer by a relative delta. */
    void move(int dx, int dy);

    /** Press one or more buttons (HID bitmask). */
    void press(byte hidButtons);

    /** Release one or more buttons. */
    void release(byte hidButtons);

    /** Scroll wheel: positive = up, negative = down. */
    void scroll(byte delta);
}
