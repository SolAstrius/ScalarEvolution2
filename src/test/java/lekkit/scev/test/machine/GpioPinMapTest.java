/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import lekkit.scev.machine.GpioPinMap;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the guest-pin ↔ world-face translation.
 *
 * <p>The block-relative encoding is what firmware sees; the world encoding is
 * what Minecraft's neighbour queries speak. These tests assert three things:
 * <ol>
 *   <li>round-trip identity across all 64 possible masks and all 4 horizontal
 *       facings — a pin written by a firmware and bounced through to the
 *       adjacent world face and back must come out unchanged;</li>
 *   <li>a known-good table for the cardinal facings so a silent sign flip
 *       in the rotation math is caught;</li>
 *   <li>TOP/BOTTOM are invariant under rotation — vertical faces never
 *       rotate with horizontal facing.</li>
 * </ol>
 */
class GpioPinMapTest {
    private static final Direction[] HORIZONTAL =
            {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

    @Test
    @DisplayName("round-trip: relativeToWorld∘worldToRelative == id for all 64 masks × 4 facings")
    void roundTripIdentity() {
        for (Direction f : HORIZONTAL) {
            for (int p = 0; p < 64; p++) {
                int there = GpioPinMap.worldToRelative(p, f);
                int back  = GpioPinMap.relativeToWorld(there, f);
                assertEquals(p, back,
                        "world→rel→world mismatch for pins=" + Integer.toBinaryString(p) + " facing=" + f);
            }
        }
    }

    @Test
    @DisplayName("round-trip reverse: worldToRelative∘relativeToWorld == id")
    void roundTripIdentityReverse() {
        for (Direction f : HORIZONTAL) {
            for (int p = 0; p < 64; p++) {
                int there = GpioPinMap.relativeToWorld(p, f);
                int back  = GpioPinMap.worldToRelative(there, f);
                assertEquals(p, back,
                        "rel→world→rel mismatch for pins=" + Integer.toBinaryString(p) + " facing=" + f);
            }
        }
    }

    @Test
    @DisplayName("FRONT bit maps to whichever cardinal direction the block is facing")
    void frontFollowsFacing() {
        // FRONT = bit 0 in block-relative.
        int front = 1 << GpioPinMap.BIT_FRONT;
        assertEquals(1 << Direction.NORTH.ordinal(), GpioPinMap.relativeToWorld(front, Direction.NORTH));
        assertEquals(1 << Direction.SOUTH.ordinal(), GpioPinMap.relativeToWorld(front, Direction.SOUTH));
        assertEquals(1 << Direction.EAST.ordinal(),  GpioPinMap.relativeToWorld(front, Direction.EAST));
        assertEquals(1 << Direction.WEST.ordinal(),  GpioPinMap.relativeToWorld(front, Direction.WEST));
    }

    @Test
    @DisplayName("LEFT/RIGHT follow CCW/CW of FACING")
    void leftRightFollowFacing() {
        // Facing NORTH: LEFT=WEST (CCW), RIGHT=EAST (CW).
        // Convention: getCounterClockWise on NORTH returns WEST (viewing from above,
        // the horizon rotates CCW when you pass from the emitter's perspective).
        int left  = 1 << GpioPinMap.BIT_LEFT;
        int right = 1 << GpioPinMap.BIT_RIGHT;
        assertEquals(1 << Direction.WEST.ordinal(),  GpioPinMap.relativeToWorld(left,  Direction.NORTH));
        assertEquals(1 << Direction.EAST.ordinal(),  GpioPinMap.relativeToWorld(right, Direction.NORTH));
        assertEquals(1 << Direction.NORTH.ordinal(), GpioPinMap.relativeToWorld(left,  Direction.EAST));
        assertEquals(1 << Direction.SOUTH.ordinal(), GpioPinMap.relativeToWorld(right, Direction.EAST));
    }

    @Test
    @DisplayName("BACK is always the opposite of FRONT")
    void backIsOppositeOfFacing() {
        int back = 1 << GpioPinMap.BIT_BACK;
        assertEquals(1 << Direction.SOUTH.ordinal(), GpioPinMap.relativeToWorld(back, Direction.NORTH));
        assertEquals(1 << Direction.NORTH.ordinal(), GpioPinMap.relativeToWorld(back, Direction.SOUTH));
        assertEquals(1 << Direction.WEST.ordinal(),  GpioPinMap.relativeToWorld(back, Direction.EAST));
        assertEquals(1 << Direction.EAST.ordinal(),  GpioPinMap.relativeToWorld(back, Direction.WEST));
    }

    @Test
    @DisplayName("TOP/BOTTOM are invariant under horizontal rotation")
    void verticalFacesAreInvariant() {
        int top    = 1 << GpioPinMap.BIT_TOP;
        int bottom = 1 << GpioPinMap.BIT_BOTTOM;
        int expectedTop    = 1 << Direction.UP.ordinal();
        int expectedBottom = 1 << Direction.DOWN.ordinal();
        for (Direction f : HORIZONTAL) {
            assertEquals(expectedTop,    GpioPinMap.relativeToWorld(top,    f), "top rotated under " + f);
            assertEquals(expectedBottom, GpioPinMap.relativeToWorld(bottom, f), "bottom rotated under " + f);
        }
    }

    @Test
    @DisplayName("high bits above 0x3F are masked — only low 6 matter")
    void ignoresHighBits() {
        // BIT_FRONT set plus garbage at bit 7 should yield the same result.
        int clean = 1 << GpioPinMap.BIT_FRONT;
        int dirty = clean | 0x80;
        // Implementation just ignores them (bit 7 never matches any shifted index in 0..5).
        assertEquals(
                GpioPinMap.relativeToWorld(clean, Direction.NORTH),
                GpioPinMap.relativeToWorld(dirty, Direction.NORTH));
    }
}
