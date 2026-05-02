/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.terminal

/**
 * Hardcoded VT escape demo we feed into a freshly-created [Terminal]
 * instead of attaching it to the guest's serial line. Lives until we
 * wire the terminal to `kernelUart`; intentionally hits a wide spread
 * of CSI sequences so the parser-side bring-up stresses what matters
 * (color SGR, reverse video, bold, cursor positioning, line clearing).
 */
internal object BootDemo {

    private const val ESC = ""
    private const val CSI = "$ESC["

    fun bytes(): ByteArray = buildString {
        // Reset + clear screen + cursor home.
        append("${CSI}0m")
        append("${CSI}2J")
        append("${CSI}H")

        // 8-color palette demo.
        append("  ")
        val names = arrayOf("BLK", "RED", "GRN", "YEL", "BLU", "MAG", "CYN", "WHT")
        for (i in 0..7) {
            // SGR 30..37 = foreground 0..7
            append("${CSI}3${i}m${names[i]} ")
        }
        append("${CSI}0m\r\n")

        // Same on a contrasting background.
        append("  ")
        for (i in 0..7) {
            append("${CSI}4${i}m${CSI}30m ${names[i]} ")
        }
        append("${CSI}0m\r\n\r\n")

        // Attribute showcase.
        append("  ${CSI}1mBOLD${CSI}0m  ")
        append("${CSI}4mUNDERLINE${CSI}0m  ")
        append("${CSI}7mREVERSE${CSI}0m  ")
        append("${CSI}1;31mBOLD RED${CSI}0m\r\n\r\n")

        // Box-drawing via DEC line graphics (G0 = SCS to special set,
        // SO/SI to switch — but we'll just use Unicode for simplicity;
        // jexer's Cell handles non-ASCII codepoints fine).
        append("  ┌────────────────────────────────────────┐\r\n")
        append("  │  ${CSI}32mttyS0 — boot placeholder${CSI}0m              │\r\n")
        append("  │  no serial attached yet                │\r\n")
        append("  └────────────────────────────────────────┘\r\n\r\n")

        // ReGIS demo: a box, a green circle, the word "ReGIS" in
        // yellow. Whole sequence wrapped in DCS 1 p ... ST, fed via
        // vt_term_write_loopback into the parser → in-process
        // regis_render_file → blit onto the terminal.
        append("\u001bP1pS(C0)W(I0)S(E)")          // cursor off, pen black, erase
        append("W(I7)")                             // gray
        append("P[100,40]V[260,40][260,80][100,80][100,40]")
        append("W(I3)")                             // green
        append("P[180,60]C[210,60]")
        append("W(I6)")                             // yellow
        append("P[120,68]T(S2)'ReGIS'")
        append("\u001b\\")                       // ST (ESC \)
        append("\r\n")

        append("  vt100$ ")
    }.toByteArray(Charsets.UTF_8)
}
