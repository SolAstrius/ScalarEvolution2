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
 * Regression tests for the animated {@link BootSplash#paintHeartbeat}.
 *
 * <p>Why this exists: static splash is not enough. Once the user sees "POWER ON"
 * and nothing else changes they think the mod is broken. The heartbeat paints
 * a pulsing circle in the top-left corner every server tick — visible proof
 * the machine is being ticked. If this animation stops working, we're back to
 * the "frozen screen" UX bug.
 *
 * <p>Test strategy: paint at two different ticks, compare pixel bytes in the
 * heartbeat region. They must differ (at least one pixel changes).
 */
class HeartbeatAnimationTest {

    @Test
    @DisplayName("Heartbeat paints some non-zero pixels")
    void paintsPixels() {
        FakeMachineBackend.FakeFramebuffer fb = new FakeMachineBackend.FakeFramebuffer(640, 480);
        BootSplash.paintHeartbeat(fb, 0);
        boolean any = false;
        for (int i = 0; i < 40 * 40 * 4; i++) { // scan top-left 40x40
            int y = i / (40 * 4);
            int xByte = i % (40 * 4);
            int off = (y * 640 + xByte / 4) * 4 + (xByte % 4);
            if (fb.pixels().get(off) != 0) { any = true; break; }
        }
        assertTrue(any, "paintHeartbeat left no trace");
    }

    @Test
    @DisplayName("Two different ticks produce visibly different heartbeat frames")
    void frameToFrameDifferent() {
        FakeMachineBackend.FakeFramebuffer a = new FakeMachineBackend.FakeFramebuffer(640, 480);
        FakeMachineBackend.FakeFramebuffer b = new FakeMachineBackend.FakeFramebuffer(640, 480);
        BootSplash.paintHeartbeat(a, 0);
        BootSplash.paintHeartbeat(b, 5);   // different phase of the 20-tick cycle

        // Inspect the heartbeat region (approx center (20, 20), radius 10).
        boolean differs = false;
        for (int dy = -10; dy <= 10 && !differs; dy++) {
            for (int dx = -10; dx <= 10 && !differs; dx++) {
                int x = 20 + dx;
                int y = 20 + dy;
                if (x < 0 || y < 0 || x >= 640 || y >= 480) continue;
                int off = (y * 640 + x) * 4;
                for (int k = 0; k < 4; k++) {
                    if (a.pixels().get(off + k) != b.pixels().get(off + k)) {
                        differs = true;
                        break;
                    }
                }
            }
        }
        assertTrue(differs, "tick 0 and tick 5 produced identical heartbeat pixels — animation is not working");
    }

    @Test
    @DisplayName("Heartbeat is a no-op on tiny framebuffers")
    void tinyNoOp() {
        FakeMachineBackend.FakeFramebuffer fb = new FakeMachineBackend.FakeFramebuffer(8, 8);
        BootSplash.paintHeartbeat(fb, 0);
        for (int i = 0; i < 8 * 8 * 4; i++) {
            assertEquals(0, fb.pixels().get(i), "tiny framebuffer should be untouched");
        }
    }

    @Test
    @DisplayName("Heartbeat region is bounded (doesn't overwrite the bottom-right)")
    void regionBounded() {
        FakeMachineBackend.FakeFramebuffer fb = new FakeMachineBackend.FakeFramebuffer(640, 480);
        // Paint a sentinel byte at (400, 400) and verify heartbeat doesn't clobber it.
        int sentinelOff = (400 * 640 + 400) * 4;
        fb.pixels().put(sentinelOff, (byte) 0x77);
        BootSplash.paintHeartbeat(fb, 3);
        assertEquals((byte) 0x77, fb.pixels().get(sentinelOff),
                "heartbeat overflowed into unrelated framebuffer region");
    }
}
