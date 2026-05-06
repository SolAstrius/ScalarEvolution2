/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.main

import lekkit.scev.client.ScevClient
import lekkit.scev.client.render.ScevRenderers
import lekkit.scev.common.ServerScope
import lekkit.scev.compat.cc.ScevCCBootstrap
import lekkit.scev.datagen.DataGenerators
import lekkit.scev.machine.firmware.FirmwareRegistry
import lekkit.scev.machine.storage.DiskTemplateRegistry
import lekkit.scev.network.ScevNetwork
import lekkit.scev.rpc.ScevRpcManager
import lekkit.scev.server.MachineManager
import lekkit.scev.server.NativeLoader
import lekkit.scev.server.SoundStreamManager
import lekkit.scev.server.StorageManager
import lekkit.scev.server.gc.DiskImageGc
import lekkit.scev.server.gc.DiskImageRegistry
import lekkit.scev.server.gc.GcPolicy
import lekkit.scev.server.gc.GcScheduler
import lekkit.scev.server.gc.ItemLifecycleListener
import lekkit.scev.server.gc.ScannerRegistry
import lekkit.scev.server.gc.ScevGc
import lekkit.scev.server.gc.ScevGcCommand
import lekkit.scev.server.gc.scanners.BlockEntityScanner
import lekkit.scev.server.gc.scanners.EntityScanner
import lekkit.scev.server.gc.scanners.PlayerInventoryScanner
import lekkit.scev.server.gc.scanners.RunningMachineScanner
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.storage.LevelResource
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.Mod
import net.neoforged.fml.config.ModConfig
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.server.ServerStartingEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent

@Mod(ScalarEvolution.MODID)
class ScalarEvolution(modBus: IEventBus, container: ModContainer) {

    init {
        // Register all deferred registries
        ScevRegistry.register(modBus)
        ScevDataComponents.register(modBus)
        ScevNetwork.register(modBus)

        // Per-BE capability bindings — IItemHandler on every
        // processing machine for cross-mod automation (Create,
        // Mekanism, AE2, IE etc).
        lekkit.scev.blockentity.ProcessingMachineCapabilities.register(modBus)

        // Common setup (both sides)
        modBus.addListener(::onCommonSetup)

        // CC: Tweaked integration — soft-dep; no-op if CC isn't installed.
        // The bootstrap class carries no CC imports, so classloading it on
        // a CC-less server doesn't fault.
        ScevCCBootstrap.registerIfPresent()

        // Datagen
        modBus.addListener(DataGenerators::onGatherData)

        // Client-only
        if (FMLEnvironment.dist == Dist.CLIENT) {
            modBus.addListener(ScevClient::onClientSetup)
            modBus.addListener(ScevClient::onRegisterReloadListeners)
            modBus.addListener(ScevRenderers::registerBlockEntityRenderers)
            modBus.addListener(ScevRenderers::registerMenuScreens)
            modBus.addListener(ScevRenderers::registerClientExtensions)
            modBus.addListener(lekkit.scev.client.render.CrtFxShader::onRegisterShaders)
        }

        // Config
        container.registerConfig(ModConfig.Type.COMMON, ScevConfig.SPEC)

        // Game event bus (not mod bus)
        // ServerScope wraps the MinecraftServer tick thread as a Kotlin
        // CoroutineDispatcher + lifecycle-bound CoroutineScope. Per-machine
        // scopes (ScevRpcManager, future consumers) parent onto this scope
        // so server stop cancels everything in one hop.
        NeoForge.EVENT_BUS.addListener(ServerScope::onServerStarting)
        NeoForge.EVENT_BUS.addListener(ServerScope::onServerStopping)
        NeoForge.EVENT_BUS.addListener(::onServerStarting)
        NeoForge.EVENT_BUS.addListener(::onServerStopping)
        NeoForge.EVENT_BUS.addListener(::onRegisterCommands)
        // SoundStreamManager.onServerTick dispatches queued PCM frames to
        // nearby players every server tick. Registering by method reference
        // against the explicit event type bypasses @SubscribeEvent scanning —
        // the method is static and self-contained.
        NeoForge.EVENT_BUS.addListener(SoundStreamManager::onServerTick)
        // ScevRpcManager.onServerTick drains every live machine's RPC UART,
        // decodes frames, dispatches to the registered handlers, and pushes
        // responses back toward the guest.
        NeoForge.EVENT_BUS.addListener(ScevRpcManager::onServerTick)

        // VT100 BE: ship the kernel-console replay ring to a player
        // the moment they open a TerminalMenu, so they don't stare at
        // a black screen until the next byte from the guest. Lives
        // on the BE companion because the BE owns the bound-machine
        // → menu correspondence.
        NeoForge.EVENT_BUS.addListener(
            lekkit.scev.blockentity.TerminalBlockEntity.Companion::onMenuOpen
        )
        NeoForge.EVENT_BUS.addListener(
            lekkit.scev.blockentity.TerminalBlockEntity.Companion::onPlayerLoggedOut
        )

        // Disk-image GC:
        //   * ItemLifecycleListener fires on ItemEntity expire / kill and
        //     runs event-driven cleanup. Always on, no config knob.
        //   * GcScheduler checks the config every tick and dispatches the
        //     opt-in periodic sweep when enabled.
        NeoForge.EVENT_BUS.register(ItemLifecycleListener::class.java)
        NeoForge.EVENT_BUS.addListener(GcScheduler::onServerTick)

        // HandheldTickHost.onServerTick drives RISC-V machines that live on
        // handheld items in player inventories — same role serverTick plays
        // for placed computer cases. Pause/unpause + grace-period destroy
        // diff lives there.
        NeoForge.EVENT_BUS.addListener(lekkit.scev.server.HandheldTickHost::onServerTick)
        NeoForge.EVENT_BUS.addListener(lekkit.scev.server.HandheldTickHost::onServerStopping)
    }

