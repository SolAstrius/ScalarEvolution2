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

        // Datagen
        modBus.addListener(lekkit.scev.datagen.DataGenerators::onGatherData);

        // Client-only
        if (net.neoforged.fml.loading.FMLEnvironment.dist == Dist.CLIENT) {
            modBus.addListener(ScevClient::onClientSetup);
            modBus.addListener(ScevRenderers::registerBlockEntityRenderers);
            modBus.addListener(ScevRenderers::registerMenuScreens);
        }

        // Config
        container.registerConfig(ModConfig.Type.COMMON, ScevConfig.SPEC);

        // Game event bus (not mod bus)
        NeoForge.EVENT_BUS.addListener(ScalarEvolution::onServerStopping);
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

    private static void onServerStopping(ServerStoppingEvent event) {
        MachineManager.finishAllMachines();
    }
}
