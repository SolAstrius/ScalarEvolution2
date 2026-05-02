/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.screen

import java.util.ArrayDeque
import java.util.function.Consumer
import lekkit.rvvm.HIDKeyboard

/**
 * Queued HID-event emitter used to "type" clipboard text into the VM when
 * the player hits Ctrl+Shift+V in [MachineScreen]. Not tied to Minecraft —
 * the screen passes in press/release sinks that wrap the network payload,
 * but the paster itself is a plain queue over bytes so it's unit-testable
 * without any render context.
 *
 * **Why queued, not burst — and why exactly one event per tick.**
 * RVVM's HID keyboard reports pressed keys as a bitmap (see
 * `hid_keyboard_fill_pressed_keys` in `hid-keyboard.c`) and enumerates
 * them in HID-code order, not press order. The guest's USB stack polls
 * that report on its own schedule (a few ms per cycle). If we deliver
 * multiple press/release events between two polls, they coalesce into a
 * single chord and the guest sees the keys sorted by HID usage id —
 * pasting `"world"` arrives as `"dlorw"`. Pacing at one event per client
 * tick (50 ms at 20 tps) guarantees the guest polls between every
 * transition. Caps throughput at ~10 chars/s but preserves order, which
 * is the only thing the user actually cares about when pasting text.
 *
 * **Shift grouping.** [queueText] tracks whether we've pressed LEFTSHIFT
 * for the current run of shifted characters and only toggles when the
 * required shift state changes. So pasting `"HELLO"` emits one SHIFT
 * press, five letter press/releases, and one SHIFT release — not five
 * redundant SHIFT cycles.
 *
 * Unmappable characters (non-ASCII, control codes other than `\n` and
 * `\t`) are skipped silently; `\r` is discarded so CRLF clipboards
 * produce clean single-Enter keystrokes on the guest.
 */
class ClipboardPaster @JvmOverloads constructor(
    private val pressSink: Consumer<Byte>,
    private val releaseSink: Consumer<Byte>,
    eventsPerTick: Int = DEFAULT_EVENTS_PER_TICK,
) {
    private val eventsPerTick: Int = maxOf(1, eventsPerTick)
    private val queue: ArrayDeque<Event> = ArrayDeque()

    /** Enqueue a bare press event. Exposed for the modifier-suppression preamble. */
    fun queuePress(hid: Byte) {
        if (hid == 0.toByte()) return
        queue.addLast(Event(true, hid))
    }

    /** Enqueue a bare release event. */
    fun queueRelease(hid: Byte) {
        if (hid == 0.toByte()) return
        queue.addLast(Event(false, hid))
    }

    /**
     * Enqueue the HID events that would type [text] on a US-QWERTY guest.
     * Returns the number of source characters that produced at least one
     * HID event — characters we couldn't map are skipped and not counted.
     */
    fun queueText(text: String): Int {
        var typed = 0
        var shiftHeld = false
        for (c in text) {
            if (c == '\r') continue   // CR discarded; the LF that follows becomes ENTER.
            val b = UsQwerty.forChar(c) ?: continue

            // Only toggle shift when the required state actually changes.
            if (b.shift && !shiftHeld) {
                queue.addLast(Event(true, HIDKeyboard.HID_KEY_LEFTSHIFT))
                shiftHeld = true
            } else if (!b.shift && shiftHeld) {
                queue.addLast(Event(false, HIDKeyboard.HID_KEY_LEFTSHIFT))
                shiftHeld = false
            }
            queue.addLast(Event(true, b.hid))
            queue.addLast(Event(false, b.hid))
            typed++
        }
        if (shiftHeld) queue.addLast(Event(false, HIDKeyboard.HID_KEY_LEFTSHIFT))
        return typed
    }

    /** Drain up to [eventsPerTick] events from the head of the queue. */
    fun tick() {
        var i = 0
        while (i < eventsPerTick && queue.isNotEmpty()) {
            val e = queue.removeFirst()
            if (e.press) pressSink.accept(e.hid) else releaseSink.accept(e.hid)
            i++
        }
    }

    /** True when the queue has been fully drained. */
    fun isIdle(): Boolean = queue.isEmpty()

    /** Pending event count — exposed for tests. */
    fun queueSize(): Int = queue.size

    /** Discard any pending events. Called when the screen closes mid-paste. */
    fun clear() = queue.clear()

    /** Recorded HID event — press or release of a single HID usage id. */
    private data class Event(val press: Boolean, val hid: Byte)

    companion object {
        /**
         * One HID event per client tick. At 20 tps that's 20 events/s ≈ 10 chars/s.
         * Anything higher lets multiple events fall inside a single guest HID poll
         * window, where RVVM's bitmap report coalesces them into a chord sorted by
         * HID code — pasted text arrives shuffled. See class kdoc.
         */
        const val DEFAULT_EVENTS_PER_TICK: Int = 1
    }
}
