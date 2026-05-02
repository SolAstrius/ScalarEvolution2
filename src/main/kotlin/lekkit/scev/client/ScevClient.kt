/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client

import lekkit.scev.client.sections.ScevSectionManager
import lekkit.scev.client.terminal.MltermNative
import lekkit.scev.server.MachineManager
import net.minecraft.client.Minecraft
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent
import net.neoforged.neoforge.common.NeoForge

/**
 * Client-only setup hooks.
 *
 * Hosts the game-pause hook: in single-player, when the pause screen
 * opens, [MachineManager.pauseAllMachines] stops all running machines'
 * worker threads until the screen is dismissed. Multiplayer has no true
 * pause, so this hook is a no-op on dedicated clients. The integrated
 * server's ticks freeze when the game pauses but native RVVM worker
 * threads don't — they need the explicit pause.
 */
object ScevClient {
    private var lastPaused = false

    @JvmStatic fun onClientSetup(e: FMLClientSetupEvent) {
        // Per-tick pause watcher uses the game bus, not the mod bus.
        NeoForge.EVENT_BUS.addListener(ScevClient::onClientTick)
        // DisplayManager's per-tick A/V-sync video jitter buffer.
        NeoForge.EVENT_BUS.addListener(DisplayManager::onClientTick)
        // Eagerly extract + load libscev_term so the first VT100
        // open isn't blocked behind a 3 MiB jar-resource extract +
        // System.load. Failure logs and proceeds — the GUI throws
        // a clear "native isn't loaded" error when opened later.
        MltermNative.ensureLoaded()
    }

    @JvmStatic fun onRegisterReloadListeners(e: RegisterClientReloadListenersEvent) {
        // Creative-tab section definitions live in resource packs.
        e.registerReloadListener(ScevSectionManager.instance())
    }

    private fun onClientTick(e: ClientTickEvent.Post) {
        val mc = Minecraft.getInstance()
        // Only meaningful with the integrated server — on a dedicated
        // client, pausing doesn't stop the remote server anyway.
        if (mc.hasSingleplayerServer()) {
            val paused = mc.isPaused
            if (paused != lastPaused) {
                lastPaused = paused
                if (paused) MachineManager.pauseAllMachines() else MachineManager.unpauseAllMachines()
            }
        } else if (lastPaused) {
            // Left single-player while "paused" — normalise the tracked state.
            MachineManager.unpauseAllMachines()
            lastPaused = false
        }

        // Drain queued PCM into OpenAL buffers, advance playback, reap
        // idle sources. Runs on the client tick thread so every OpenAL
        // call stays on the audio-context thread. Netty handlers don't
        // touch OpenAL — they only buffer bytes for this tick.
        SoundStreamPlayer.clientTick()

        // On disconnect (mc.level becomes null after being non-null),
        // drop every streaming source so OpenAL handles don't leak into
        // the next connection.
        if (mc.level == null && SoundStreamPlayer.liveSourceCount() > 0) {
            SoundStreamPlayer.destroyAll()
        }
    }
}
