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
import lekkit.scev.client.screen.ClipboardPaster;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exercises the paced paste queue without any Minecraft context. The
 * recorded events list captures the exact sequence the screen's packet
 * sinks would emit — the same property {@link HeldKeyTrackerTest} relies
 * on for the press/release-balance contract.
 */
class ClipboardPasterTest {

    /**
     * Single event log: each entry is a one-char prefix ("+" press / "-"
     * release) plus the HID value as an unsigned int. Easier to assert on
     * than two parallel lists when event ordering is the thing under test.
     */
    private final List<String> events = new ArrayList<>();
    private final ClipboardPaster paster = new ClipboardPaster(
            hid -> events.add("+" + (hid & 0xFF)),
            hid -> events.add("-" + (hid & 0xFF)),
            1024); // large per-tick so we can drain in one go during tests

    private void drainAll() {
        while (!paster.isIdle()) paster.tick();
    }

    @Test
    @DisplayName("Typing 'a' emits just press(A), release(A) — no shift cycling")
    void singleLowercase() {
        paster.queueText("a");
        drainAll();
        assertEquals(List.of("+4", "-4"), events, "a -> press A, release A");
    }

    @Test
    @DisplayName("Typing 'A' wraps the press/release in SHIFT press/release")
    void singleUppercase() {
        paster.queueText("A");
        drainAll();
        assertEquals(List.of(
                "+" + (HIDKeyboard.HID_KEY_LEFTSHIFT & 0xFF),
                "+4",
                "-4",
                "-" + (HIDKeyboard.HID_KEY_LEFTSHIFT & 0xFF)
        ), events);
    }

    @Test
    @DisplayName("Runs of shifted characters share one SHIFT press/release")
    void shiftRunGrouping() {
        paster.queueText("ABC");
        drainAll();
        // Expected sequence: SHIFT↓  A↓A↑  B↓B↑  C↓C↑  SHIFT↑
        //
        // The critical property here is that SHIFT is not re-pressed between
        // A / B / C. A naive implementation would have 9 presses + 9 releases;
        // we want 4 + 4.
        int shift = HIDKeyboard.HID_KEY_LEFTSHIFT & 0xFF;
        assertEquals(List.of(
                "+" + shift,
                "+4", "-4",
                "+5", "-5",
                "+6", "-6",
                "-" + shift
        ), events);
    }

    @Test
    @DisplayName("Transitions between shifted and unshifted release/press SHIFT at the boundary")
    void shiftTransitions() {
        paster.queueText("aA");
        drainAll();
        int shift = HIDKeyboard.HID_KEY_LEFTSHIFT & 0xFF;
        assertEquals(List.of(
                "+4", "-4",              // 'a'
                "+" + shift,              // shift on
                "+4", "-4",              // 'A'
                "-" + shift               // shift off at end-of-text
        ), events);

        events.clear();
        paster.queueText("Aa");
        drainAll();
        assertEquals(List.of(
                "+" + shift,              // shift on
                "+4", "-4",              // 'A'
                "-" + shift,              // shift off before 'a'
                "+4", "-4"               // 'a'
        ), events);
    }

    @Test
    @DisplayName("Newlines become HID_KEY_ENTER; CR is discarded (CRLF -> one Enter)")
    void newlineHandling() {
        paster.queueText("a\r\nb");
        drainAll();
        int enter = HIDKeyboard.HID_KEY_ENTER & 0xFF;
        // Expect 'a', Enter, 'b' — no trace of the CR.
        assertEquals(List.of(
                "+4", "-4",
                "+" + enter, "-" + enter,
                "+5", "-5"
        ), events);
    }

    @Test
    @DisplayName("Unmappable characters are skipped silently without leaking SHIFT state")
    void unmappableSkipped() {
        // é has no mapping; the surrounding 'a' and 'A' should type normally.
        paster.queueText("aéA");
        drainAll();
        int shift = HIDKeyboard.HID_KEY_LEFTSHIFT & 0xFF;
        assertEquals(List.of(
                "+4", "-4",              // 'a'
                "+" + shift,              // shift on for 'A'
                "+4", "-4",              // 'A'
                "-" + shift               // shift off at end
        ), events);
    }

    @Test
    @DisplayName("Tick drains at most eventsPerTick events")
    void ticksPaceOutput() {
        ClipboardPaster paced = new ClipboardPaster(
                hid -> events.add("+" + (hid & 0xFF)),
                hid -> events.add("-" + (hid & 0xFF)),
                2);
        paced.queueText("abc"); // 6 events: press/release for a, b, c
        paced.tick();
        assertEquals(2, events.size(), "first tick drains exactly 2 events");
        paced.tick();
        assertEquals(4, events.size());
        paced.tick();
        assertEquals(6, events.size());
        assertTrue(paced.isIdle());
    }

    @Test
    @DisplayName("Empty text produces no events and leaves the paster idle")
    void emptyInput() {
        paster.queueText("");
        assertTrue(paster.isIdle());
        paster.tick();
        assertTrue(events.isEmpty());
    }

    @Test
    @DisplayName("clear() drops all pending events — paste interrupted by screen close")
    void clearDropsPending() {
        paster.queueText("hello world");
        assertFalse(paster.isIdle());
        paster.clear();
        assertTrue(paster.isIdle());
        paster.tick();
        assertTrue(events.isEmpty(), "no events should fire after clear");
    }

    @Test
    @DisplayName("queuePress / queueRelease emit bare events — used for the modifier-suppression preamble")
    void bareEvents() {
        paster.queuePress(HIDKeyboard.HID_KEY_LEFTCTRL);
        paster.queueRelease(HIDKeyboard.HID_KEY_LEFTCTRL);
        drainAll();
        int ctrl = HIDKeyboard.HID_KEY_LEFTCTRL & 0xFF;
        assertEquals(List.of("+" + ctrl, "-" + ctrl), events);
    }

    @Test
    @DisplayName("Zero HID byte is ignored by bare queue APIs (consistent with HeldKeyTracker)")
    void zeroHidIgnored() {
        paster.queuePress((byte) 0);
        paster.queueRelease((byte) 0);
        drainAll();
        assertTrue(events.isEmpty());
        assertTrue(paster.isIdle());
    }

    @Test
    @DisplayName("queueText returns the number of source characters successfully mapped")
    void queueTextReturnsTypedCount() {
        assertEquals(3, paster.queueText("abc"));
        paster.clear();
        events.clear();

        // 'é' unmappable, '\r' swallowed — only 'a' and 'b' are typed.
        assertEquals(2, paster.queueText("a\réb"));
        paster.clear();
        events.clear();

        // Text consisting entirely of swallowed + unmappable yields zero.
        assertEquals(0, paster.queueText("\r\ré"));
    }
}
