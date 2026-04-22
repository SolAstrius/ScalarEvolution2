/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.machine;

import static org.junit.jupiter.api.Assertions.*;

import lekkit.rvvm.HIDKeyboard;
import lekkit.scev.client.screen.UsQwerty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the clipboard-char → HID+shift map. Cases are organized so a
 * single typo in the table (swapped shift flag, wrong HID code) fails a
 * targeted test rather than a generic "table is wrong" assertion.
 */
class UsQwertyTest {

    @Test
    @DisplayName("Lowercase letters map to HID A..Z with shift=false")
    void lowercaseLetters() {
        UsQwerty.Binding a = UsQwerty.forChar('a');
        assertNotNull(a);
        assertEquals(HIDKeyboard.HID_KEY_A, a.hid());
        assertFalse(a.shift());

        UsQwerty.Binding z = UsQwerty.forChar('z');
        assertEquals(HIDKeyboard.HID_KEY_Z, z.hid());
        assertFalse(z.shift());
    }

    @Test
    @DisplayName("Uppercase letters share the HID with their lowercase but set shift=true")
    void uppercaseLetters() {
        UsQwerty.Binding A = UsQwerty.forChar('A');
        assertNotNull(A);
        assertEquals(HIDKeyboard.HID_KEY_A, A.hid());
        assertTrue(A.shift());

        UsQwerty.Binding Z = UsQwerty.forChar('Z');
        assertEquals(HIDKeyboard.HID_KEY_Z, Z.hid());
        assertTrue(Z.shift());
    }

    @Test
    @DisplayName("Digits 0..9 use HID_KEY_0 for '0' and HID_KEY_1..9 for '1'..'9'")
    void digits() {
        assertEquals(HIDKeyboard.HID_KEY_0, UsQwerty.forChar('0').hid());
        assertEquals(HIDKeyboard.HID_KEY_1, UsQwerty.forChar('1').hid());
        assertEquals(HIDKeyboard.HID_KEY_9, UsQwerty.forChar('9').hid());
        for (char c = '0'; c <= '9'; c++) assertFalse(UsQwerty.forChar(c).shift());
    }

    @Test
    @DisplayName("Shifted digit glyphs map to the underlying digit's HID with shift=true")
    void shiftedDigits() {
        // ")" shares HID with "0"; "!" with "1"; etc.
        assertEquals(UsQwerty.forChar('0').hid(), UsQwerty.forChar(')').hid());
        assertEquals(UsQwerty.forChar('1').hid(), UsQwerty.forChar('!').hid());
        assertEquals(UsQwerty.forChar('2').hid(), UsQwerty.forChar('@').hid());
        assertEquals(UsQwerty.forChar('5').hid(), UsQwerty.forChar('%').hid());
        assertEquals(UsQwerty.forChar('9').hid(), UsQwerty.forChar('(').hid());
        assertTrue(UsQwerty.forChar(')').shift());
        assertTrue(UsQwerty.forChar('@').shift());
        assertTrue(UsQwerty.forChar('(').shift());
    }

    @Test
    @DisplayName("Whitespace: space/LF/TAB all map without shift")
    void whitespace() {
        assertEquals(HIDKeyboard.HID_KEY_SPACE, UsQwerty.forChar(' ').hid());
        assertEquals(HIDKeyboard.HID_KEY_ENTER, UsQwerty.forChar('\n').hid());
        assertEquals(HIDKeyboard.HID_KEY_TAB, UsQwerty.forChar('\t').hid());
        assertFalse(UsQwerty.forChar(' ').shift());
        assertFalse(UsQwerty.forChar('\n').shift());
        assertFalse(UsQwerty.forChar('\t').shift());
    }

    @Test
    @DisplayName("Punctuation: unshifted and shifted glyphs share the same HID")
    void punctuationPairing() {
        // Sanity-check a handful of the trickier shift pairs — semicolon/colon,
        // apostrophe/quote, slash/question. A swapped shift flag here would
        // have made every typed URL or path unreadable.
        assertEquals(UsQwerty.forChar(';').hid(), UsQwerty.forChar(':').hid());
        assertEquals(UsQwerty.forChar('\'').hid(), UsQwerty.forChar('"').hid());
        assertEquals(UsQwerty.forChar('/').hid(), UsQwerty.forChar('?').hid());
        assertEquals(UsQwerty.forChar('-').hid(), UsQwerty.forChar('_').hid());
        assertEquals(UsQwerty.forChar('=').hid(), UsQwerty.forChar('+').hid());

        assertFalse(UsQwerty.forChar(';').shift());
        assertTrue(UsQwerty.forChar(':').shift());
        assertFalse(UsQwerty.forChar('/').shift());
        assertTrue(UsQwerty.forChar('?').shift());
    }

    @Test
    @DisplayName("Backslash and pipe pair correctly (a known-easy-to-flip mapping)")
    void backslashAndPipe() {
        assertEquals(HIDKeyboard.HID_KEY_BACKSLASH, UsQwerty.forChar('\\').hid());
        assertEquals(HIDKeyboard.HID_KEY_BACKSLASH, UsQwerty.forChar('|').hid());
        assertFalse(UsQwerty.forChar('\\').shift());
        assertTrue(UsQwerty.forChar('|').shift());
    }

    @Test
    @DisplayName("Unmappable characters return null — callers skip silently")
    void unmappableReturnsNull() {
        assertNull(UsQwerty.forChar('\r'));       // CR — caller strips it explicitly
        assertNull(UsQwerty.forChar((char) 0));   // NUL
        assertNull(UsQwerty.forChar((char) 7));   // BEL
        assertNull(UsQwerty.forChar('é'));         // non-ASCII
        assertNull(UsQwerty.forChar('漢'));         // CJK
        assertNull(UsQwerty.forChar('€'));         // Euro sign
    }

    @Test
    @DisplayName("Every printable ASCII character except the control oddities is reachable")
    void coverageOfPrintableAscii() {
        // Walk the whole 0x20..0x7E range plus \t and \n; each must be mapped.
        // Guards against a regression that silently drops a key (e.g. removing
        // a line in the static initializer).
        StringBuilder missing = new StringBuilder();
        for (int c = 0x20; c <= 0x7E; c++) {
            if (UsQwerty.forChar((char) c) == null) missing.append((char) c);
        }
        if (UsQwerty.forChar('\t') == null) missing.append("\\t");
        if (UsQwerty.forChar('\n') == null) missing.append("\\n");
        assertEquals("", missing.toString(), "unmapped printable characters: " + missing);
    }
}
