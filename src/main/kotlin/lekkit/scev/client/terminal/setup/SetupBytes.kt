/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.terminal.setup

/**
 * Tiny helpers for emitting VT100-era escape sequences as Strings.
 * Used by [SetupRenderer] to assemble the Setup-page byte streams that
 * get fed into [lekkit.scev.client.terminal.MltermBackend.feed]. The
 * helpers are pure string-building — encoding to bytes happens once at
 * the end of each renderer with `.toByteArray(Charsets.UTF_8)`.
 *
 * Why strings and not bytes throughout: the layout work (tab markers,
 * decade rulers, padded labels, answerback overlay) is much easier to
 * read with String concat / interpolation than with ByteArray builders.
 * The only non-ASCII characters used are the bullet `•` (control-char
 * indicator in answerback edit mode) and the hollow box `□` (unset
 * switchpack indicator) — both single Unicode codepoints, fine in UTF-8.
 *
 * Period-correctness: every sequence here exists in the actual VT100
 * spec — DECDHL (`ESC#3`/`ESC#4`), DECDWL (`ESC#6`), CSI cursor moves,
 * CSI SGR for reverse/underline, DECTCEM hide-cursor, DECAWM autowrap.
 * No xterm extensions are used in this file.
 */
internal object SetupBytes {

    /** ASCII Escape, a real escape character (not the `\e` glyph). */
    const val ESC: String = ""

    /** Control Sequence Introducer — the prefix for almost every CSI verb. */
    const val CSI: String = ESC + "["

    /** Cursor position. 1-based row + column, per VT100 manual. */
    fun cup(row: Int, col: Int): String = "${CSI}${row};${col}H"

    /** Select Graphic Rendition. Pass any number of SGR parameters; they
     *  are joined with `;` per the spec. `sgr()` with no args resets. */
    fun sgr(vararg codes: Int): String =
        if (codes.isEmpty()) "${CSI}0m"
        else "${CSI}${codes.joinToString(";")}m"

    /** DECDHL top half of a double-height line. The matching bottom half
     *  must be issued with [dhBot] on the next row, with the same text,
     *  for the line to render as a single tall character. Without the
     *  bottom-half pair, the row renders as double-width single-height. */
    fun dhTop(text: String): String = ESC + "#3" + text

    /** DECDHL bottom half — see [dhTop]. */
    fun dhBot(text: String): String = ESC + "#4" + text

    /** DECDWL — single-height, double-width. */
    fun dw(text: String): String = ESC + "#6" + text

    /** SGR reset — clears reverse, underline, bold, color, etc. */
    fun reset(): String = "${CSI}0m"

    /** RIS (full reset, `ESC c`) + ED 2 + per-row 80-char space
     *  overwrite + CUP home.
     *
     *  RIS clears parser-side state (DECDHL line attrs, DECSTBM
     *  scroll region, DECOM, alt screen, etc.). ED 2 erases all
     *  cells. But mlterm has a render-side optimisation that skips
     *  redrawing lines whose model didn't change "enough" — and
     *  because RIS resets line attrs WITHOUT marking the line dirty
     *  in a way that survives the render-skip path, a row that had
     *  DECDHL set the previous frame keeps its old pixels even
     *  though the model says it's empty single-height now.
     *
     *  Workaround: explicitly write 80 spaces to every row before
     *  drawing anything. The space content differs from whatever
     *  was there (kernel log text), so mlterm has no excuse to skip
     *  the redraw. ~4 KB of extra bytes per Setup repaint —
     *  unmeasurable hit at 60 fps.
     */
    fun cls(): String = buildString {
        append("${ESC}c")           // RIS — full reset
        append("${CSI}2J")          // ED 2 — sanity, in case RIS leaves anything
        // Per-row space-fill: space is the SGR-bg cell, so this also
        // re-establishes "every cell is bg" if any inverse/colored
        // cells survived the reset.
        val spaces = " ".repeat(80)
        for (row in 1..24) {
            append("${CSI}${row};1H")
            append(spaces)
        }
        append("${CSI}H")           // home
    }

    /** DECTCEM — hide the local cursor. We draw our own reverse-video
     *  block where we want the cursor visible, so the real cursor
     *  staying off avoids a second blinking artifact. */
    fun hideCursor(): String = "${CSI}?25l"

    /** DECTCEM — show. Used on Setup-mode exit, matching the real
     *  VT100's behavior of restoring the live cursor. */
    fun showCursor(): String = "${CSI}?25h"

    /** DECAWM off. Setup pages need to write 80-column-wide content
     *  on rows 23 and 24 without the implicit wrap-into-row-25 that
     *  triggers a screen scroll. Real VT100 Setup mode disables
     *  autowrap implicitly while it's active for the same reason;
     *  we do it explicitly. */
    fun autowrapOff(): String = "${CSI}?7l"

    /** DECAWM on — restored on Setup exit. */
    fun autowrapOn(): String = "${CSI}?7h"
}
