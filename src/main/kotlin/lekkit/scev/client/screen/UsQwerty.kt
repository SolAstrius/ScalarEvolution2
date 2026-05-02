/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.screen

import lekkit.rvvm.HIDKeyboard

/**
 * Maps Unicode characters to the HID usage id + shift state that would
 * produce that character on a US-QWERTY keyboard. Used by the paste-as-
 * keystrokes path: clipboard text on the host is decomposed through this
 * table into the sequence of HID events the guest would see if someone
 * typed the text on a physical US keyboard.
 *
 * **The guest must be configured as US QWERTY.** Every paste-as-keystrokes
 * mechanism has this property (spice, qemu, virt-viewer) — the host can't
 * know what xkb layout the guest has loaded, so we pick one and document.
 *
 * Characters outside the printable ASCII range (and `\r`, which we swallow)
 * return `null`. Callers skip them silently.
 */
object UsQwerty {
    /** A keystroke: an HID usage id, plus whether shift must be held. */
    data class Binding(@get:JvmName("hid") val hid: Byte, @get:JvmName("shift") val shift: Boolean)

    private val ascii: Array<Binding?> = arrayOfNulls(128)

    init {
        // Letters — lowercase = unshifted HID code, uppercase = shifted.
        for (c in 'a'..'z') {
            val hid = (HIDKeyboard.HID_KEY_A + (c - 'a')).toByte()
            ascii[c.code] = Binding(hid, false)
            ascii[c.uppercaseChar().code] = Binding(hid, true)
        }

        // Digits — HID has 1..9 at 0x1e..0x26, 0 at 0x27 (not contiguous).
        ascii['0'.code] = Binding(HIDKeyboard.HID_KEY_0, false)
        for (c in '1'..'9') {
            ascii[c.code] = Binding((HIDKeyboard.HID_KEY_1 + (c - '1')).toByte(), false)
        }
        // Shifted digits share the HID code with their unshifted form.
        val shiftedDigits = ")!@#$%^&*("
        for (i in 0..9) {
            val d = ascii['0'.code + i]!!
            ascii[shiftedDigits[i].code] = Binding(d.hid, true)
        }

        // Whitespace — LF→ENTER; CR is swallowed by the caller.
        ascii[' '.code]  = Binding(HIDKeyboard.HID_KEY_SPACE, false)
        ascii['\n'.code] = Binding(HIDKeyboard.HID_KEY_ENTER, false)
        ascii['\t'.code] = Binding(HIDKeyboard.HID_KEY_TAB, false)

        // Punctuation — each HID key appears twice (unshifted + shifted glyph).
        ascii['-'.code]  = Binding(HIDKeyboard.HID_KEY_MINUS, false)
        ascii['_'.code]  = Binding(HIDKeyboard.HID_KEY_MINUS, true)
        ascii['='.code]  = Binding(HIDKeyboard.HID_KEY_EQUAL, false)
        ascii['+'.code]  = Binding(HIDKeyboard.HID_KEY_EQUAL, true)
        ascii['['.code]  = Binding(HIDKeyboard.HID_KEY_LEFTBRACE, false)
        ascii['{'.code]  = Binding(HIDKeyboard.HID_KEY_LEFTBRACE, true)
        ascii[']'.code]  = Binding(HIDKeyboard.HID_KEY_RIGHTBRACE, false)
        ascii['}'.code]  = Binding(HIDKeyboard.HID_KEY_RIGHTBRACE, true)
        ascii['\\'.code] = Binding(HIDKeyboard.HID_KEY_BACKSLASH, false)
        ascii['|'.code]  = Binding(HIDKeyboard.HID_KEY_BACKSLASH, true)
        ascii[';'.code]  = Binding(HIDKeyboard.HID_KEY_SEMICOLON, false)
        ascii[':'.code]  = Binding(HIDKeyboard.HID_KEY_SEMICOLON, true)
        ascii['\''.code] = Binding(HIDKeyboard.HID_KEY_APOSTROPHE, false)
        ascii['"'.code]  = Binding(HIDKeyboard.HID_KEY_APOSTROPHE, true)
        ascii['`'.code]  = Binding(HIDKeyboard.HID_KEY_GRAVE, false)
        ascii['~'.code]  = Binding(HIDKeyboard.HID_KEY_GRAVE, true)
        ascii[','.code]  = Binding(HIDKeyboard.HID_KEY_COMMA, false)
        ascii['<'.code]  = Binding(HIDKeyboard.HID_KEY_COMMA, true)
        ascii['.'.code]  = Binding(HIDKeyboard.HID_KEY_DOT, false)
        ascii['>'.code]  = Binding(HIDKeyboard.HID_KEY_DOT, true)
        ascii['/'.code]  = Binding(HIDKeyboard.HID_KEY_SLASH, false)
        ascii['?'.code]  = Binding(HIDKeyboard.HID_KEY_SLASH, true)
    }

    /**
     * Keystroke that produces [c] on US QWERTY, or `null` if the character
     * isn't reachable (non-ASCII, control chars other than `\n` `\t`).
     */
    @JvmStatic fun forChar(c: Char): Binding? =
        if (c.code >= ascii.size) null else ascii[c.code]
}
