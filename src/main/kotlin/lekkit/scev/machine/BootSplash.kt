/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.machine

import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.sin

/**
 * Paints a recognisable "POWER ON" pattern into a fresh framebuffer so
 * the user sees visible feedback even when no firmware is running.
 *
 * Running firmware will overwrite the splash on its first frame. The
 * splash is only visible if no firmware is loaded, the firmware failed
 * to start, or it hasn't drawn its first frame yet.
 *
 * Pattern description: a deep blue background (like a BSOD or
 * early-boot text-mode) with a centred orange "POWER ON" legend drawn
 * as block pixels. Pure JVM — no RISC-V toolchain required.
 */
object BootSplash {
    /** Background color (deep blue). A, R, G, B bytes. */
    private const val BG_A = 0xFF; private const val BG_R = 0x10
    private const val BG_G = 0x20; private const val BG_B = 0x60
    /** Foreground color (orange). */
    private const val FG_A = 0xFF; private const val FG_R = 0xF0
    private const val FG_G = 0x90; private const val FG_B = 0x20
    /** Accent color for the diagonal decoration (lighter blue). */
    private const val ACCENT_A = 0xFF; private const val ACCENT_R = 0x40
    private const val ACCENT_G = 0x60; private const val ACCENT_B = 0xA0

    /** Block-pixel scale for the text — scaled at paint time. */
    private const val PIXEL_SCALE = 4

    /** Center of the heartbeat indicator (pixels). */
    private const val HEARTBEAT_CX = 20
    private const val HEARTBEAT_CY = 20
    /** Max radius of the heartbeat pulse. */
    private const val HEARTBEAT_R = 8

    // 5x7 glyphs for the small alphabet we need. Bits are MSB-first;
    // the top-left pixel is bit (GLYPH_W-1) of row 0.
    private const val GLYPH_W = 5
    private const val GLYPH_H = 7

    /**
     * Paint the animated "heartbeat" indicator in the top-left corner
     * — a small pulsing circle whose radius oscillates with [tick].
     *
     * Called from `ComputerCaseBlockEntity.serverTick` on every
     * Minecraft tick as long as the machine is powered and has a
     * display. Gives the user visible proof that the server is
     * ticking the machine — even when no firmware is drawing to the
     * framebuffer.
     *
     * Only touches a 16×16 region around the indicator, so the rest
     * of the splash (and any firmware-written pixels) is left alone.
     */
    @JvmStatic
    fun paintHeartbeat(fb: FramebufferView, tick: Int) {
        val w = fb.width()
        val h = fb.height()
        if (w < 64 || h < 48) return
        val writer = Writer(fb.pixels(), w, h)

        // Heartbeat radius oscillates 3..8 over a 20-tick period.
        val phase = (tick % 20) / 20.0
        val r = (3 + (HEARTBEAT_R - 3) * (0.5 + 0.5 * sin(phase * 2 * PI))).toInt()
        val rSquared = r * r
        // Outer radius always the same so we clear the region each frame.
        val outerR = HEARTBEAT_R + 1
        val outerSq = outerR * outerR

        for (dy in -outerR..outerR) {
            for (dx in -outerR..outerR) {
                val dist = dx * dx + dy * dy
                if (dist > outerSq) continue
                val x = HEARTBEAT_CX + dx
                val y = HEARTBEAT_CY + dy
                if (dist <= rSquared) {
                    writer.pixel(x, y, FG_A, FG_R, FG_G, FG_B)
                } else {
                    // Erase to background so previous-frame ring is cleaned up.
                    writer.pixel(x, y, BG_A, BG_R, BG_G, BG_B)
                }
            }
        }
    }

