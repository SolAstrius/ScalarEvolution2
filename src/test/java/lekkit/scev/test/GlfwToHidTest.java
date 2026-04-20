/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test;

import static org.junit.jupiter.api.Assertions.*;

import lekkit.rvvm.HIDKeyboard;
import lekkit.scev.client.screen.GlfwToHid;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

/**
 * Verifies the GLFW -> HID keymap used by {@code MachineScreen}.
 *
 * <p>These run headless (no GLFW init needed — we only use GLFW's static
 * key-code constants) and catch regressions where a shift of the HID or GLFW
 * code ranges silently maps, say, 'A' to HID_KEY_NONE or to a different key.
 */
class GlfwToHidTest {

    @Test
    @DisplayName("Letters A-Z map to HID_KEY_A..Z")
    void letters() {
        assertEquals(HIDKeyboard.HID_KEY_A, GlfwToHid.map(GLFW.GLFW_KEY_A));
        assertEquals(HIDKeyboard.HID_KEY_M, GlfwToHid.map(GLFW.GLFW_KEY_M));
        assertEquals(HIDKeyboard.HID_KEY_Z, GlfwToHid.map(GLFW.GLFW_KEY_Z));
    }

    @Test
    @DisplayName("Number row: 0 at HID_KEY_0 (not 1), 1..9 at 0x1e..0x26")
    void numberRow() {
        assertEquals(HIDKeyboard.HID_KEY_0, GlfwToHid.map(GLFW.GLFW_KEY_0));
        assertEquals(HIDKeyboard.HID_KEY_1, GlfwToHid.map(GLFW.GLFW_KEY_1));
        assertEquals(HIDKeyboard.HID_KEY_5, GlfwToHid.map(GLFW.GLFW_KEY_5));
        assertEquals(HIDKeyboard.HID_KEY_9, GlfwToHid.map(GLFW.GLFW_KEY_9));
    }

    @Test
    @DisplayName("Control keys — Enter, Esc, Tab, Space, Backspace")
    void controlKeys() {
        assertEquals(HIDKeyboard.HID_KEY_ENTER, GlfwToHid.map(GLFW.GLFW_KEY_ENTER));
        assertEquals(HIDKeyboard.HID_KEY_ESC, GlfwToHid.map(GLFW.GLFW_KEY_ESCAPE));
        assertEquals(HIDKeyboard.HID_KEY_TAB, GlfwToHid.map(GLFW.GLFW_KEY_TAB));
        assertEquals(HIDKeyboard.HID_KEY_SPACE, GlfwToHid.map(GLFW.GLFW_KEY_SPACE));
        assertEquals(HIDKeyboard.HID_KEY_BACKSPACE, GlfwToHid.map(GLFW.GLFW_KEY_BACKSPACE));
    }

    @Test
    @DisplayName("Arrow keys")
    void arrows() {
        assertEquals(HIDKeyboard.HID_KEY_UP, GlfwToHid.map(GLFW.GLFW_KEY_UP));
        assertEquals(HIDKeyboard.HID_KEY_DOWN, GlfwToHid.map(GLFW.GLFW_KEY_DOWN));
        assertEquals(HIDKeyboard.HID_KEY_LEFT, GlfwToHid.map(GLFW.GLFW_KEY_LEFT));
        assertEquals(HIDKeyboard.HID_KEY_RIGHT, GlfwToHid.map(GLFW.GLFW_KEY_RIGHT));
    }

    @Test
    @DisplayName("Function keys F1-F12")
    void functionKeys() {
        assertEquals(HIDKeyboard.HID_KEY_F1, GlfwToHid.map(GLFW.GLFW_KEY_F1));
        assertEquals(HIDKeyboard.HID_KEY_F7, GlfwToHid.map(GLFW.GLFW_KEY_F7));
        assertEquals(HIDKeyboard.HID_KEY_F12, GlfwToHid.map(GLFW.GLFW_KEY_F12));
    }

    @Test
    @DisplayName("Modifier keys")
    void modifiers() {
        assertEquals(HIDKeyboard.HID_KEY_LEFTCTRL, GlfwToHid.map(GLFW.GLFW_KEY_LEFT_CONTROL));
        assertEquals(HIDKeyboard.HID_KEY_RIGHTCTRL, GlfwToHid.map(GLFW.GLFW_KEY_RIGHT_CONTROL));
        assertEquals(HIDKeyboard.HID_KEY_LEFTSHIFT, GlfwToHid.map(GLFW.GLFW_KEY_LEFT_SHIFT));
        assertEquals(HIDKeyboard.HID_KEY_RIGHTSHIFT, GlfwToHid.map(GLFW.GLFW_KEY_RIGHT_SHIFT));
        assertEquals(HIDKeyboard.HID_KEY_LEFTALT, GlfwToHid.map(GLFW.GLFW_KEY_LEFT_ALT));
        assertEquals(HIDKeyboard.HID_KEY_RIGHTALT, GlfwToHid.map(GLFW.GLFW_KEY_RIGHT_ALT));
    }

    @Test
    @DisplayName("Numpad keys")
    void numpad() {
        assertEquals(HIDKeyboard.HID_KEY_KP0, GlfwToHid.map(GLFW.GLFW_KEY_KP_0));
        assertEquals(HIDKeyboard.HID_KEY_KP1, GlfwToHid.map(GLFW.GLFW_KEY_KP_1));
        assertEquals(HIDKeyboard.HID_KEY_KP9, GlfwToHid.map(GLFW.GLFW_KEY_KP_9));
        assertEquals(HIDKeyboard.HID_KEY_KPENTER, GlfwToHid.map(GLFW.GLFW_KEY_KP_ENTER));
        assertEquals(HIDKeyboard.HID_KEY_KPPLUS, GlfwToHid.map(GLFW.GLFW_KEY_KP_ADD));
        assertEquals(HIDKeyboard.HID_KEY_KPMINUS, GlfwToHid.map(GLFW.GLFW_KEY_KP_SUBTRACT));
    }

    @Test
    @DisplayName("Punctuation: comma, period, slash, semicolon")
    void punctuation() {
        assertEquals(HIDKeyboard.HID_KEY_COMMA, GlfwToHid.map(GLFW.GLFW_KEY_COMMA));
        assertEquals(HIDKeyboard.HID_KEY_DOT, GlfwToHid.map(GLFW.GLFW_KEY_PERIOD));
        assertEquals(HIDKeyboard.HID_KEY_SLASH, GlfwToHid.map(GLFW.GLFW_KEY_SLASH));
        assertEquals(HIDKeyboard.HID_KEY_SEMICOLON, GlfwToHid.map(GLFW.GLFW_KEY_SEMICOLON));
    }

    @Test
    @DisplayName("Unknown keys return HID_KEY_NONE")
    void unknownReturnsNone() {
        // GLFW_KEY_UNKNOWN is -1.
        assertEquals(HIDKeyboard.HID_KEY_NONE, GlfwToHid.map(GLFW.GLFW_KEY_UNKNOWN));
        // An arbitrary high key-code we don't map.
        assertEquals(HIDKeyboard.HID_KEY_NONE, GlfwToHid.map(9999));
    }
}
