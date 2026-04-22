/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.screen;

import lekkit.rvvm.HIDKeyboard;

/**
 * Maps Unicode characters to the HID usage id + shift state that would
 * produce that character on a US-QWERTY keyboard. Used by the paste-as-
 * keystrokes path: clipboard text on the host is decomposed through this
 * table into the sequence of HID events the guest would see if someone
 * typed the text on a physical US keyboard.
 *
 * <p><b>The guest must be configured as US QWERTY.</b> Every paste-as-
 * keystrokes mechanism has this property (spice, qemu, virt-viewer) —
 * the host can't know what xkb layout the guest has loaded, so we pick
 * one layout and document it. US is the sane default.
 *
 * <p>Characters outside the printable ASCII range (and carriage return,
 * which we swallow) return {@code null}. Callers skip them silently.
 */
public final class UsQwerty {
    private UsQwerty() {}

    /** A keystroke: an HID usage id, plus whether shift must be held to produce the character. */
    public record Binding(byte hid, boolean shift) {}

    private static final Binding[] ASCII = new Binding[128];

    static {
        // Letters — lowercase is the unshifted HID code, uppercase is shifted.
        for (char c = 'a'; c <= 'z'; c++) {
            byte hid = (byte) (HIDKeyboard.HID_KEY_A + (c - 'a'));
            ASCII[c] = new Binding(hid, false);
            ASCII[Character.toUpperCase(c)] = new Binding(hid, true);
        }

        // Digits — HID has 1..9 at 0x1e..0x26, 0 at 0x27 (not at 0x1d, so
        // we can't just do HID_KEY_0 + (c - '0') cleanly).
        ASCII['0'] = new Binding(HIDKeyboard.HID_KEY_0, false);
        for (char c = '1'; c <= '9'; c++) {
            ASCII[c] = new Binding((byte) (HIDKeyboard.HID_KEY_1 + (c - '1')), false);
        }
        // Shifted digits share the HID code with their unshifted form, shift=true.
        // Index i here is the digit; the shifted glyph sits in SHIFTED_DIGITS[i].
        String shiftedDigits = ")!@#$%^&*(";
        for (int i = 0; i < 10; i++) {
            Binding d = ASCII['0' + i];
            ASCII[shiftedDigits.charAt(i)] = new Binding(d.hid(), true);
        }

        // Whitespace — LF becomes ENTER; CR is handled by the caller (swallowed).
        ASCII[' ']  = new Binding(HIDKeyboard.HID_KEY_SPACE, false);
        ASCII['\n'] = new Binding(HIDKeyboard.HID_KEY_ENTER, false);
        ASCII['\t'] = new Binding(HIDKeyboard.HID_KEY_TAB, false);

        // Punctuation — each HID key appears twice (unshifted + shifted glyph).
        ASCII['-']  = new Binding(HIDKeyboard.HID_KEY_MINUS, false);
        ASCII['_']  = new Binding(HIDKeyboard.HID_KEY_MINUS, true);
        ASCII['=']  = new Binding(HIDKeyboard.HID_KEY_EQUAL, false);
        ASCII['+']  = new Binding(HIDKeyboard.HID_KEY_EQUAL, true);
        ASCII['[']  = new Binding(HIDKeyboard.HID_KEY_LEFTBRACE, false);
        ASCII['{']  = new Binding(HIDKeyboard.HID_KEY_LEFTBRACE, true);
        ASCII[']']  = new Binding(HIDKeyboard.HID_KEY_RIGHTBRACE, false);
        ASCII['}']  = new Binding(HIDKeyboard.HID_KEY_RIGHTBRACE, true);
        ASCII['\\'] = new Binding(HIDKeyboard.HID_KEY_BACKSLASH, false);
        ASCII['|']  = new Binding(HIDKeyboard.HID_KEY_BACKSLASH, true);
        ASCII[';']  = new Binding(HIDKeyboard.HID_KEY_SEMICOLON, false);
        ASCII[':']  = new Binding(HIDKeyboard.HID_KEY_SEMICOLON, true);
        ASCII['\''] = new Binding(HIDKeyboard.HID_KEY_APOSTROPHE, false);
        ASCII['"']  = new Binding(HIDKeyboard.HID_KEY_APOSTROPHE, true);
        ASCII['`']  = new Binding(HIDKeyboard.HID_KEY_GRAVE, false);
        ASCII['~']  = new Binding(HIDKeyboard.HID_KEY_GRAVE, true);
        ASCII[',']  = new Binding(HIDKeyboard.HID_KEY_COMMA, false);
        ASCII['<']  = new Binding(HIDKeyboard.HID_KEY_COMMA, true);
        ASCII['.']  = new Binding(HIDKeyboard.HID_KEY_DOT, false);
        ASCII['>']  = new Binding(HIDKeyboard.HID_KEY_DOT, true);
        ASCII['/']  = new Binding(HIDKeyboard.HID_KEY_SLASH, false);
        ASCII['?']  = new Binding(HIDKeyboard.HID_KEY_SLASH, true);
    }

    /**
     * Return the keystroke that produces {@code c} on US QWERTY, or
     * {@code null} if the character isn't reachable (non-ASCII, control
     * characters other than \n \t, etc.).
     */
    public static Binding forChar(char c) {
        if (c >= ASCII.length) return null;
        return ASCII[c];
    }
}
