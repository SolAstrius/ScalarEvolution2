/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.screen;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Tracks which HID keys we've emitted a press for without a following release.
 *
 * <p>Why this is its own class: if {@link MachineScreen} closes while the
 * player is holding shift, the VM sees shift as stuck down forever. The
 * tracker ensures we flush a release for every dangling press. Lives here
 * (not inline in the screen) so unit tests can exercise the logic without
 * instantiating a full screen stack.
 *
 * <p>The tracker is write-only from the caller's perspective — it doesn't
 * return which keys are pressed, it just invokes the press/release sinks.
 * Ignores HID 0 (meaning "no mapping") silently.
 */
public final class HeldKeyTracker {
    private final Set<Byte> held = new HashSet<>();
    private final Consumer<Byte> pressSink;
    private final Consumer<Byte> releaseSink;

    public HeldKeyTracker(Consumer<Byte> pressSink, Consumer<Byte> releaseSink) {
        this.pressSink = pressSink;
        this.releaseSink = releaseSink;
    }

    /** Register a press. Idempotent: pressing the same key twice records once. */
    public void press(byte hid) {
        if (hid == 0) return;
        held.add(hid);
        pressSink.accept(hid);
    }

    /** Register a release. No-op if the key wasn't held. */
    public void release(byte hid) {
        if (hid == 0) return;
        if (held.remove(hid)) {
            releaseSink.accept(hid);
        } else {
            // Still forward the release — GLFW may have generated one without
            // a matching press if the screen opened while the key was already
            // down. Better to tell the VM "released" spuriously than to have
            // the VM believe the key is still held.
            releaseSink.accept(hid);
        }
    }

    /** Emit a release for every key we've recorded a press for, then clear. */
    public void releaseAll() {
        for (Byte hid : held) releaseSink.accept(hid);
        held.clear();
    }

    /** Number of currently-held keys. Exposed for tests. */
    public int heldCount() { return held.size(); }

    /** True if the given key is currently tracked as held. Exposed for tests. */
    public boolean isHeld(byte hid) { return held.contains(hid); }
}
