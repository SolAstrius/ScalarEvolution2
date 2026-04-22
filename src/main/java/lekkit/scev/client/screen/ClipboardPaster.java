/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.screen;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;
import lekkit.rvvm.HIDKeyboard;

/**
 * Queued HID-event emitter used to "type" clipboard text into the VM when
 * the player hits Ctrl+Shift+V in {@link MachineScreen}. Not tied to
 * Minecraft — the screen passes in press/release sinks that wrap the
 * network payload, but the paster itself is a plain queue over bytes so
 * it's unit-testable without any render context.
 *
 * <p><b>Why queued, not burst.</b> Blasting the entire clipboard as one
 * burst overruns the guest's HID buffer and the RVVM→guest path; most
 * guests drop everything past ~10–20 events. Instead we enqueue and
 * release a small batch per client tick so the guest's USB stack can
 * keep up.
 *
 * <p><b>Shift grouping.</b> {@link #queueText} tracks whether we've
 * pressed LEFTSHIFT for the current run of shifted characters and only
 * toggles it when the required shift state changes. So pasting
 * {@code "HELLO"} emits one SHIFT press, five letter press/releases,
 * and one SHIFT release — not five redundant SHIFT cycles.
 *
 * <p>Unmappable characters (non-ASCII, control codes other than \n and
 * \t) are skipped silently; {@code \r} is discarded so CRLF clipboards
 * produce clean single-Enter keystrokes on the guest.
 */
public final class ClipboardPaster {
    /** Default events-per-tick. At 20 tps this is ~80 HID events/s — comfortable for any guest. */
    public static final int DEFAULT_EVENTS_PER_TICK = 4;

    private final Deque<Event> queue = new ArrayDeque<>();
    private final Consumer<Byte> pressSink;
    private final Consumer<Byte> releaseSink;
    private final int eventsPerTick;

    public ClipboardPaster(Consumer<Byte> pressSink, Consumer<Byte> releaseSink) {
        this(pressSink, releaseSink, DEFAULT_EVENTS_PER_TICK);
    }

    public ClipboardPaster(Consumer<Byte> pressSink, Consumer<Byte> releaseSink, int eventsPerTick) {
        this.pressSink = pressSink;
        this.releaseSink = releaseSink;
        this.eventsPerTick = Math.max(1, eventsPerTick);
    }

    /** Enqueue a bare press event. Exposed for the modifier-suppression preamble. */
    public void queuePress(byte hid) {
        if (hid == 0) return;
        queue.addLast(new Event(true, hid));
    }

    /** Enqueue a bare release event. */
    public void queueRelease(byte hid) {
        if (hid == 0) return;
        queue.addLast(new Event(false, hid));
    }

    /**
     * Enqueue the HID events that would type {@code text} on a US-QWERTY guest.
     * Returns the number of source characters that produced at least one HID
     * event — characters we couldn't map are skipped and not counted.
     */
    public int queueText(String text) {
        int typed = 0;
        boolean shiftHeld = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r') continue; // CR is discarded; the LF that follows becomes ENTER.
            UsQwerty.Binding b = UsQwerty.forChar(c);
            if (b == null) continue;

            // Only toggle shift when the required state actually changes.
            if (b.shift() && !shiftHeld) {
                queue.addLast(new Event(true, HIDKeyboard.HID_KEY_LEFTSHIFT));
                shiftHeld = true;
            } else if (!b.shift() && shiftHeld) {
                queue.addLast(new Event(false, HIDKeyboard.HID_KEY_LEFTSHIFT));
                shiftHeld = false;
            }
            queue.addLast(new Event(true, b.hid()));
            queue.addLast(new Event(false, b.hid()));
            typed++;
        }
        if (shiftHeld) {
            queue.addLast(new Event(false, HIDKeyboard.HID_KEY_LEFTSHIFT));
        }
        return typed;
    }

    /** Drain up to {@link #eventsPerTick} events from the head of the queue. */
    public void tick() {
        for (int i = 0; i < eventsPerTick && !queue.isEmpty(); i++) {
            Event e = queue.removeFirst();
            if (e.press) pressSink.accept(e.hid);
            else releaseSink.accept(e.hid);
        }
    }

    /** True when the queue has been fully drained. */
    public boolean isIdle() { return queue.isEmpty(); }

    /** Pending event count — exposed for tests. */
    public int queueSize() { return queue.size(); }

    /** Discard any pending events. Called when the screen closes mid-paste. */
    public void clear() { queue.clear(); }

    /** Recorded HID event — press or release of a single HID usage id. */
    private record Event(boolean press, byte hid) {}
}
