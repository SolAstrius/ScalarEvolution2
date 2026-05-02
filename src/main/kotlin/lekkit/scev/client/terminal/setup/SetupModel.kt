/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.terminal.setup

import io.netty.buffer.ByteBuf
import net.minecraft.nbt.CompoundTag

/**
 * State backing the Setup-screen UI for a single terminal block. Splits
 * cleanly along persistence lines:
 *
 * - [PersistentState]: what a real VT100 keeps in NVR (tabs, switchpacks,
 *   speeds, answerback message, intensity, 80/132-column flag) plus our
 *   per-block additions (CRT FX, scrollback). Saved server-side in the
 *   block-entity's NBT, replicated to all viewers.
 * - [ViewState]: which page is active, where the focus / cursor is, am I
 *   currently editing the answerback message, etc. Pure UI ephemera —
 *   per-viewer, never serialized, lives only in the open
 *   [lekkit.scev.client.screen.TerminalScreen].
 *
 * The split mirrors how a real VT100 worked: multiple operators using
 * the same terminal saw the same persistent feature settings (anything
 * stored in NVR) but each session was free to navigate Setup pages
 * independently. In our world the "operators" are different players
 * looking at the same block face from different positions.
 *
 * The persistent state is intentionally a regular Kotlin data class
 * with primitive fields, so NBT serialisation is straightforward and
 * stable. Don't introduce Map / List of structs unless you also wire
 * proper schema versioning.
 */
