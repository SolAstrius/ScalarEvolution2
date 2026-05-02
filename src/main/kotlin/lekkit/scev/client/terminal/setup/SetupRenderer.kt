/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.terminal.setup

import lekkit.scev.client.terminal.setup.SetupBytes.CSI
import lekkit.scev.client.terminal.setup.SetupBytes.autowrapOff
import lekkit.scev.client.terminal.setup.SetupBytes.cls
import lekkit.scev.client.terminal.setup.SetupBytes.cup
import lekkit.scev.client.terminal.setup.SetupBytes.dhBot
import lekkit.scev.client.terminal.setup.SetupBytes.dhTop
import lekkit.scev.client.terminal.setup.SetupBytes.dw
import lekkit.scev.client.terminal.setup.SetupBytes.hideCursor
import lekkit.scev.client.terminal.setup.SetupBytes.reset
import lekkit.scev.client.terminal.setup.SetupBytes.sgr
import lekkit.scev.client.terminal.setup.SetupBytes.showCursor

/**
 * Render a [SetupModel] page to a VT byte stream that, when fed to
 * mlterm via [lekkit.scev.client.terminal.MltermBackend.feed], paints
 * the period-correct DEC Setup interface for that kind / page.
 *
 * Lifted verbatim from the Python prototype that was iterated against
 * `mlterm-fb-dumper` until each page matched the figures in the VT100
 * User Guide (Aug 78 / Jan 79, EK-VT100-UG-001/002, figs 1-4 / 1-5 /
 * 1-6) and the screen-RAM layout described in the VT100 Technical
 * Manual §4.7.x. See `tools/dec-refs/proto/setup_render.py` (gone
 * after this port) for the reference rendering tool.
 *
 * Future eras (VT220 / VT340 / VT420 / VT520) will add their own pages
 * and their own shell wrapper — VT220+ Setup is multi-pane with arrow-
 * key navigation, color highlights from VT340 onward, etc. The single-
 * file structure here is intentional: one place to read all the
 * layouts at a glance. Resist the temptation to abstract until at
 * least three eras are wired in and a real shape emerges.
 */
internal object SetupRenderer {

    /**
     * Public entry: pick the right per-page rendering function based on
     * the active page in [m.view]. Returns a UTF-8 byte stream ready to
     * feed into mlterm. Output is self-contained — clears the screen,
     * paints the page, leaves cursor parked off-screen. Calling twice
     * in a row is idempotent.
     */
    fun render(m: SetupModel, info: Info = Info()): ByteArray = when (m.view.page) {
        SetupModel.Page.SETUP_A -> renderSetupA(m)
        SetupModel.Page.SETUP_B -> renderSetupB(m)
        SetupModel.Page.CRT_FX  -> renderCrtFx(m)
        SetupModel.Page.MOD     -> renderMod(m, info)
    }

    /** Display-only metadata for the MOD page — the bound machine UUID
     *  (read-only, set by the bus controller) and the TERM string sent
     *  to the guest (derived from the terminal kind). Defaulted so test
     *  callers can ignore. */
    data class Info(val boundUuid: String = NULL_UUID, val term: String = "vt100")

    /* ---------- Shell --------------------------------------------------- */

    /**
     * The chrome every Setup page wears: row 1+2 = double-height title,
     * row 3 = double-width reverse-underlined "TO EXIT PRESS \"SET-UP\""
     * banner. Matches the photo in the user guide pixel-for-pixel
     * (within the limits of the cozette font).
     *
     * Always emitted as the first thing on a page, including the screen
     * clear + cursor hide + autowrap-off, so callers can append page-
     * specific content without worrying about leftover state.
     */
    private fun shellTop(title: String): String = buildString {
        append(reset()); append(cls()); append(hideCursor()); append(autowrapOff())
        append(cup(1, 1)); append(dhTop(title))
        append(cup(2, 1)); append(dhBot(title))
        append(cup(3, 1)); append(dw(sgr(7, 4) + "TO EXIT PRESS \"SET-UP\"" + reset()))
    }

    /* ---------- SET-UP A ----------------------------------------------- */

    /**
     * Tab-stops + decade ruler. Per fig 1-4 and tm §4.7.x:
     *  - Row 23: free line. Carries the cursor and a "T" letter above
     *    each set tab stop.
     *  - Row 24: ruler — `12345` plain, `67890` reverse-video, repeated
     *    eight times. We emit seven full decades (70 chars) and a
     *    truncated eighth (`12345` + reverse `6789`, 9 chars) for a
     *    79-char total: writing the 80th column triggers an autowrap
     *    that scrolls the screen even with DECAWM off in some mlterm
     *    versions, so we just don't write there. The visual difference
     *    versus a real 80-column ruler is one missing reverse `0` at
     *    the right edge — invisible in normal use.
     */
    private fun renderSetupA(m: SetupModel): ByteArray = buildString {
        append(shellTop("SET-UP A"))

        // Row 23: T markers.
        val line23 = CharArray(80) { ' ' }
        for (c in m.persistent.tabs) {
            if (c in 1..80) line23[c - 1] = 'T'
        }
        append(cup(23, 1))
        append(String(line23, 0, 79))

        // Row 24: ruler.
        append(cup(24, 1))
        repeat(7) {
            append(reset()); append("12345"); append(sgr(7)); append("67890")
        }
        append(reset()); append("12345"); append(sgr(7)); append("6789")
        append(reset())

        // Cursor block at the user's column (defaults to col 1, matches
        // the manual photo).
        val cc = m.view.cursorCol.coerceIn(1, 80)
        append(cup(23, cc)); append(sgr(7)); append(" "); append(reset())
    }.toByteArray(Charsets.UTF_8)

