/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.machine;

import java.nio.ByteBuffer;

/**
 * Paints a recognisable "POWER ON" pattern into a fresh framebuffer so the
 * user sees visible feedback even when no firmware is running.
 *
 * <p>This exists because the previous code path would silently boot a
 * machine with no bootrom loaded — the CPU would fetch zeros from RAM,
 * fail its first instruction decode, and halt. The framebuffer stayed
 * at its zero-initialized state (fully transparent black), which the
 * player read as "the mod is broken".
 *
 * <p>Running firmware will overwrite the splash on its first frame. The
 * splash is only visible if:
 * <ul>
 *   <li>no firmware is loaded (empty flash asset, no {@code fw_payload.bin})</li>
 *   <li>firmware failed to start</li>
 *   <li>firmware hasn't drawn its first frame yet</li>
 * </ul>
 *
 * <p>Pattern description: a deep blue background (like a BSOD or early-boot
 * text-mode) with a centred orange "POWER ON" legend drawn as block pixels.
 * Pure Java — no RISC-V toolchain required.
 */
public final class BootSplash {
    /** Background color (deep blue). A, R, G, B bytes. */
    private static final int BG_A = 0xFF, BG_R = 0x10, BG_G = 0x20, BG_B = 0x60;
    /** Foreground color (orange). */
    private static final int FG_A = 0xFF, FG_R = 0xF0, FG_G = 0x90, FG_B = 0x20;
    /** Accent color for the diagonal decoration (lighter blue). */
    private static final int ACCENT_A = 0xFF, ACCENT_R = 0x40, ACCENT_G = 0x60, ACCENT_B = 0xA0;

    /** Block-pixel scale for the text — scaled at paint time. */
    private static final int PIXEL_SCALE = 4;

    private BootSplash() {}

    /** Center of the heartbeat indicator (pixels). */
    private static final int HEARTBEAT_CX = 20;
    private static final int HEARTBEAT_CY = 20;
    /** Max radius of the heartbeat pulse. */
    private static final int HEARTBEAT_R = 8;

    /**
     * Paint the animated "heartbeat" indicator in the top-left corner —
     * a small pulsing circle whose radius oscillates with {@code tick}.
     *
     * <p>This is called from {@code ComputerCaseBlockEntity.serverTick} on
     * every Minecraft tick as long as the machine is powered and has a
     * display. It gives the user visible proof that the server is ticking
     * the machine — even when no firmware is drawing to the framebuffer.
     *
     * <p>Only touches a 16×16 region around the indicator, so the rest of
     * the splash (and any firmware-written pixels) is left alone.
     */
    public static void paintHeartbeat(FramebufferView fb, int tick) {
        int w = fb.width();
        int h = fb.height();
        if (w < 64 || h < 48) return;
        ByteBuffer buf = fb.pixels();
        Writer writer = new Writer(buf, w, h);

        // Heartbeat radius oscillates 3..8 over a 20-tick period.
        double phase = (tick % 20) / 20.0;
        int r = (int) (3 + (HEARTBEAT_R - 3) * (0.5 + 0.5 * Math.sin(phase * 2 * Math.PI)));
        int rSquared = r * r;
        // Outer radius always the same so we clear the region each frame.
        int outerR = HEARTBEAT_R + 1;
        int outerSq = outerR * outerR;

        for (int dy = -outerR; dy <= outerR; dy++) {
            for (int dx = -outerR; dx <= outerR; dx++) {
                int dist = dx * dx + dy * dy;
                if (dist > outerSq) continue;
                int x = HEARTBEAT_CX + dx;
                int y = HEARTBEAT_CY + dy;
                if (dist <= rSquared) {
                    writer.pixel(x, y, FG_A, FG_R, FG_G, FG_B);
                } else {
                    // Erase to background so previous-frame ring is cleaned up.
                    writer.pixel(x, y, BG_A, BG_R, BG_G, BG_B);
                }
            }
        }
    }