data class SetupModel(
    val persistent: PersistentState = PersistentState(),
    val view: ViewState = ViewState(),
) {
    /**
     * NVR-equivalent state. Anything here survives world reload, world
     * unload, server restart, and is broadcast to every player viewing
     * this terminal so they see consistent feature settings.
     *
     * Switchpack semantics (per VT100 fig 1-6):
     *  - [sw1] = scroll, autorepeat, screen, cursor (4 bits)
     *  - [sw2] = auto xon/xoff, ANSI/VT52, keyclick, margin bell
     *  - [sw3] = #/£ shift, wrap-around, new-line, interlace
     *  - [sw4] = [reserved], power 60/50Hz, bits/char, parity sense + parity
     *  - [sw5] = STP optional pack, only meaningful when [sw5Present]
     *
     * Each switchpack is 4 bits packed into the low 4 bits of one byte;
     * MSB-first within the nibble matches the manual's left-to-right
     * reading of the indicator boxes.
     */
    data class PersistentState(
        val tabs: Set<Int> = DEFAULT_TABS,
        val sw1: Byte = 0b0101.toByte(),                  // matches fig 1-6 defaults
        val sw2: Byte = 0b0110.toByte(),
        val sw3: Byte = 0b0100.toByte(),
        val sw4: Byte = 0b0000.toByte(),
        val sw5: Byte = 0b0000.toByte(),
        val sw5Present: Boolean = false,
        val tSpeed: Int = 9600,
        val rSpeed: Int = 9600,
        /** Up to 20 chars; control chars allowed (rendered as `•` while
         *  editing). Empty string = no answerback set. */
        val answerback: String = "",
        val col132: Boolean = false,
        /** 0..15. Maps to the real VT100's NVR Intensity byte (4-bit
         *  DAC controlling the analog brightness signal). */
        val intensity: Int = 7,

        // ---- Mod additions (per-block, no DEC equivalent) -----------------
        val phosphor: Phosphor = Phosphor.GREEN,
        /** 0..50 percent. Visual intensity of the simulated scanline overlay
         *  in the in-world block-face renderer. 0 = no scanlines. */
        val scanlines: Int = 25,
        /** 0..90 percent. Phosphor-decay / motion-blur effect — how
         *  much of the previous frame "sticks" into the current
         *  frame each tick. 0 = instant updates (modern LCD); 50 =
         *  visible phosphor trail; 90 = smeary "ghost in the
         *  machine" look. */
        val persistence: Int = 0,
        val scrollback: Int = 1024,
    ) {
        fun toNbt(): CompoundTag = CompoundTag().also { t ->
            t.putIntArray("tabs", tabs.toIntArray())
            t.putByte("sw1", sw1)
            t.putByte("sw2", sw2)
            t.putByte("sw3", sw3)
            t.putByte("sw4", sw4)
            t.putByte("sw5", sw5)
            t.putBoolean("sw5p", sw5Present)
            t.putInt("tspeed", tSpeed)
            t.putInt("rspeed", rSpeed)
            t.putString("answerback", answerback)
            t.putBoolean("col132", col132)
            t.putInt("intensity", intensity)
            t.putString("phosphor", phosphor.name)
            t.putInt("scanlines", scanlines)
            t.putInt("persistence", persistence)
            t.putInt("scrollback", scrollback)
        }

        /** Wire encoding for [lekkit.scev.network.SetupSyncPayload] /
         *  [lekkit.scev.network.SetupEditPayload]. Matches [fromNbt] /
         *  [toNbt] in the set of fields covered. Version-tagged so future
         *  additions don't break older clients (server skips unknown
         *  trailing bytes by reading version first). */
        fun writeTo(buf: ByteBuf) {
            buf.writeByte(WIRE_VERSION)
            val arr = tabs.toIntArray()
            buf.writeInt(arr.size)
            for (t in arr) buf.writeInt(t)
            buf.writeByte(sw1.toInt())
            buf.writeByte(sw2.toInt())
            buf.writeByte(sw3.toInt())
            buf.writeByte(sw4.toInt())
            buf.writeByte(sw5.toInt())
            buf.writeBoolean(sw5Present)
            buf.writeInt(tSpeed)
            buf.writeInt(rSpeed)
            val ab = answerback.toByteArray(Charsets.UTF_8)
            buf.writeInt(ab.size); buf.writeBytes(ab)
            buf.writeBoolean(col132)
            buf.writeInt(intensity)
            buf.writeByte(phosphor.ordinal)
            buf.writeInt(scanlines)
            buf.writeInt(persistence)
            buf.writeInt(scrollback)
        }

        companion object {
            /** Bumped when the wire layout changes. Older clients see a
             *  newer version byte and ignore the packet rather than
             *  misinterpret it.
             *  v1 — initial release.
             *  v2 — added `persistence` field after `scanlines`. */
            const val WIRE_VERSION: Int = 2

            /** VT100 ROM default — tabs every 8 columns starting at col 9
             *  (so column 1 is NOT a tab; matching all real DEC firmware). */
            val DEFAULT_TABS: Set<Int> = (9..80 step 8).toSet()

            /** Companion to [writeTo]. Returns null on a wire-version
             *  mismatch so the caller can drop the packet rather than
             *  apply a half-decoded state. */
            fun readFrom(buf: ByteBuf): PersistentState? {
                val ver = buf.readByte().toInt()
                if (ver != WIRE_VERSION) return null
                val n = buf.readInt().coerceIn(0, 256)   // sanity cap; VT100 has 80 tabs max
                val tabs = HashSet<Int>(n)
                for (i in 0 until n) tabs.add(buf.readInt())
                val sw1 = buf.readByte()
                val sw2 = buf.readByte()
                val sw3 = buf.readByte()
                val sw4 = buf.readByte()
                val sw5 = buf.readByte()
                val sw5p = buf.readBoolean()
                val ts = buf.readInt()
                val rs = buf.readInt()
                val abLen = buf.readInt().coerceIn(0, 1024)
                val abBytes = ByteArray(abLen); buf.readBytes(abBytes)
                val ab = String(abBytes, Charsets.UTF_8)
                val col132 = buf.readBoolean()
                val intensity = buf.readInt()
                val phosphorOrd = buf.readByte().toInt().coerceIn(0, Phosphor.entries.size - 1)
                val phos = Phosphor.entries[phosphorOrd]
                val scanlines = buf.readInt()
                val persistence = buf.readInt()
                val scrollback = buf.readInt()
                return PersistentState(
                    tabs = tabs.ifEmpty { DEFAULT_TABS },
                    sw1 = sw1, sw2 = sw2, sw3 = sw3, sw4 = sw4, sw5 = sw5,
                    sw5Present = sw5p,
                    tSpeed = ts, rSpeed = rs,
                    answerback = ab,
                    col132 = col132,
                    intensity = intensity.coerceIn(0, 15),
                    phosphor = phos,
                    scanlines = scanlines.coerceIn(0, 50),
                    persistence = persistence.coerceIn(0, 90),
                    scrollback = scrollback.coerceIn(64, 65536),
                )
            }

            fun fromNbt(t: CompoundTag): PersistentState {
                if (t.isEmpty) return PersistentState()
                val phosphorName = t.getString("phosphor")
                val phos = runCatching { Phosphor.valueOf(phosphorName) }
                    .getOrDefault(Phosphor.GREEN)
                return PersistentState(
                    tabs = t.getIntArray("tabs").toSet().ifEmpty { DEFAULT_TABS },
                    sw1 = t.getByte("sw1"),
                    sw2 = t.getByte("sw2"),
                    sw3 = t.getByte("sw3"),
                    sw4 = t.getByte("sw4"),
                    sw5 = t.getByte("sw5"),
                    sw5Present = t.getBoolean("sw5p"),
                    tSpeed = if (t.contains("tspeed")) t.getInt("tspeed") else 9600,
                    rSpeed = if (t.contains("rspeed")) t.getInt("rspeed") else 9600,
                    answerback = t.getString("answerback"),
                    col132 = t.getBoolean("col132"),
                    intensity = if (t.contains("intensity")) t.getInt("intensity") else 7,
                    phosphor = phos,
                    scanlines = if (t.contains("scanlines")) t.getInt("scanlines") else 25,
                    persistence = if (t.contains("persistence")) t.getInt("persistence") else 0,
                    scrollback = if (t.contains("scrollback")) t.getInt("scrollback") else 1024,
                )
            }
        }
    }

    /**
     * Per-viewer transient state. Lives in the TerminalScreen instance,
     * dies on screen close, never replicated.
     */
    data class ViewState(
        val page: Page = Page.SETUP_A,
        /** Index of the currently-focused field within the active page.
         *  Each renderer interprets this in its own page-local terms. */
        val focus: Int = 0,
        /** Active cursor column on row 23 (Setup A/B free line). 1-based. */
        val cursorCol: Int = 1,
        /** True while the user is mid-flight typing an answerback message
         *  (Shift+A in Setup B). [answerbackEditBuf] contains what they've
         *  typed so far; [answerbackDelim] is the delimiter character they
         *  picked, captured on the first keystroke after Shift+A. */
        val answerbackEditing: Boolean = false,
        val answerbackEditBuf: String = "",
        val answerbackDelim: String = "",
    )

    /** Persistent CRT phosphor color. Affects the in-world block-face
     *  renderer's color tint; does not affect the inner mlterm output
     *  bytes (those stay monochrome white-on-black, the renderer tints
     *  on output). */
    enum class Phosphor { GREEN, AMBER, WHITE }

    /** Setup pages, in left-to-right order for arrow-key navigation. */
    enum class Page {
        /** SET-UP A — tab stops + decade ruler. */
        SETUP_A,
        /** SET-UP B — switchpacks + speeds (and answerback edit overlay). */
        SETUP_B,
        /** CRT FX — brightness, phosphor, scanlines, 80/132 toggle.
         *  Mod-specific page; brightness slot is period-real (NVR Intensity). */
        CRT_FX,
        /** MOD — Minecraft-only metadata: bound UUID (read-only),
         *  TERM (read-only), scrollback (editable). */
        MOD,
    }
}
