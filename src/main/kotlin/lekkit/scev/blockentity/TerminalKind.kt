/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.blockentity

/**
 * Era / capability profile for a terminal block.
 *
 * Each [TerminalBlockEntity] carries one of these. The kind drives:
 *  - [termType]: the string passed to mlterm's `vt_create_term`,
 *    which in turn shapes the DA reply, the supported escape
 *    sequences, and the terminfo entry the guest should ideally use.
 *  - [cols] × [rows]: text grid dimensions. Most DEC terminals were
 *    80×24; a few (VT132, VT420) supported 132 columns. Teletypes
 *    are append-only so the row count means "scrollback that fits."
 *  - [displayName]: shown in tooltips / menu titles.
 *
 * **What's NOT here yet:** font choice and DA-reply override.
 * Future kinds with non-default fonts (e.g. a hardcopy teletype's
 * Courier) will need a font-asset path; bespoke DA replies (e.g. a
 * VT52 that should claim no extensions) will need a separate hook
 * in `scev_term_new`. For now mlterm picks the right defaults from
 * [termType] alone.
 *
 * Adding a new kind: bump the enum, add a concrete `Vt220Block`
 * (etc.) that constructs a [TerminalBlockEntity] with the new kind,
 * ship the model + texture under its own asset path, register a
 * blockstate slot in `ScevRegistry`. The shared
 * [lekkit.scev.menu.TerminalMenu] /
 * [lekkit.scev.client.screen.TerminalScreen] /
 * [lekkit.scev.client.terminal.TerminalActiveHost] machinery picks
 * up the new kind without further changes.
 */
enum class TerminalKind(
    val termType: String,
    val cols: Int,
    val rows: Int,
    val displayName: String,
) {
    /** DEC VT100 (1978). Monochrome, no color SGR, 80×24, basic
     *  cursor + line-attribute control. Mlterm responds to DA with
     *  a minimal reply when term_type=vt100. */
    VT100("vt100", 80, 24, "VT100"),

    /** DEC VT220 (1983). Soft fonts (DRCS), selective erase, 8-color
     *  ANSI SGR. Same grid as VT100. */
    VT220("vt220", 80, 24, "VT220"),

    /** DEC VT340 (1987). Sixel + ReGIS graphics planes, 16 colors.
     *  The peak of pre-X-Window DEC terminals. */
    VT340("vt340", 80, 24, "VT340"),

    /** DEC VT420 (1990). 132-column mode, multi-session, soft-reset
     *  improvements. No graphics — DEC moved graphics to the VTxxx-G
     *  line. Industrial dark-gray case marking the DEC-to-Compaq
     *  transition era. */
    VT420("vt420", 80, 24, "VT420"),

    /** DEC VT520 (1993). The last DEC-branded terminal. 8-bit C1
     *  controls, ANSI 3.64 conformance level 4, TEK 4014 graphics
     *  emulation. Charcoal case. */
    VT520("vt520", 80, 24, "VT520"),
    ;

    companion object {
        /** Default for a freshly-placed Vt100Block. */
        val DEFAULT: TerminalKind = VT100

        /** Lenient lookup — returns [DEFAULT] if [name] doesn't match
         *  any enum constant. Used on NBT load where a future-version
         *  save might carry an unknown name; better to fall back to a
         *  sane default than crash chunk loading. */
        @JvmStatic
        fun byNameOrDefault(name: String?): TerminalKind {
            if (name == null) return DEFAULT
            return entries.firstOrNull { it.name == name } ?: DEFAULT
        }
    }
}