    /** Paint the splash into [fb]. Safe on any framebuffer; does nothing if dimensions are absurd. */
    @JvmStatic
    fun paint(fb: FramebufferView) {
        val w = fb.width()
        val h = fb.height()
        if (w < 64 || h < 48) return

        val writer = Writer(fb.pixels(), w, h)

        // 1. Fill the whole display with the background color.
        writer.fill(BG_A, BG_R, BG_G, BG_B)

        // 2. Decoration: faint diagonal stripes so the user can see "yes, it's drawing".
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (((x + y) / 12) % 4 == 0) {
                    writer.pixel(x, y, ACCENT_A, ACCENT_R, ACCENT_G, ACCENT_B)
                }
            }
        }

        // 3. "POWER ON" text, centred, rendered via the tiny 5x7 font below.
        val text = "POWER ON"
        val textPixelWidth = (text.length * (GLYPH_W + 1) - 1) * PIXEL_SCALE
        val textPixelHeight = GLYPH_H * PIXEL_SCALE
        val x0 = (w - textPixelWidth) / 2
        val y0 = (h - textPixelHeight) / 2
        for (i in text.indices) {
            drawGlyph(writer, text[i], x0 + i * (GLYPH_W + 1) * PIXEL_SCALE, y0, PIXEL_SCALE)
        }

        // 4. A small framing rectangle so users can verify their rendering
        //    isn't clipping the framebuffer (stretches/black borders indicate
        //    a UV misconfiguration).
        writer.rect(2, 2, w - 4, h - 4, FG_A, FG_R, FG_G, FG_B)
    }

    private fun drawGlyph(w: Writer, ch: Char, x: Int, y: Int, scale: Int) {
        val rows = glyphFor(ch)
        for (row in 0 until GLYPH_H) {
            val bits = rows[row].toInt() and 0xFF
            for (col in 0 until GLYPH_W) {
                if ((bits shr (GLYPH_W - 1 - col)) and 1 != 0) {
                    w.block(x + col * scale, y + row * scale, scale, scale, FG_A, FG_R, FG_G, FG_B)
                }
            }
        }
    }

    private fun glyphFor(ch: Char): ByteArray = when (ch) {
        'P' -> byteArrayOf(0b11110, 0b10001, 0b10001, 0b11110, 0b10000, 0b10000, 0b10000)
        'O' -> byteArrayOf(0b01110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110)
        'W' -> byteArrayOf(0b10001, 0b10001, 0b10001, 0b10001, 0b10101, 0b11011, 0b10001)
        'E' -> byteArrayOf(0b11111, 0b10000, 0b10000, 0b11110, 0b10000, 0b10000, 0b11111)
        'R' -> byteArrayOf(0b11110, 0b10001, 0b10001, 0b11110, 0b10100, 0b10010, 0b10001)
        'N' -> byteArrayOf(0b10001, 0b11001, 0b10101, 0b10101, 0b10011, 0b10001, 0b10001)
        ' ' -> byteArrayOf(0, 0, 0, 0, 0, 0, 0)
        else -> byteArrayOf(0b11111, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b11111.toByte())
    }

    /** Tiny writer so we don't sprinkle byte arithmetic all over. */
    private class Writer(private val buf: ByteBuffer, private val w: Int, private val h: Int) {
        fun pixel(x: Int, y: Int, a: Int, r: Int, g: Int, b: Int) {
            if (x < 0 || y < 0 || x >= w || y >= h) return
            val off = (y * w + x) * 4
            buf.put(off,     b.toByte())
            buf.put(off + 1, g.toByte())
            buf.put(off + 2, r.toByte())
            buf.put(off + 3, a.toByte())
        }

        fun block(x: Int, y: Int, bw: Int, bh: Int, a: Int, r: Int, g: Int, b: Int) {
            for (dy in 0 until bh) for (dx in 0 until bw) pixel(x + dx, y + dy, a, r, g, b)
        }

        fun fill(a: Int, r: Int, g: Int, b: Int) {
            for (y in 0 until h) for (x in 0 until w) pixel(x, y, a, r, g, b)
        }

        /** Unfilled rectangle outline (1-pixel stroke). */
        fun rect(x: Int, y: Int, rw: Int, rh: Int, a: Int, r: Int, g: Int, b: Int) {
            for (i in 0 until rw) {
                pixel(x + i, y, a, r, g, b)
                pixel(x + i, y + rh - 1, a, r, g, b)
            }
            for (i in 0 until rh) {
                pixel(x, y + i, a, r, g, b)
                pixel(x + rw - 1, y + i, a, r, g, b)
            }
        }
    }
}
