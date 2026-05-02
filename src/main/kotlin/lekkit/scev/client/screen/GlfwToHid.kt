/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.screen

import lekkit.rvvm.HIDKeyboard
import org.lwjgl.glfw.GLFW

/**
 * Maps GLFW key codes (what Minecraft 1.21 passes to `Screen.keyPressed`)
 * to USB HID usage IDs (what [HIDKeyboard] expects).
 *
 * The original 1.7.10 mod had a similar mapping against LWJGL 2.x Keyboard
 * constants; this port regenerates it against `org.lwjgl.glfw.GLFW`.
 *
 * Only a reasonable subset is mapped — letters, numbers, function keys,
 * modifiers, arrows, editing keys, and a handful of punctuation. Unmapped
 * keys return [HIDKeyboard.HID_KEY_NONE] (0); the server handler ignores it.
 */
object GlfwToHid {
    /** Return the HID usage id for a GLFW key, or [HIDKeyboard.HID_KEY_NONE] if unmapped. */
    @JvmStatic fun map(glfwKey: Int): Byte {
        // Letters
        if (glfwKey in GLFW.GLFW_KEY_A..GLFW.GLFW_KEY_Z) {
            return (HIDKeyboard.HID_KEY_A + (glfwKey - GLFW.GLFW_KEY_A)).toByte()
        }
        // Top-row numbers — GLFW uses ASCII '0'=48 ... '9'=57.
        // HID: 1..9 at 0x1e..0x26, 0 at 0x27.
        if (glfwKey == GLFW.GLFW_KEY_0) return HIDKeyboard.HID_KEY_0
        if (glfwKey in GLFW.GLFW_KEY_1..GLFW.GLFW_KEY_9) {
            return (HIDKeyboard.HID_KEY_1 + (glfwKey - GLFW.GLFW_KEY_1)).toByte()
        }
        // Function keys
        if (glfwKey in GLFW.GLFW_KEY_F1..GLFW.GLFW_KEY_F12) {
            return (HIDKeyboard.HID_KEY_F1 + (glfwKey - GLFW.GLFW_KEY_F1)).toByte()
        }
        if (glfwKey in GLFW.GLFW_KEY_F13..GLFW.GLFW_KEY_F24) {
            return (HIDKeyboard.HID_KEY_F13 + (glfwKey - GLFW.GLFW_KEY_F13)).toByte()
        }
        // Keypad 0..9
        if (glfwKey == GLFW.GLFW_KEY_KP_0) return HIDKeyboard.HID_KEY_KP0
        if (glfwKey in GLFW.GLFW_KEY_KP_1..GLFW.GLFW_KEY_KP_9) {
            return (HIDKeyboard.HID_KEY_KP1 + (glfwKey - GLFW.GLFW_KEY_KP_1)).toByte()
        }

        return when (glfwKey) {
            // Control
            GLFW.GLFW_KEY_ENTER          -> HIDKeyboard.HID_KEY_ENTER
            GLFW.GLFW_KEY_ESCAPE         -> HIDKeyboard.HID_KEY_ESC
            GLFW.GLFW_KEY_BACKSPACE      -> HIDKeyboard.HID_KEY_BACKSPACE
            GLFW.GLFW_KEY_TAB            -> HIDKeyboard.HID_KEY_TAB
            GLFW.GLFW_KEY_SPACE          -> HIDKeyboard.HID_KEY_SPACE
            GLFW.GLFW_KEY_MINUS          -> HIDKeyboard.HID_KEY_MINUS
            GLFW.GLFW_KEY_EQUAL          -> HIDKeyboard.HID_KEY_EQUAL
            GLFW.GLFW_KEY_LEFT_BRACKET   -> HIDKeyboard.HID_KEY_LEFTBRACE
            GLFW.GLFW_KEY_RIGHT_BRACKET  -> HIDKeyboard.HID_KEY_RIGHTBRACE
            GLFW.GLFW_KEY_BACKSLASH      -> HIDKeyboard.HID_KEY_BACKSLASH
            GLFW.GLFW_KEY_SEMICOLON      -> HIDKeyboard.HID_KEY_SEMICOLON
            GLFW.GLFW_KEY_APOSTROPHE     -> HIDKeyboard.HID_KEY_APOSTROPHE
            GLFW.GLFW_KEY_GRAVE_ACCENT   -> HIDKeyboard.HID_KEY_GRAVE
            GLFW.GLFW_KEY_COMMA          -> HIDKeyboard.HID_KEY_COMMA
            GLFW.GLFW_KEY_PERIOD         -> HIDKeyboard.HID_KEY_DOT
            GLFW.GLFW_KEY_SLASH          -> HIDKeyboard.HID_KEY_SLASH
            GLFW.GLFW_KEY_CAPS_LOCK      -> HIDKeyboard.HID_KEY_CAPSLOCK

            // Editing
            GLFW.GLFW_KEY_PRINT_SCREEN   -> HIDKeyboard.HID_KEY_SYSRQ
            GLFW.GLFW_KEY_SCROLL_LOCK    -> HIDKeyboard.HID_KEY_SCROLLLOCK
            GLFW.GLFW_KEY_PAUSE          -> HIDKeyboard.HID_KEY_PAUSE
            GLFW.GLFW_KEY_INSERT         -> HIDKeyboard.HID_KEY_INSERT
            GLFW.GLFW_KEY_HOME           -> HIDKeyboard.HID_KEY_HOME
            GLFW.GLFW_KEY_PAGE_UP        -> HIDKeyboard.HID_KEY_PAGEUP
            GLFW.GLFW_KEY_DELETE         -> HIDKeyboard.HID_KEY_DELETE
            GLFW.GLFW_KEY_END            -> HIDKeyboard.HID_KEY_END
            GLFW.GLFW_KEY_PAGE_DOWN      -> HIDKeyboard.HID_KEY_PAGEDOWN
            GLFW.GLFW_KEY_RIGHT          -> HIDKeyboard.HID_KEY_RIGHT
            GLFW.GLFW_KEY_LEFT           -> HIDKeyboard.HID_KEY_LEFT
            GLFW.GLFW_KEY_DOWN           -> HIDKeyboard.HID_KEY_DOWN
            GLFW.GLFW_KEY_UP             -> HIDKeyboard.HID_KEY_UP

            // Keypad
            GLFW.GLFW_KEY_NUM_LOCK       -> HIDKeyboard.HID_KEY_NUMLOCK
            GLFW.GLFW_KEY_KP_DIVIDE      -> HIDKeyboard.HID_KEY_KPSLASH
            GLFW.GLFW_KEY_KP_MULTIPLY    -> HIDKeyboard.HID_KEY_KPASTERISK
            GLFW.GLFW_KEY_KP_SUBTRACT    -> HIDKeyboard.HID_KEY_KPMINUS
            GLFW.GLFW_KEY_KP_ADD         -> HIDKeyboard.HID_KEY_KPPLUS
            GLFW.GLFW_KEY_KP_ENTER       -> HIDKeyboard.HID_KEY_KPENTER
            GLFW.GLFW_KEY_KP_DECIMAL     -> HIDKeyboard.HID_KEY_KPDOT
            GLFW.GLFW_KEY_KP_EQUAL       -> HIDKeyboard.HID_KEY_KPEQUAL

            // Modifiers
            GLFW.GLFW_KEY_LEFT_CONTROL   -> HIDKeyboard.HID_KEY_LEFTCTRL
            GLFW.GLFW_KEY_LEFT_SHIFT     -> HIDKeyboard.HID_KEY_LEFTSHIFT
            GLFW.GLFW_KEY_LEFT_ALT       -> HIDKeyboard.HID_KEY_LEFTALT
            GLFW.GLFW_KEY_LEFT_SUPER     -> HIDKeyboard.HID_KEY_LEFTMETA
            GLFW.GLFW_KEY_RIGHT_CONTROL  -> HIDKeyboard.HID_KEY_RIGHTCTRL
            GLFW.GLFW_KEY_RIGHT_SHIFT    -> HIDKeyboard.HID_KEY_RIGHTSHIFT
            GLFW.GLFW_KEY_RIGHT_ALT      -> HIDKeyboard.HID_KEY_RIGHTALT
            GLFW.GLFW_KEY_RIGHT_SUPER    -> HIDKeyboard.HID_KEY_RIGHTMETA
            GLFW.GLFW_KEY_MENU           -> HIDKeyboard.HID_KEY_MENU

            else -> HIDKeyboard.HID_KEY_NONE
        }
    }
}
