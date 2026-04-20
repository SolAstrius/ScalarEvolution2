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

    /** Called by the network handler when a {@link DisplayPayload} arrives. */
    public static synchronized void acceptRemote(DisplayPayload payload) {
        DisplayState s = createOrResize(payload.machineUuid(), payload.width(), payload.height());
        if (s != null) s.updateRemoteBuffer(payload.pixels());
    }
}
