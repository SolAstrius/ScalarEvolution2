/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.main;

import lekkit.scev.client.ScevClient;
import lekkit.scev.client.render.ScevRenderers;
import lekkit.scev.network.ScevNetwork;
import lekkit.scev.server.MachineManager;
import lekkit.scev.server.StorageManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

@Mod(ScalarEvolution.MODID)
public final class ScalarEvolution {
    public static final String MODID = "scev";
    public static final String NAME = "Scalar Evolution";

    public static ResourceLocation rl(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }

    public ScalarEvolution(IEventBus modBus, ModContainer container) {
        // Register all deferred registries
        ScevRegistry.register(modBus);
        ScevDataComponents.register(modBus);
        ScevNetwork.register(modBus);

        // Common setup (both sides)
        modBus.addListener(ScalarEvolution::onCommonSetup);

        // CC: Tweaked integration — soft-dep; no-op if CC isn't installed.
        // The bootstrap class carries no CC imports, so classloading it on
        // a CC-less server doesn't fault.
        lekkit.scev.compat.cc.ScevCCBootstrap.registerIfPresent();

        // Datagen
        modBus.addListener(lekkit.scev.datagen.DataGenerators::onGatherData);

        // Client-only
        if (net.neoforged.fml.loading.FMLEnvironment.dist == Dist.CLIENT) {
            modBus.addListener(ScevClient::onClientSetup);
            modBus.addListener(ScevClient::onRegisterReloadListeners);
            modBus.addListener(ScevRenderers::registerBlockEntityRenderers);
            modBus.addListener(ScevRenderers::registerMenuScreens);
        }

        // Config
        container.registerConfig(ModConfig.Type.COMMON, ScevConfig.SPEC);

        // Game event bus (not mod bus)
        // ServerScope wraps the MinecraftServer tick thread as a Kotlin
        // CoroutineDispatcher + lifecycle-bound CoroutineScope. Per-machine
        // scopes (ScevRpcManager, future consumers) parent onto this scope
        // so server stop cancels everything in one hop.
        NeoForge.EVENT_BUS.addListener(lekkit.scev.common.ServerScope::onServerStarting);
        NeoForge.EVENT_BUS.addListener(lekkit.scev.common.ServerScope::onServerStopping);
        NeoForge.EVENT_BUS.addListener(ScalarEvolution::onServerStarting);
        NeoForge.EVENT_BUS.addListener(ScalarEvolution::onServerStopping);
        // SoundStreamManager.onServerTick dispatches queued PCM frames to
        // nearby players every server tick. Registering by method reference
        // against the explicit event type bypasses @SubscribeEvent scanning —
        // the method is static and self-contained.
        NeoForge.EVENT_BUS.addListener(lekkit.scev.server.SoundStreamManager::onServerTick);
        // ScevRpcManager.onServerTick drains every live machine's RPC UART,
        // decodes frames, dispatches to the registered handlers, and pushes
        // responses back toward the guest.
        NeoForge.EVENT_BUS.addListener(lekkit.scev.rpc.ScevRpcManager::onServerTick);
    }

    private static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(lekkit.scev.server.NativeLoader::ensureLoaded);
        // Install built-in firmwares (LINUX, OPENSBI_ONLY, OPEN_FIRMWARE) and
        // disk templates. Other mods can register their own entries from
        // their own FMLCommonSetupEvent — registration order is preserved by
        // the registries' LinkedHashMap backing. Safe to register outside
        // enqueueWork: registration is a plain concurrent-map put.
        lekkit.scev.machine.firmware.FirmwareRegistry.registerBuiltins();
        lekkit.scev.machine.storage.DiskTemplateRegistry.registerBuiltins();
    }

    private static void onServerStarting(ServerStartingEvent event) {
        // Rebind NVMe / snapshot storage into the active world's save folder
        // so disk contents travel with the world (backup, copy, delete).
        StorageManager.onServerStarting(event.getServer());
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        MachineManager.finishAllMachines();
        StorageManager.onServerStopping();
    }
}
