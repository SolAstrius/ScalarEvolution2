/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.terminal.setup

import org.lwjgl.glfw.GLFW

/**
 * Pure key-event → [SetupModel] mutation. No I/O, no networking, no
 * mlterm calls — just transforms one model into the next based on what
 * the user pressed. Whoever owns the model decides what to do with the
 * result (broadcast the persistent half over the network, re-render
 * the page locally, etc.).
 *
 * Why a returned [Action] rather than mutating in place: persistent
 * changes need to round-trip through the server (the BE is server-side
 * authoritative) while view changes are local. Splitting them at the
 * controller boundary keeps that policy out of the Screen and avoids
 * accidentally sending every cursor-key tap over the wire.
 */
internal object SetupController {

    /** A single user action's outcome. The model is always replaced
     *  (functional update); whether to broadcast the persistent slice
     *  is the caller's choice based on [persistentChanged]. */
    data class Action(
        val next: SetupModel,
        /** True if [next.persistent] differs from the input model's
         *  persistent state. Caller should send a [SetupEditPayload]
         *  to the server and not apply optimistically — let the server
         *  echo back the new state via [SetupSyncPayload] for consistency
         *  across multi-viewer rooms. */
        val persistentChanged: Boolean,
        /** True if Setup mode should be exited entirely (the screen
         *  should drop back to live terminal output). */
        val exitSetup: Boolean = false,
        /** True if the screen should request a full save/recall/reset
         *  cycle. We don't have separate "saved vs. live" state since
         *  the persistent slice already auto-saves on every edit, so
         *  Save is a no-op visual flash and Recall is also a no-op for
         *  now — wiring left for completeness with real DEC behavior. */
        val flashWait: Boolean = false,
    )

    /**
     * Returns [Action] only when the controller actually consumed the
     * key. Otherwise returns null and the caller should fall through
     * (e.g. to Minecraft's normal screen handling, so ESC outside
     * Setup mode still closes the GUI). Modeled after Screen.keyPressed
     * which the call site already implements.
     */
    fun keyPressed(m: SetupModel, keyCode: Int, modifiers: Int): Action? {
        // Answerback edit gets first refusal — it has to grab a wide
        // range of keys (delimiter, message chars, ESC to cancel) and
        // we don't want, say, the Setup-A '5' shortcut firing while
        // the user types a literal '5' into their answerback message.
        if (m.view.answerbackEditing) {
            return handleAnswerbackKey(m, keyCode, modifiers)
        }

        val shift = (modifiers and GLFW.GLFW_MOD_SHIFT) != 0

        // F3 — exit Setup (matches the "press SET-UP again to exit"
        // line on the chrome banner; F3 was the SET-UP key on every
        // VT220+ keyboard, and we use it across all kinds).
        if (keyCode == GLFW.GLFW_KEY_F3) {
            return Action(m, persistentChanged = false, exitSetup = true)
        }

        // ESC also exits — gentler-than-DEC but matches modern conventions.
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            return Action(m, persistentChanged = false, exitSetup = true)
        }

        // Page navigation: ← → walks the page list.
        if (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT) {
            val pages = SetupModel.Page.entries
            val cur = pages.indexOf(m.view.page)
            val next = if (keyCode == GLFW.GLFW_KEY_LEFT) {
                pages[(cur - 1 + pages.size) % pages.size]
            } else {
                pages[(cur + 1) % pages.size]
            }
            return Action(
                m.copy(view = m.view.copy(page = next, focus = 0)),
                persistentChanged = false,
            )
        }

