/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.machine;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import lekkit.rvvm.HIDKeyboard;
import lekkit.scev.client.screen.HeldKeyTracker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@code MachineScreen} uses this tracker to ensure that every keyPress
 * gets a matching keyRelease even when the screen closes mid-keystroke —
 * otherwise the VM sees Shift (or any other modifier) as stuck down forever.
 *
 * <p>These tests capture the press / release fan-out by recording calls into
 * a pair of lists, so we verify the exact sequence of events the
 * {@code MachineScreen} would generate without instantiating a screen
 * (which requires a full Minecraft render context).
 */
class HeldKeyTrackerTest {

    private final List<Byte> presses = new ArrayList<>();
    private final List<Byte> releases = new ArrayList<>();
    private final HeldKeyTracker tracker = new HeldKeyTracker(presses::add, releases::add);

    @Test
    @DisplayName("press forwards the key and records it as held")
    void pressRecords() {
        tracker.press(HIDKeyboard.HID_KEY_A);
        assertEquals(1, presses.size());
        assertEquals(HIDKeyboard.HID_KEY_A, presses.get(0));
        assertTrue(tracker.isHeld(HIDKeyboard.HID_KEY_A));
        assertEquals(1, tracker.heldCount());
    }

    @Test
    @DisplayName("HID 0 (unmapped) is ignored — no press, no tracking")
    void unmappedKeyIgnored() {
        tracker.press((byte) 0);
        assertTrue(presses.isEmpty());
        assertEquals(0, tracker.heldCount());
    }

    @Test
    @DisplayName("release sends release and untracks")
    void releaseUntracks() {
        tracker.press(HIDKeyboard.HID_KEY_A);
        tracker.release(HIDKeyboard.HID_KEY_A);
        assertEquals(1, releases.size());
        assertFalse(tracker.isHeld(HIDKeyboard.HID_KEY_A));
    }

    @Test
    @DisplayName("releaseAll emits a release for every held key — the critical screen-close path")
    void releaseAllFlushesHeld() {
        tracker.press(HIDKeyboard.HID_KEY_LEFTSHIFT);
        tracker.press(HIDKeyboard.HID_KEY_A);
        tracker.press(HIDKeyboard.HID_KEY_SPACE);

        // Simulate "user closes the screen while holding 3 keys".
        tracker.releaseAll();

        assertEquals(3, releases.size(), "every held key must get a release event");
        assertTrue(releases.contains(HIDKeyboard.HID_KEY_LEFTSHIFT));
        assertTrue(releases.contains(HIDKeyboard.HID_KEY_A));
        assertTrue(releases.contains(HIDKeyboard.HID_KEY_SPACE));
        assertEquals(0, tracker.heldCount(), "releaseAll must clear the held set");
    }

    @Test
    @DisplayName("releaseAll is a no-op when nothing is held")
    void releaseAllEmpty() {
        tracker.releaseAll();
        assertTrue(releases.isEmpty());
    }

    @Test
    @DisplayName("release with no prior press still forwards (avoid stuck-down states)")
    void releaseWithoutPressStillForwards() {
        // GLFW may fire release without press if the screen opened while a
        // key was already down. We forward anyway — a spurious release is
        // harmless; a missed release is not.
        tracker.release(HIDKeyboard.HID_KEY_A);
        assertEquals(1, releases.size());
    }

    @Test
    @DisplayName("double press doesn't duplicate the held entry")
    void doublePressIsIdempotent() {
        tracker.press(HIDKeyboard.HID_KEY_A);
        tracker.press(HIDKeyboard.HID_KEY_A);
        // Both presses are forwarded (caller's choice), but we track it once.
        assertEquals(2, presses.size());
        assertEquals(1, tracker.heldCount());
    }
}