    /** Paint the splash into {@code fb}. Safe to call on any framebuffer; does nothing if dimensions are absurd. */
    public static void paint(FramebufferView fb) {
        int w = fb.width();
        int h = fb.height();
        if (w < 64 || h < 48) return;

        ByteBuffer buf = fb.pixels();
        Writer writer = new Writer(buf, w, h);

        // 1. Fill the whole display with the background color.
        writer.fill(BG_A, BG_R, BG_G, BG_B);

        // 2. Decoration: faint diagonal stripes so the user can see "yes, it's drawing".
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (((x + y) / 12) % 4 == 0) {
                    writer.pixel(x, y, ACCENT_A, ACCENT_R, ACCENT_G, ACCENT_B);
                }
            }
        }

        // 3. "POWER ON" text, centred, rendered via the tiny 5x7 font below.
        String text = "POWER ON";
        int textPixelWidth = (text.length() * (GLYPH_W + 1) - 1) * PIXEL_SCALE;
        int textPixelHeight = GLYPH_H * PIXEL_SCALE;
        int x0 = (w - textPixelWidth) / 2;
        int y0 = (h - textPixelHeight) / 2;
        for (int i = 0; i < text.length(); i++) {
            drawGlyph(writer, text.charAt(i), x0 + i * (GLYPH_W + 1) * PIXEL_SCALE, y0, PIXEL_SCALE);
        }

        // 4. A small framing rectangle so users can verify their rendering
        //    isn't clipping the framebuffer (stretches/black borders indicate
        //    a UV misconfiguration).
        writer.rect(2, 2, w - 4, h - 4, FG_A, FG_R, FG_G, FG_B);
    }

    private static void drawGlyph(Writer w, char ch, int x, int y, int scale) {
        byte[] rows = glyphFor(ch);
        for (int row = 0; row < GLYPH_H; row++) {
            int bits = rows[row] & 0xFF;
            for (int col = 0; col < GLYPH_W; col++) {
                if (((bits >> (GLYPH_W - 1 - col)) & 1) != 0) {
                    w.block(x + col * scale, y + row * scale, scale, scale, FG_A, FG_R, FG_G, FG_B);
                }
            }
        }
    }

    // 5x7 glyphs for the small alphabet we need. Bits are MSB-first;
    // the top-left pixel is bit (GLYPH_W-1) of row 0.
    private static final int GLYPH_W = 5;
    private static final int GLYPH_H = 7;

    private static byte[] glyphFor(char ch) {
        return switch (ch) {
            case 'P' -> new byte[] { 0b11110, 0b10001, 0b10001, 0b11110, 0b10000, 0b10000, 0b10000 };
            case 'O' -> new byte[] { 0b01110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110 };
            case 'W' -> new byte[] { 0b10001, 0b10001, 0b10001, 0b10001, 0b10101, 0b11011, 0b10001 };
            case 'E' -> new byte[] { 0b11111, 0b10000, 0b10000, 0b11110, 0b10000, 0b10000, 0b11111 };
            case 'R' -> new byte[] { 0b11110, 0b10001, 0b10001, 0b11110, 0b10100, 0b10010, 0b10001 };
            case 'N' -> new byte[] { 0b10001, 0b11001, 0b10101, 0b10101, 0b10011, 0b10001, 0b10001 };
            case ' ' -> new byte[] { 0, 0, 0, 0, 0, 0, 0 };
            default  -> new byte[] { 0b11111, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b11111 };
        };
    }

    /** Tiny writer so we don't sprinkle byte arithmetic all over. */
    private static final class Writer {
        private final ByteBuffer buf;
        private final int w, h;

        Writer(ByteBuffer buf, int w, int h) {
            this.buf = buf;
            this.w = w;
            this.h = h;
        }

        void pixel(int x, int y, int a, int r, int g, int b) {
            if (x < 0 || y < 0 || x >= w || y >= h) return;
            int off = (y * w + x) * 4;
            buf.put(off,     (byte) b);
            buf.put(off + 1, (byte) g);
            buf.put(off + 2, (byte) r);
            buf.put(off + 3, (byte) a);
        }

        void block(int x, int y, int bw, int bh, int a, int r, int g, int b) {
            for (int dy = 0; dy < bh; dy++) for (int dx = 0; dx < bw; dx++) pixel(x + dx, y + dy, a, r, g, b);
        }

        void fill(int a, int r, int g, int b) {
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) pixel(x, y, a, r, g, b);
        }

        /** Unfilled rectangle outline (1-pixel stroke). */
        void rect(int x, int y, int rw, int rh, int a, int r, int g, int b) {
            for (int i = 0; i < rw; i++) { pixel(x + i, y, a, r, g, b); pixel(x + i, y + rh - 1, a, r, g, b); }
            for (int i = 0; i < rh; i++) { pixel(x, y + i, a, r, g, b); pixel(x + rw - 1, y + i, a, r, g, b); }
        }
    }
}
