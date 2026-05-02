/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.machine

/**
 * Abstract HID keyboard: press / release a single USB HID usage code.
 *
 * Scancodes are the standard USB HID 8-bit usage IDs (see
 * `lekkit.rvvm.HIDKeyboard.HID_KEY_*`). [press] and [release] are
 * idempotent — pressing a key twice is equivalent to pressing once,
 * releasing a non-pressed key is a no-op.
 */
interface KeyboardDevice {
    /** Send a key press. Idempotent. */
    fun press(hidScancode: Byte)

    /** Send a key release. Idempotent. */
    fun release(hidScancode: Byte)
}
