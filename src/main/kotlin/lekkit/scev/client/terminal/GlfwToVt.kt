/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.terminal

import org.lwjgl.glfw.GLFW

/**
 * Encode a GLFW key event as the byte sequence a (mostly) xterm-
 * compatible terminal would put on its TX line. Returns `null` for
 * keys we don't handle — let the caller fall through to charTyped for
 * printable text.
 *
 * Only the keys with a definite VT/xterm encoding live here. Letters,
 * digits, punctuation, etc. are all delivered through GLFW's
 * charTyped path, which already handles layout / IME / dead-keys
 * properly. Don't try to synthesise printable bytes from raw GLFW
 * keycodes — that's how you end up with broken non-US layouts.
 */
internal object GlfwToVt {

    // Spelled with \u so the source line stays visible — the literal
    // 0x1B was here originally, then got stripped by an editor/save
    // pass and arrow keys sent "[A" instead of "\x1b[A".
    private const val ESC = ""

    fun encode(keyCode: Int, modifiers: Int): ByteArray? {
        val ctrl = (modifiers and GLFW.GLFW_MOD_CONTROL) != 0
        val alt = (modifiers and GLFW.GLFW_MOD_ALT) != 0

        // Plain control keys.
        when (keyCode) {
            GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> return byteArrayOf('\r'.code.toByte())
            GLFW.GLFW_KEY_BACKSPACE -> return byteArrayOf(0x7F)        // DEL — what real terminals send
            GLFW.GLFW_KEY_TAB       -> return byteArrayOf('\t'.code.toByte())
            GLFW.GLFW_KEY_ESCAPE    -> return byteArrayOf(0x1B)        // raw ESC; let caller decide if Esc closes the GUI
            GLFW.GLFW_KEY_DELETE    -> return "$ESC[3~".toByteArray()
            GLFW.GLFW_KEY_INSERT    -> return "$ESC[2~".toByteArray()
            GLFW.GLFW_KEY_HOME      -> return "$ESC[H".toByteArray()
            GLFW.GLFW_KEY_END       -> return "$ESC[F".toByteArray()
            GLFW.GLFW_KEY_PAGE_UP   -> return "$ESC[5~".toByteArray()
            GLFW.GLFW_KEY_PAGE_DOWN -> return "$ESC[6~".toByteArray()
            GLFW.GLFW_KEY_UP        -> return "$ESC[A".toByteArray()
            GLFW.GLFW_KEY_DOWN      -> return "$ESC[B".toByteArray()
            GLFW.GLFW_KEY_RIGHT     -> return "$ESC[C".toByteArray()
            GLFW.GLFW_KEY_LEFT      -> return "$ESC[D".toByteArray()
            GLFW.GLFW_KEY_F1        -> return "${ESC}OP".toByteArray()
            GLFW.GLFW_KEY_F2        -> return "${ESC}OQ".toByteArray()
            GLFW.GLFW_KEY_F3        -> return "${ESC}OR".toByteArray()
            GLFW.GLFW_KEY_F4        -> return "${ESC}OS".toByteArray()
            GLFW.GLFW_KEY_F5        -> return "$ESC[15~".toByteArray()
            GLFW.GLFW_KEY_F6        -> return "$ESC[17~".toByteArray()
            GLFW.GLFW_KEY_F7        -> return "$ESC[18~".toByteArray()
            GLFW.GLFW_KEY_F8        -> return "$ESC[19~".toByteArray()
            GLFW.GLFW_KEY_F9        -> return "$ESC[20~".toByteArray()
            GLFW.GLFW_KEY_F10       -> return "$ESC[21~".toByteArray()
            GLFW.GLFW_KEY_F11       -> return "$ESC[23~".toByteArray()
            GLFW.GLFW_KEY_F12       -> return "$ESC[24~".toByteArray()
        }

        // Ctrl + letter → control byte. GLFW's letter keycodes match
        // ASCII upper-case (KEY_A == 'A' == 65). Mask down to 0x1F to
        // get the control char (Ctrl-A → 0x01, Ctrl-C → 0x03, etc.).
        if (ctrl && keyCode in GLFW.GLFW_KEY_A..GLFW.GLFW_KEY_Z) {
            return byteArrayOf((keyCode - GLFW.GLFW_KEY_A + 1).toByte())
        }

        // Alt + character: prefix with ESC. Defer the actual character
        // resolution to charTyped so we get layout-correct bytes; this
        // helper just returns null and the caller emits ESC then waits
        // for the matching charTyped. Tracking that here would require
        // shared state with the screen; not worth it for a v1.
        if (alt) return null

        return null
    }
}
