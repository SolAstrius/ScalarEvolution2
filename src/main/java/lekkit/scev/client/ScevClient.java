/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client;

import lekkit.scev.client.sections.ScevSectionManager;
import lekkit.scev.server.MachineManager;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client-only setup hooks.
 *
 * <p>Also hosts the game-pause hook: in single-player, when the pause screen
 * opens, {@link MachineManager#pauseAllMachines()} stops all running
 * machines' worker threads until the screen is dismissed. This prevents
 * machines from continuing to tick while the player has the game "paused",
 * which would be surprising.
 *
 * <p>Multiplayer has no true pause, so this hook is a no-op on dedicated
 * clients. Integrated server ticks freeze when the game pauses but native
 * RVVM worker threads don't — they need the explicit pause.
 */
public final class ScevClient {
    private static boolean lastPaused = false;

    private ScevClient() {}

    public static void onClientSetup(FMLClientSetupEvent e) {
        // Register the per-tick pause watcher. Using the game bus (not the
        // mod bus) because ClientTickEvent fires on the game bus.
        NeoForge.EVENT_BUS.addListener(ScevClient::onClientTick);
        // DisplayManager runs the A/V-sync video jitter buffer: every
        // tick it picks the newest buffered frame whose PTS is <= the
        // media clock and presents it.
        NeoForge.EVENT_BUS.addListener(DisplayManager::onClientTick);
    }

    public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent e) {
        // Creative-tab section definitions live in resource packs. Reloads
        // on /reload and on world enter.
        e.registerReloadListener(ScevSectionManager.instance());
    }

    private static void onClientTick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        // Only meaningful when the integrated server is running — on a
        // dedicated client, pausing doesn't stop the remote server anyway.
        if (mc.hasSingleplayerServer()) {
            boolean paused = mc.isPaused();
            if (paused != lastPaused) {
                lastPaused = paused;
                if (paused) MachineManager.pauseAllMachines();
                else MachineManager.unpauseAllMachines();
            }
        } else if (lastPaused) {
            // Left single-player while "paused" — normalise the tracked state.
            MachineManager.unpauseAllMachines();
            lastPaused = false;
        }

        // Drain queued PCM into OpenAL buffers, advance playback, reap idle
        // sources. Runs here (on the client tick thread) so every OpenAL
        // call stays on the audio-context thread. Netty handlers don't
        // touch OpenAL directly — they only buffer bytes for this tick.
        SoundStreamPlayer.clientTick();

        // On disconnect (mc.level becomes null after being non-null), drop
        // every streaming source so we don't leak OpenAL handles into the
        // next connection.
        if (mc.level == null && SoundStreamPlayer.liveSourceCount() > 0) {
            SoundStreamPlayer.destroyAll();
        }
    }
}
