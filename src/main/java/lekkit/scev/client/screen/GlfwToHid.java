/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.screen;

import lekkit.rvvm.HIDKeyboard;
import org.lwjgl.glfw.GLFW;

/**
 * Maps GLFW key codes (what Minecraft 1.21 passes to {@code Screen#keyPressed})
 * to USB HID usage IDs (what {@link HIDKeyboard} expects).
 *
 * <p>The original 1.7.10 mod had a similar mapping against LWJGL 2.x Keyboard
 * constants; this port regenerates it against {@code org.lwjgl.glfw.GLFW}.
 *
 * <p>Only a reasonable subset of keys is mapped — letters, numbers, function
 * keys, modifiers, arrows, editing keys, and a handful of punctuation. Keys
 * not in the map return {@link HIDKeyboard#HID_KEY_NONE} (0), which the server
 * handler ignores.
 */
public final class GlfwToHid {
    private GlfwToHid() {}

    /**
     * Return the HID usage id for a GLFW key, or {@link HIDKeyboard#HID_KEY_NONE}
     * if unmapped.
     */
    public static byte map(int glfwKey) {
        // Letters
        if (glfwKey >= GLFW.GLFW_KEY_A && glfwKey <= GLFW.GLFW_KEY_Z) {
            return (byte) (HIDKeyboard.HID_KEY_A + (glfwKey - GLFW.GLFW_KEY_A));
        }
        // Numbers (top row) — GLFW uses ASCII '0' = 48 ... '9' = 57.
        // HID: 1..9 at 0x1e..0x26, 0 at 0x27.
        if (glfwKey == GLFW.GLFW_KEY_0) return HIDKeyboard.HID_KEY_0;
        if (glfwKey >= GLFW.GLFW_KEY_1 && glfwKey <= GLFW.GLFW_KEY_9) {
            return (byte) (HIDKeyboard.HID_KEY_1 + (glfwKey - GLFW.GLFW_KEY_1));
        }
        // Function keys F1..F12
        if (glfwKey >= GLFW.GLFW_KEY_F1 && glfwKey <= GLFW.GLFW_KEY_F12) {
            return (byte) (HIDKeyboard.HID_KEY_F1 + (glfwKey - GLFW.GLFW_KEY_F1));
        }
        // F13..F24
        if (glfwKey >= GLFW.GLFW_KEY_F13 && glfwKey <= GLFW.GLFW_KEY_F24) {
            return (byte) (HIDKeyboard.HID_KEY_F13 + (glfwKey - GLFW.GLFW_KEY_F13));
        }
        // Keypad 0..9 and 0
        if (glfwKey == GLFW.GLFW_KEY_KP_0) return HIDKeyboard.HID_KEY_KP0;
        if (glfwKey >= GLFW.GLFW_KEY_KP_1 && glfwKey <= GLFW.GLFW_KEY_KP_9) {
            return (byte) (HIDKeyboard.HID_KEY_KP1 + (glfwKey - GLFW.GLFW_KEY_KP_1));
        }

        return switch (glfwKey) {
            // Control keys
            case GLFW.GLFW_KEY_ENTER -> HIDKeyboard.HID_KEY_ENTER;
            case GLFW.GLFW_KEY_ESCAPE -> HIDKeyboard.HID_KEY_ESC;
            case GLFW.GLFW_KEY_BACKSPACE -> HIDKeyboard.HID_KEY_BACKSPACE;
            case GLFW.GLFW_KEY_TAB -> HIDKeyboard.HID_KEY_TAB;
            case GLFW.GLFW_KEY_SPACE -> HIDKeyboard.HID_KEY_SPACE;
            case GLFW.GLFW_KEY_MINUS -> HIDKeyboard.HID_KEY_MINUS;
            case GLFW.GLFW_KEY_EQUAL -> HIDKeyboard.HID_KEY_EQUAL;
            case GLFW.GLFW_KEY_LEFT_BRACKET -> HIDKeyboard.HID_KEY_LEFTBRACE;
            case GLFW.GLFW_KEY_RIGHT_BRACKET -> HIDKeyboard.HID_KEY_RIGHTBRACE;
            case GLFW.GLFW_KEY_BACKSLASH -> HIDKeyboard.HID_KEY_BACKSLASH;
            case GLFW.GLFW_KEY_SEMICOLON -> HIDKeyboard.HID_KEY_SEMICOLON;
            case GLFW.GLFW_KEY_APOSTROPHE -> HIDKeyboard.HID_KEY_APOSTROPHE;
            case GLFW.GLFW_KEY_GRAVE_ACCENT -> HIDKeyboard.HID_KEY_GRAVE;
            case GLFW.GLFW_KEY_COMMA -> HIDKeyboard.HID_KEY_COMMA;
            case GLFW.GLFW_KEY_PERIOD -> HIDKeyboard.HID_KEY_DOT;
            case GLFW.GLFW_KEY_SLASH -> HIDKeyboard.HID_KEY_SLASH;
            case GLFW.GLFW_KEY_CAPS_LOCK -> HIDKeyboard.HID_KEY_CAPSLOCK;

            // Editing
            case GLFW.GLFW_KEY_PRINT_SCREEN -> HIDKeyboard.HID_KEY_SYSRQ;
            case GLFW.GLFW_KEY_SCROLL_LOCK -> HIDKeyboard.HID_KEY_SCROLLLOCK;
            case GLFW.GLFW_KEY_PAUSE -> HIDKeyboard.HID_KEY_PAUSE;
            case GLFW.GLFW_KEY_INSERT -> HIDKeyboard.HID_KEY_INSERT;
            case GLFW.GLFW_KEY_HOME -> HIDKeyboard.HID_KEY_HOME;
            case GLFW.GLFW_KEY_PAGE_UP -> HIDKeyboard.HID_KEY_PAGEUP;
            case GLFW.GLFW_KEY_DELETE -> HIDKeyboard.HID_KEY_DELETE;
            case GLFW.GLFW_KEY_END -> HIDKeyboard.HID_KEY_END;
            case GLFW.GLFW_KEY_PAGE_DOWN -> HIDKeyboard.HID_KEY_PAGEDOWN;
            case GLFW.GLFW_KEY_RIGHT -> HIDKeyboard.HID_KEY_RIGHT;
            case GLFW.GLFW_KEY_LEFT -> HIDKeyboard.HID_KEY_LEFT;
            case GLFW.GLFW_KEY_DOWN -> HIDKeyboard.HID_KEY_DOWN;
            case GLFW.GLFW_KEY_UP -> HIDKeyboard.HID_KEY_UP;

            // Keypad
            case GLFW.GLFW_KEY_NUM_LOCK -> HIDKeyboard.HID_KEY_NUMLOCK;
            case GLFW.GLFW_KEY_KP_DIVIDE -> HIDKeyboard.HID_KEY_KPSLASH;
            case GLFW.GLFW_KEY_KP_MULTIPLY -> HIDKeyboard.HID_KEY_KPASTERISK;
            case GLFW.GLFW_KEY_KP_SUBTRACT -> HIDKeyboard.HID_KEY_KPMINUS;
            case GLFW.GLFW_KEY_KP_ADD -> HIDKeyboard.HID_KEY_KPPLUS;
            case GLFW.GLFW_KEY_KP_ENTER -> HIDKeyboard.HID_KEY_KPENTER;
            case GLFW.GLFW_KEY_KP_DECIMAL -> HIDKeyboard.HID_KEY_KPDOT;
            case GLFW.GLFW_KEY_KP_EQUAL -> HIDKeyboard.HID_KEY_KPEQUAL;

            // Modifiers
            case GLFW.GLFW_KEY_LEFT_CONTROL -> HIDKeyboard.HID_KEY_LEFTCTRL;
            case GLFW.GLFW_KEY_LEFT_SHIFT -> HIDKeyboard.HID_KEY_LEFTSHIFT;
            case GLFW.GLFW_KEY_LEFT_ALT -> HIDKeyboard.HID_KEY_LEFTALT;
            case GLFW.GLFW_KEY_LEFT_SUPER -> HIDKeyboard.HID_KEY_LEFTMETA;
            case GLFW.GLFW_KEY_RIGHT_CONTROL -> HIDKeyboard.HID_KEY_RIGHTCTRL;
            case GLFW.GLFW_KEY_RIGHT_SHIFT -> HIDKeyboard.HID_KEY_RIGHTSHIFT;
            case GLFW.GLFW_KEY_RIGHT_ALT -> HIDKeyboard.HID_KEY_RIGHTALT;
            case GLFW.GLFW_KEY_RIGHT_SUPER -> HIDKeyboard.HID_KEY_RIGHTMETA;
            case GLFW.GLFW_KEY_MENU -> HIDKeyboard.HID_KEY_MENU;

            default -> HIDKeyboard.HID_KEY_NONE;
        };
    }
}