    /* ---------- SET-UP B ----------------------------------------------- */

    /**
     * Switchpacks + speed strip. Per fig 1-5 / 1-6 and tm §4.7.x.
     *
     * Layout of row 24:
     *   `1 ▮▮▮▮  2 ▮▮▮▮  3 ▮▮▮▮  4 ▮▮▮▮  T SPEED 9600  R SPEED 9600`
     *
     * Each toggle indicator is a one-cell glyph:
     *  - filled (bit set) → reverse-video space
     *  - empty (bit clear) → hollow-square `□` Unicode glyph
     *
     * MSB of each switchpack byte is the leftmost indicator, matching
     * the manual's figure 1-6 reading order.
     *
     * Row 23 is the "free line" per the technical manual: normally just
     * a cursor block, but in answerback-edit mode the message is
     * displayed inline starting at column 1 as `A=<delim><msg>` with
     * control chars rendered as `•`. Same place real VT100 ROM put it.
     */
    private fun renderSetupB(m: SetupModel): ByteArray = buildString {
        append(shellTop("SET-UP B"))

        // Row 23: cursor or answerback overlay.
        if (m.view.answerbackEditing) {
            val sanitized = m.view.answerbackEditBuf.map {
                if (it.code < 0x20 || it.code == 0x7f) '•' else it
            }.joinToString("")
            val line23 = "A=" + m.view.answerbackDelim + sanitized
            append(cup(23, 1))
            append(line23.take(79))
            append(cup(23, line23.length + 1))
            append(sgr(7)); append(" "); append(reset())
        } else {
            val cc = m.view.cursorCol.coerceIn(1, 80)
            append(cup(23, cc)); append(sgr(7)); append(" "); append(reset())
        }

        // Row 24: switchpacks + speeds.
        append(cup(24, 1))
        renderSwitchpack(this, "1", m.persistent.sw1)
        append("  ")
        renderSwitchpack(this, "2", m.persistent.sw2)
        append("  ")
        renderSwitchpack(this, "3", m.persistent.sw3)
        append("  ")
        renderSwitchpack(this, "4", m.persistent.sw4)
        if (m.persistent.sw5Present) {
            append("  ")
            renderSwitchpack(this, "5", m.persistent.sw5)
        }
        append("  T SPEED ${m.persistent.tSpeed}  R SPEED ${m.persistent.rSpeed}")
    }.toByteArray(Charsets.UTF_8)

    private fun renderSwitchpack(out: StringBuilder, label: String, bits: Byte) {
        out.append(label); out.append(' ')
        // MSB-first: bit 3 (8) is leftmost, bit 0 (1) is rightmost.
        for (i in 3 downTo 0) {
            val set = (bits.toInt() shr i) and 1 == 1
            if (set) {
                out.append(sgr(7)); out.append(' '); out.append(reset())
            } else {
                out.append('□')
            }
        }
    }

    /* ---------- CRT FX ------------------------------------------------- */

    /**
     * Brightness / phosphor / scanlines / 80-132 column toggle. VT100-era
     * styling: monochrome only (no SGR color), focus highlighted with
     * reverse video, hint strip at row 24 in DW + reverse.
     *
     * Field index for [SetupModel.ViewState.focus] (matters for the
     * controller's arrow-key handling):
     *   0 = BRIGHTNESS (intensity 0..15)
     *   1 = PHOSPHOR   (cycle GREEN → AMBER → WHITE → GREEN)
     *   2 = SCANLINES  (0..50 percent)
     *   3 = 80/132 COLS (boolean)
     */
    private fun renderCrtFx(m: SetupModel): ByteArray = buildString {
        append(shellTop("CRT FX"))

        val rows = arrayOf(
            "BRIGHTNESS"   to "%2d / 15".format(m.persistent.intensity),
            "PHOSPHOR"     to m.persistent.phosphor.name,
            "SCANLINES"    to "%3d %%".format(m.persistent.scanlines),
            "80/132 COLS"  to if (m.persistent.col132) "132" else " 80",
        )
        for ((i, row) in rows.withIndex()) {
            val (label, value) = row
            val focused = m.view.focus == i
            append(cup(6 + i * 2, 4))
            if (focused) append(sgr(7))
            append("%-14s %s".format(label, value))
            append(reset())
        }

        append(cup(24, 1))
        append(dw(sgr(7) + "  +/- ADJUST   ARROWS MOVE   ESC EXIT  " + reset()))
    }.toByteArray(Charsets.UTF_8)

    /* ---------- MOD ---------------------------------------------------- */

    /**
     * Minecraft-only metadata. Bound machine UUID and TERM are read-only
     * (the bus stamps the UUID, TERM is derived from kind); only
     * scrollback is editable.
     *
     * Field index for the controller (only the editable rows count):
     *   0 = SCROLLBACK
     */
    private fun renderMod(m: SetupModel, info: Info): ByteArray = buildString {
        append(shellTop("MOD"))

        // Read-only header block.
        append(cup(6, 4)); append("%-14s %s".format("BOUND UUID", info.boundUuid))
        append(cup(8, 4)); append("%-14s %s".format("TERM", info.term))

        // Editable fields.
        val focused = m.view.focus == 0
        append(cup(11, 4))
        if (focused) append(sgr(7))
        append("%-14s %d".format("SCROLLBACK", m.persistent.scrollback))
        append(reset())

        append(cup(24, 1))
        append(dw(sgr(7) + "  +/- ADJUST   ARROWS MOVE   ESC EXIT  " + reset()))
    }.toByteArray(Charsets.UTF_8)

    private const val NULL_UUID = "00000000-0000-0000-0000-000000000000"
}