        // Per-page handling.
        return when (m.view.page) {
            SetupModel.Page.SETUP_A -> handleSetupA(m, keyCode, shift)
            SetupModel.Page.SETUP_B -> handleSetupB(m, keyCode, shift)
            SetupModel.Page.CRT_FX  -> handleCrtFx(m, keyCode)
            SetupModel.Page.MOD     -> handleMod(m, keyCode)
        }
    }

    /** Plain printable-character input. Only meaningful while
     *  answerback edit is active; everywhere else Setup ignores
     *  charTyped. */
    fun charTyped(m: SetupModel, ch: Char): Action? {
        if (!m.view.answerbackEditing) return null
        return appendAnswerbackChar(m, ch)
    }

    /* ---------- per-page key handling ---------------------------------- */

    private fun handleSetupA(m: SetupModel, keyCode: Int, shift: Boolean): Action? {
        // '5' (no shift) — switch to SET-UP B. Period-correct VT100 binding.
        if (keyCode == GLFW.GLFW_KEY_5 && !shift) {
            return Action(
                m.copy(view = m.view.copy(page = SetupModel.Page.SETUP_B, focus = 0)),
                persistentChanged = false,
            )
        }
        // Cursor movement on the ruler — adjusts where the cursor block
        // sits and (with Tab key handling, eventually) where set/clear
        // tab acts. Stub for now: just advance the column.
        if (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT) {
            // already consumed by page navigation above; unreachable
            return null
        }
        return null
    }

    private fun handleSetupB(m: SetupModel, keyCode: Int, shift: Boolean): Action? {
        // '5' (no shift) — switch back to SET-UP A.
        if (keyCode == GLFW.GLFW_KEY_5 && !shift) {
            return Action(
                m.copy(view = m.view.copy(page = SetupModel.Page.SETUP_A, focus = 0)),
                persistentChanged = false,
            )
        }
        // Shift+A — start answerback edit. Per UG, the cursor moves to
        // row 23 col 1 and "A=" appears; the next keystroke is taken
        // as the delimiter character.
        if (keyCode == GLFW.GLFW_KEY_A && shift) {
            return Action(
                m.copy(
                    view = m.view.copy(
                        answerbackEditing = true,
                        answerbackEditBuf = "",
                        answerbackDelim = "",
                    )
                ),
                persistentChanged = false,
            )
        }
        return null
    }

    private fun handleCrtFx(m: SetupModel, keyCode: Int): Action? {
        // 4 fields: BRIGHTNESS, PHOSPHOR, SCANLINES, COL132.
        when (keyCode) {
            GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN -> {
                val nFields = 4
                val delta = if (keyCode == GLFW.GLFW_KEY_UP) -1 else 1
                val nf = ((m.view.focus + delta) % nFields + nFields) % nFields
                return Action(
                    m.copy(view = m.view.copy(focus = nf)),
                    persistentChanged = false,
                )
            }
            GLFW.GLFW_KEY_KP_ADD, GLFW.GLFW_KEY_EQUAL -> {
                return adjustCrtField(m, +1)
            }
            GLFW.GLFW_KEY_KP_SUBTRACT, GLFW.GLFW_KEY_MINUS -> {
                return adjustCrtField(m, -1)
            }
            GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                // Enter cycles enums (PHOSPHOR) and toggles booleans
                // (COL132). Numeric fields ignore Enter.
                return enterOnCrtField(m)
            }
        }
        return null
    }

    private fun handleMod(m: SetupModel, keyCode: Int): Action? {
        // Only one editable field (SCROLLBACK). Up/Down still cycles
        // for forward-compat when more fields land.
        when (keyCode) {
            GLFW.GLFW_KEY_KP_ADD, GLFW.GLFW_KEY_EQUAL -> {
                return Action(
                    m.copy(persistent = m.persistent.copy(
                        scrollback = (m.persistent.scrollback * 2).coerceAtMost(65536)
                    )),
                    persistentChanged = true,
                )
            }
            GLFW.GLFW_KEY_KP_SUBTRACT, GLFW.GLFW_KEY_MINUS -> {
                return Action(
                    m.copy(persistent = m.persistent.copy(
                        scrollback = (m.persistent.scrollback / 2).coerceAtLeast(64)
                    )),
                    persistentChanged = true,
                )
            }
        }
        return null
    }

    /* ---------- CRT FX field helpers ----------------------------------- */

    private fun adjustCrtField(m: SetupModel, delta: Int): Action {
        val p = m.persistent
        val np = when (m.view.focus) {
            0 -> p.copy(intensity = (p.intensity + delta).coerceIn(0, 15))
            1 -> p.copy(phosphor = cyclePhosphor(p.phosphor, delta))
            2 -> p.copy(scanlines = (p.scanlines + delta * 5).coerceIn(0, 50))
            3 -> p.copy(col132 = !p.col132)
            else -> p
        }
        return Action(m.copy(persistent = np), persistentChanged = np != p)
    }

    private fun enterOnCrtField(m: SetupModel): Action {
        return when (m.view.focus) {
            1 -> Action(
                m.copy(persistent = m.persistent.copy(
                    phosphor = cyclePhosphor(m.persistent.phosphor, +1)
                )),
                persistentChanged = true,
            )
            3 -> Action(
                m.copy(persistent = m.persistent.copy(col132 = !m.persistent.col132)),
                persistentChanged = true,
            )
            else -> Action(m, persistentChanged = false)
        }
    }

    private fun cyclePhosphor(p: SetupModel.Phosphor, delta: Int): SetupModel.Phosphor {
        val all = SetupModel.Phosphor.entries
        val idx = ((all.indexOf(p) + delta) % all.size + all.size) % all.size
        return all[idx]
    }

    /* ---------- Answerback edit ---------------------------------------- */

    /**
     * Answerback editing is a tiny three-state machine on top of the
     * model:
     *   1. just entered (delim="", buf=""): the next keystroke captures
     *      the delimiter character.
     *   2. delim captured, buf accumulating: each keystroke appends to
     *      buf, until either max length (20) or the delim is typed
     *      again.
     *   3. delim re-typed: edit ends, buf is committed to
     *      [SetupModel.PersistentState.answerback], view returns to
     *      Setup B normally.
     *
     * ESC at any point cancels the edit without committing.
     */
    private fun handleAnswerbackKey(m: SetupModel, keyCode: Int, modifiers: Int): Action? {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            // Cancel: revert view, leave persistent answerback unchanged.
            return Action(
                m.copy(view = m.view.copy(
                    answerbackEditing = false,
                    answerbackEditBuf = "",
                    answerbackDelim = "",
                )),
                persistentChanged = false,
            )
        }
        // Backspace removes the last char of the message buffer (after
        // the delimiter is already captured). DEC's manual says "the
        // only way to fix a typo is restart with the delimiter again",
        // but our forgiving variant is friendlier.
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
            val buf = m.view.answerbackEditBuf
            if (buf.isNotEmpty()) {
                return Action(
                    m.copy(view = m.view.copy(answerbackEditBuf = buf.dropLast(1))),
                    persistentChanged = false,
                )
            }
            return Action(m, persistentChanged = false)
        }
        return null   // let charTyped handle printable input
    }

    private fun appendAnswerbackChar(m: SetupModel, ch: Char): Action {
        val v = m.view
        // Phase 1: capture delimiter.
        if (v.answerbackDelim.isEmpty()) {
            return Action(
                m.copy(view = v.copy(answerbackDelim = ch.toString())),
                persistentChanged = false,
            )
        }
        // Phase 3: closing delimiter — commit.
        if (ch.toString() == v.answerbackDelim) {
            return Action(
                m.copy(
                    persistent = m.persistent.copy(answerback = v.answerbackEditBuf),
                    view = v.copy(
                        answerbackEditing = false,
                        answerbackEditBuf = "",
                        answerbackDelim = "",
                    ),
                ),
                persistentChanged = m.persistent.answerback != v.answerbackEditBuf,
            )
        }
        // Phase 2: append, bounded by the manual's 20-char cap.
        if (v.answerbackEditBuf.length >= 20) {
            return Action(m, persistentChanged = false)
        }
        return Action(
            m.copy(view = v.copy(answerbackEditBuf = v.answerbackEditBuf + ch)),
            persistentChanged = false,
        )
    }
}