    private fun onCommonSetup(event: FMLCommonSetupEvent) {
        event.enqueueWork(NativeLoader::ensureLoaded)
        // Install built-in firmwares (LINUX, OPENSBI_ONLY, OPEN_FIRMWARE) and
        // disk templates. Other mods can register their own entries from
        // their own FMLCommonSetupEvent — registration order is preserved by
        // the registries' LinkedHashMap backing. Safe to register outside
        // enqueueWork: registration is a plain concurrent-map put.
        FirmwareRegistry.registerBuiltins()
        DiskTemplateRegistry.registerBuiltins()

        // Register built-in disk-image GC scanners. Other mods may add their
        // own (AE2 compat, Create contraptions, RS disks, …) from their own
        // common-setup hook via ScannerRegistry.register().
        ScannerRegistry.register(RunningMachineScanner())
        ScannerRegistry.register(PlayerInventoryScanner())
        ScannerRegistry.register(BlockEntityScanner())
        ScannerRegistry.register(EntityScanner())
    }

    private fun onServerStarting(event: ServerStartingEvent) {
        // Rebind NVMe / snapshot storage into the active world's save folder
        // so disk contents travel with the world (backup, copy, delete).
        StorageManager.onServerStarting(event.server)

        // Stand up the per-world disk-image GC. Registry + images dir live
        // under the world save so GC state travels with the save just like
        // the images themselves do.
        val worldScev = event.server.getWorldPath(LevelResource.ROOT).resolve("scev")
        val imagesDir = worldScev.resolve("images")
        val registryFile = imagesDir.resolve(".registry.json")
        val registry = DiskImageRegistry.load(registryFile)
        val policy = GcPolicy(
            ScevConfig.GC_CREATION_GRACE_MINUTES.get() * 60_000L,
            ScevConfig.GC_SWEEP_RETENTION_DAYS.get() * 86_400_000L,
            ScevConfig.GC_SWEEP_INTERVAL_HOURS.get() * 3_600_000L)
        ScevGc.install(DiskImageGc(imagesDir, registry, policy))
        GcScheduler.reset()
    }

    private fun onServerStopping(event: ServerStoppingEvent) {
        // Persist the GC registry before the world is unloaded so the next
        // boot picks up the current lastSeen map + protected set.
        ScevGc.active()?.registry()?.save()
        ScevGc.uninstall()

        MachineManager.finishAllMachines()
        StorageManager.onServerStopping()
    }

    /**
     * Wire the `/scev gc ...` command tree. Called once per server
     * start via [RegisterCommandsEvent].
     */
    private fun onRegisterCommands(event: RegisterCommandsEvent) {
        ScevGcCommand.register(event.dispatcher)
    }

    companion object {
        const val MODID: String = "scev"
        const val NAME: String = "Scalar Evolution"

        @JvmStatic
        fun rl(path: String): ResourceLocation = ResourceLocation.fromNamespaceAndPath(MODID, path)
    }
}
