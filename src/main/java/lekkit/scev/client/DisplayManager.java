/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lekkit.scev.network.DisplayPayload;
import lekkit.scev.server.MachineManager;
import lekkit.scev.server.MachineState;

/**
 * Client-side cache of framebuffer state per-machine. In single-player, mirrors the
 * server-side machine state directly for zero-copy rendering.
 */
public final class DisplayManager {
    private static final Map<UUID, DisplayState> DISPLAYS = new HashMap<>();
    private static final boolean OPTIMIZE_SINGLEPLAYER = true;

    private DisplayManager() {}

    public static synchronized DisplayState get(UUID uuid) {
        DisplayState s = DISPLAYS.get(uuid);

        // Evict stale singleplayer cache entries: the referenced MachineState
        // was destroyed (power-off) but this cache still holds a DisplayState
        // pointing at the dead backend. Without eviction, the next lookup
        // would keep returning the stale entry and the client would show the
        // old VM's final frame indefinitely — including across a subsequent
        // power-on that would have built a fresh MachineState.
        if (s != null && s.isStale()) {
            DISPLAYS.remove(uuid);
            s.destroy();
            s = null;
        }

        if (OPTIMIZE_SINGLEPLAYER && s == null) {
            MachineState ms = MachineManager.getMachineState(uuid);
            if (ms != null && ms.getDisplay() != null) {
                s = new DisplayState(ms);
                DISPLAYS.put(uuid, s);
            }
        }
        return s;
    }

    public static synchronized DisplayState createOrResize(UUID uuid, int w, int h) {
        DisplayState existing = DISPLAYS.get(uuid);
        if (existing != null && (existing.getWidth() != w || existing.getHeight() != h)) {
            existing.destroy();
            DISPLAYS.remove(uuid);
            existing = null;
        }
        if (existing == null) {
            existing = new DisplayState(uuid, w, h);
            DISPLAYS.put(uuid, existing);
        }
        return existing;
    }

    public static synchronized void destroy(UUID uuid) {
        DisplayState s = DISPLAYS.remove(uuid);
        if (s != null) s.destroy();
    }

    public static synchronized void recycleAll() {
        DISPLAYS.values().forEach(DisplayState::destroy);
        DISPLAYS.clear();
    }

    /**
     * Called by the network handler when a {@link DisplayPayload} arrives.
     *
     * <p>Two sentinel semantics:
     * <ul>
     *   <li>{@code width == 0 || height == 0} → dispose the cached DisplayState
     *       for this UUID. The server emits this on power-off / VM teardown so
     *       the client stops rendering the last frame.</li>
     *   <li>Otherwise → create or resize the cached DisplayState and copy in
     *       the fresh pixels.</li>
     * </ul>
     *
     * <p>Short-circuit: when {@link #OPTIMIZE_SINGLEPLAYER} is in effect and
     * a live {@link MachineState} is registered locally for this UUID, skip
     * the remote buffer entirely — the local machine's direct ByteBuffer is
     * already zero-copy-visible to {@link #get}. This preserves the
     * singleplayer rendering path while the server still broadcasts for any
     * remote players (LAN guests on an integrated server, for example).
     */
    public static synchronized void acceptRemote(DisplayPayload payload) {
        if (OPTIMIZE_SINGLEPLAYER && MachineManager.getMachineState(payload.machineUuid()) != null) {
            return;
        }
        if (payload.width() == 0 || payload.height() == 0) {
            destroy(payload.machineUuid());
            return;
        }
        DisplayState s = createOrResize(payload.machineUuid(), payload.width(), payload.height());
        if (s != null) s.updateRemoteBuffer(payload.pixels());
    }
}
