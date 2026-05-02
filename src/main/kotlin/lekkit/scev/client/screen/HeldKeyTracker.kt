/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.screen

import java.util.function.Consumer

/**
 * Tracks which HID keys we've emitted a press for without a following release.
 *
 * If [MachineScreen] closes while the player is holding shift, the VM sees
 * shift as stuck down forever. The tracker ensures we flush a release for
 * every dangling press. Lives here (not inline in the screen) so unit tests
 * can exercise the logic without instantiating a full screen stack.
 *
 * Write-only from the caller's perspective — doesn't return which keys are
 * pressed, just invokes the press/release sinks. Ignores HID 0 silently.
 */
class HeldKeyTracker(
    private val pressSink: Consumer<Byte>,
    private val releaseSink: Consumer<Byte>,
) {
    private val held = HashSet<Byte>()

    /** Register a press. Idempotent: pressing the same key twice records once. */
    fun press(hid: Byte) {
        if (hid == 0.toByte()) return
        held.add(hid)
        pressSink.accept(hid)
    }

    /** Register a release. */
    fun release(hid: Byte) {
        if (hid == 0.toByte()) return
        // Remove if held; either way still forward the release. GLFW may have
        // generated one without a matching press if the screen opened while
        // the key was already down — better to tell the VM "released"
        // spuriously than to have it believe the key is still held.
        held.remove(hid)
        releaseSink.accept(hid)
    }

    /** Emit a release for every key we've recorded a press for, then clear. */
    fun releaseAll() {
        for (hid in held) releaseSink.accept(hid)
        held.clear()
    }

    /** Number of currently-held keys. Exposed for tests. */
    fun heldCount(): Int = held.size

    /** True if the given key is currently tracked as held. Exposed for tests. */
    fun isHeld(hid: Byte): Boolean = hid in held
}
