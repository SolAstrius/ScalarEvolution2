/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.machine;

import static org.junit.jupiter.api.Assertions.*;

import lekkit.scev.machine.BootSplash;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for {@link BootSplash}. The splash exists specifically
 * to prevent "dark screen on power on" — its pixels must actually be written
 * to the framebuffer.
 *
 * <p>If {@link #paintsNonZeroPixels} fails, the dark-screen bug is back.
 */
class BootSplashTest {

    @Test
    @DisplayName("paint writes non-zero pixels to every quadrant")
    void paintsNonZeroPixels() {
        FakeMachineBackend.FakeFramebuffer fb = new FakeMachineBackend.FakeFramebuffer(640, 480);
        BootSplash.paint(fb);

        int[] quadrantPixels = new int[4];
        for (int y = 0; y < 480; y++) {
            for (int x = 0; x < 640; x++) {
                int off = (y * 640 + x) * 4;
                int b = fb.pixels().get(off)     & 0xFF;
                int g = fb.pixels().get(off + 1) & 0xFF;
                int r = fb.pixels().get(off + 2) & 0xFF;
                int a = fb.pixels().get(off + 3) & 0xFF;
                if ((a | r | g | b) == 0) continue;
                int quad = (y < 240 ? 0 : 2) + (x < 320 ? 0 : 1);
                quadrantPixels[quad]++;
            }
        }
        for (int q = 0; q < 4; q++) {
            assertTrue(quadrantPixels[q] > 0,
                    "BootSplash left quadrant " + q + " empty — user would see a dark screen here");
        }
    }

    @Test
    @DisplayName("paint writes the background color at (0,0)")
    void paintsBackground() {
        FakeMachineBackend.FakeFramebuffer fb = new FakeMachineBackend.FakeFramebuffer(640, 480);
        BootSplash.paint(fb);
        // Top-left pixel should be either background or accent (not zero).
        int b = fb.pixels().get(0)     & 0xFF;
        int g = fb.pixels().get(1) & 0xFF;
        int r = fb.pixels().get(2) & 0xFF;
        int a = fb.pixels().get(3) & 0xFF;
        assertEquals(0xFF, a, "alpha must be opaque");
        assertTrue((r | g | b) > 0, "Top-left pixel is pure transparent — splash didn't paint");
    }

    @Test
    @DisplayName("paint is a no-op for tiny buffers (won't trash memory)")
    void tinyBufferNoOp() {
        FakeMachineBackend.FakeFramebuffer fb = new FakeMachineBackend.FakeFramebuffer(8, 8);
        BootSplash.paint(fb);
        // Every pixel should still be zero — we skipped because the buffer is too small.
        for (int i = 0; i < 8 * 8 * 4; i++) {
            assertEquals(0, fb.pixels().get(i), "Tiny buffer should be untouched, but byte " + i + " was written");
        }
    }
}
