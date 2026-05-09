/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.main

import lekkit.scev.blockentity.*
import lekkit.scev.blocks.*
import lekkit.scev.client.sections.ScevCreativeTab
import lekkit.scev.client.sections.ScevSectionRegistry
import lekkit.scev.expansion.*
import lekkit.scev.items.*
import lekkit.scev.machine.storage.DiskTemplateRegistry
import lekkit.scev.menu.*
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.MapColor
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension
import net.neoforged.neoforge.network.IContainerFactory
import net.neoforged.neoforge.registries.DeferredBlock
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredItem
import net.neoforged.neoforge.registries.DeferredRegister
import java.util.function.Supplier

/**
 * Central registration of every block, item, block-entity type, menu type, and
 * creative tab. Field names and accessibility match the previous Java class —
 * Java callers continue to reference `ScevRegistry.EPOXY` etc. via `@JvmField`.
 */
object ScevRegistry {

    @JvmField val BLOCKS              = DeferredRegister.createBlocks(ScalarEvolution.MODID)
    @JvmField val ITEMS               = DeferredRegister.createItems(ScalarEvolution.MODID)
    @JvmField val BLOCK_ENTITY_TYPES  = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ScalarEvolution.MODID)
    @JvmField val MENU_TYPES          = DeferredRegister.create(Registries.MENU, ScalarEvolution.MODID)
    @JvmField val CREATIVE_MODE_TABS  = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ScalarEvolution.MODID)

    // ---- Property factories -------------------------------------------------
    private fun machineProps(): BlockBehaviour.Properties = BlockBehaviour.Properties.of()
        .mapColor(MapColor.METAL).strength(2.5f).noOcclusion()
    private fun itemProps(): Item.Properties = Item.Properties()
    private fun singleProps(): Item.Properties = Item.Properties().stacksTo(1)

    // ---- Helpers ------------------------------------------------------------

    /**
     * Register a block + matching `BlockItem` under the same name. The
     * `BlockItem` holder isn't returned — the only callers of the Java
     * `*_ITEM` fields were inside this file's own static-init block, and
     * those have moved into the [init] block below.
     */
    private fun <B : Block> blockWithItem(
        name: String, factory: () -> B, single: Boolean = false,
    ): DeferredBlock<B> {
        val block: DeferredBlock<B> = BLOCKS.register(name, Supplier { factory() })
        ITEMS.register(name, Supplier { BlockItem(block.get(), if (single) singleProps() else itemProps()) })
        return block
    }

    /**
     * Register a `BlockEntityType` for a single block. Keeps the verbose
     * `BlockEntityType.Builder.of(...).build(null)` boilerplate out of the
     * field declarations.
     */
    private fun <T : BlockEntity> blockEntityType(
        name: String,
        block: DeferredBlock<*>,
        factory: BlockEntityType.BlockEntitySupplier<T>,
    ): DeferredHolder<BlockEntityType<*>, BlockEntityType<T>> =
        BLOCK_ENTITY_TYPES.register(name, Supplier {
            BlockEntityType.Builder.of(factory, block.get()).build(null)
        })

    /** As above but valid across multiple blocks (a single BE class
     *  shared by every variant of a family — e.g. all terminal kinds
     *  share TerminalBlockEntity). Vararg so adding a future variant
     *  is a one-token edit. */
    private fun <T : BlockEntity> blockEntityType(
        name: String,
        factory: BlockEntityType.BlockEntitySupplier<T>,
        vararg blocks: DeferredBlock<*>,
    ): DeferredHolder<BlockEntityType<*>, BlockEntityType<T>> =
        BLOCK_ENTITY_TYPES.register(name, Supplier {
            BlockEntityType.Builder.of(factory, *blocks.map { it.get() }.toTypedArray()).build(null)
        })

    /** Register a menu type around a `MenuMenu::fromNetwork`-style factory. */
    private fun <M : AbstractContainerMenu> menuType(
        name: String,
        factory: IContainerFactory<M>,
    ): DeferredHolder<MenuType<*>, MenuType<M>> =
        MENU_TYPES.register(name, Supplier { IMenuTypeExtension.create(factory) })

    /** Register a plain `new Item(itemProps())` under [name]. */
    private fun rawItem(name: String): DeferredItem<Item> =
        ITEMS.register(name, Supplier { Item(itemProps()) })

    /** Tier-ladder helpers: name is `"$prefix$tier"`, all share the singleProps factory shape. */
    private fun ramTier(tier: Int): DeferredItem<RamItem> =
        ITEMS.register("ram_sodimm$tier", Supplier { RamItem(singleProps(), tier - 1) })
    private fun cpuTier(tier: Int, cores: Int): DeferredItem<CpuItem> =
        ITEMS.register("cpu$tier", Supplier { CpuItem(singleProps(), tier, cores) })
    private fun motherboardTier(tier: Int): DeferredItem<MotherboardItem> =
        ITEMS.register("motherboard$tier", Supplier { MotherboardItem(singleProps(), tier) })
    private fun socTier(tier: Int, isa: String, packages: Int, embeddedRamKiB: Int): DeferredItem<SocItem> =
        ITEMS.register("soc$tier", Supplier { SocItem(itemProps(), tier, isa, packages, embeddedRamKiB) })

    /** Plain PCI-card slot fillers — same constructor shape, varying [PciCardItem.Kind]. */
    private fun pciCard(name: String, kind: PciCardItem.Kind): DeferredItem<PciCardItem> =
        ITEMS.register(name, Supplier { PciCardItem(singleProps(), kind) })

    /**
     * Anonymous-class shorthand for "directional block whose only
     * customisation is which BE it spawns." Lets the field declaration
     * stay one line: `simpleDirectionalBlock("vt100", ::TerminalBlockEntity)`.
     */
    private fun simpleDirectionalBlock(
        name: String,
        ctor: (BlockPos, BlockState) -> BlockEntity,
    ): DeferredBlock<DirectionalBlock> = blockWithItem(name, {
        object : DirectionalBlock(machineProps()) {
            override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = ctor(pos, state)
        }
    })

    /**
     * Keyboard-style BE: the BE constructor needs the registered type
     * passed back into itself, so the supplier captures a `lateinit`
     * reference filled in by the same register call. Resolves at BE-
     * construction time, well after class init.
     */
    private fun keyboardBE(name: String, block: DeferredBlock<*>, hasMouse: Boolean):
        DeferredHolder<BlockEntityType<*>, BlockEntityType<KeyboardBlockEntity>> {
        lateinit var ref: DeferredHolder<BlockEntityType<*>, BlockEntityType<KeyboardBlockEntity>>
        ref = BLOCK_ENTITY_TYPES.register(name, Supplier {
            BlockEntityType.Builder.of(
                BlockEntityType.BlockEntitySupplier { pos, state ->
                    KeyboardBlockEntity(ref.get(), pos, state, hasMouse)
                },
                block.get()
            ).build(null)
        })
        return ref
    }

    // ---- Blocks (paired with matching BlockItem) ----------------------------
    @JvmField val WORKSTATION      = blockWithItem("workstation",      { WorkstationBlock(machineProps()) }, single = true)
    @JvmField val POWERMARK        = blockWithItem("powermark",        { WorkstationBlock(machineProps()) }, single = true)
    @JvmField val TINKERPAD: DeferredBlock<WorkstationBlock> = run {
        val block = BLOCKS.register("tinkerpad", Supplier { WorkstationBlock(machineProps()) })
        ITEMS.register("tinkerpad", Supplier { TinkerpadItem(block.get(), singleProps()) })
        block
    }
    @JvmField val VT100            = blockWithItem("vt100", { Vt100Block(machineProps()) })
    @JvmField val VT220            = blockWithItem("vt220", { Vt220Block(machineProps()) })
    @JvmField val VT340            = blockWithItem("vt340", { Vt340Block(machineProps()) })
    @JvmField val VT420            = blockWithItem("vt420", { Vt420Block(machineProps()) })
    @JvmField val VT520            = blockWithItem("vt520", { Vt520Block(machineProps()) })
    @JvmField val CRT_MONITOR      = simpleDirectionalBlock("crt_monitor", ::CRTBlockEntity)
    @JvmField val KEYBOARD         = blockWithItem("keyboard",         { KeyboardBlock(machineProps(), false) })
    @JvmField val KEYBOARD_MOUSE   = blockWithItem("keyboard_mouse",   { KeyboardBlock(machineProps(), true) })
    @JvmField val MCU_BOARD        = blockWithItem("mcu_board",        { McuBoardBlock(machineProps()) }, single = true)
    @JvmField val CABLE            = blockWithItem("cable",            { CableBlock(machineProps()) })
    @JvmField val FLASH_PROGRAMMER = blockWithItem("flash_programmer", { FlashProgrammerBlock(machineProps()) }, single = true)

    // ---- Processing machines (ink / ribbon production) ---------------------
    @JvmField val INK_MIXER          = blockWithItem("ink_mixer",          { InkMixerBlock(machineProps()) }, single = true)
    @JvmField val RIBBON_IMPREGNATOR = blockWithItem("ribbon_impregnator", { RibbonImpregnatorBlock(machineProps()) }, single = true)
    @JvmField val TELETYPE           = blockWithItem("teletype",           { TeletypeBlock(machineProps()) }, single = true)

    // ---- Teletype consumables + ink-chain items -----------------------------
    @JvmField val PAPER_ROLL      = ITEMS.register("paper_roll",      Supplier { PaperRollItem(singleProps()) })
    @JvmField val PIGMENT         = ITEMS.register("pigment",         Supplier { PigmentItem(itemProps()) })
    @JvmField val BINDER          = ITEMS.register("binder",          Supplier { BinderItem(itemProps()) })
    @JvmField val INK_JAR         = ITEMS.register("ink_jar",         Supplier { InkJarItem(singleProps()) })
    @JvmField val RIBBON          = ITEMS.register("ribbon",          Supplier { RibbonItem(singleProps()) })
    @JvmField val PRINTOUT        = ITEMS.register("printout",        Supplier { PrintoutItem(singleProps()) })

    // ---- Expansion cards ----------------------------------------------------
    @JvmField val SERIAL_PORT_CARD = ITEMS.register("serial_port_card", Supplier { SerialPortCardItem(singleProps()) })
    @JvmField val I2C_CARD         = ITEMS.register("i2c_card",         Supplier { I2CCardItem(singleProps()) })
    @JvmField val RTC_CARD         = ITEMS.register("rtc_card",         Supplier { RtcCardItem(singleProps()) })
    @JvmField val GPIO_EXPANSION_CARD = ITEMS.register("gpio_expansion_card", Supplier { GpioCardItem(singleProps()) })

    // ---- Raw-material items -------------------------------------------------
    @JvmField val EPOXY              = rawItem("epoxy")
    @JvmField val SILICA_COMPOUND    = rawItem("silica_compound")
    @JvmField val MOLD_COMPOUND      = rawItem("mold_compound")
    @JvmField val FIBERGLASS         = rawItem("fiberglass")
    @JvmField val SILICON_WAFER      = rawItem("silicon_wafer")
    @JvmField val PCB_BASE           = rawItem("pcb_base")
    @JvmField val DSUB_CONNECTOR     = rawItem("dsub_connector")
    @JvmField val CRYSTAL_OSCILLATOR = rawItem("crystal_oscillator")
    @JvmField val ELECTRONIC_PARTS   = rawItem("electronic_parts")
    @JvmField val VOLTAGE_REGULATOR  = rawItem("voltage_regulator")
    @JvmField val RTC_MODULE         = rawItem("rtc_module")
    @JvmField val MEMORY_CHIP        = rawItem("memory_chip")
    @JvmField val CHAR_DISPLAY       = rawItem("char_display")
    @JvmField val GFX_DISPLAY        = rawItem("gfx_display")

    // ---- SoC tier ladder (see SocItem javadoc for the spec table) ----------
    @JvmField val SOC1 = socTier(1, "rv32im",   1,     4)   // 4 KiB, bare-metal
    @JvmField val SOC2 = socTier(2, "rv32imac", 1,   256)   // 256 KiB, MCU+RTOS
    @JvmField val SOC3 = socTier(3, "rv64imac", 2, 32768)   // 32 MiB, embedded Linux

    // ---- Tools --------------------------------------------------------------
    @JvmField val SOLDERING_IRON = ITEMS.register("soldering_iron", Supplier { SolderingIronItem(singleProps().durability(25)) })

    // ---- Compute components -------------------------------------------------
    @JvmField val CPU1 = cpuTier(1, cores = 1)
    @JvmField val CPU2 = cpuTier(2, cores = 2)
    @JvmField val CPU3 = cpuTier(3, cores = 4)

    @JvmField val RAM_SODIMM1 = ramTier(1)
    @JvmField val RAM_SODIMM2 = ramTier(2)
    @JvmField val RAM_SODIMM3 = ramTier(3)
    @JvmField val RAM_SODIMM4 = ramTier(4)
    @JvmField val RAM_SODIMM5 = ramTier(5)

    // ---- Storage ------------------------------------------------------------
    @JvmField val FLASH_CHIP = ITEMS.register("flash_chip", Supplier { FlashItem(singleProps()) })
    @JvmField val HDD  = ITEMS.register("hdd",  Supplier { StorageItem(singleProps(), "hdd.ext2", 1024) })
    @JvmField val NVME = ITEMS.register("nvme", Supplier { NvmeItem(singleProps()) })
    /**
     * Preloaded NVMe SSD shipped with the [DiskTemplateRegistry.ALPINE] rootfs.
     * Same slot as [NVME] — extends `NvmeItem` so the motherboard slot
     * predicate accepts both.
     */
    @JvmField val NVME_PRELOADED = ITEMS.register("nvme_preloaded", Supplier {
        PreloadedNvmeItem(singleProps(), DiskTemplateRegistry.ALPINE)
    })

    // ---- PCI cards ----------------------------------------------------------
    @JvmField val VGA_CARD   = pciCard("vga_card",   PciCardItem.Kind.VGA)
    @JvmField val SOUND_CARD = pciCard("sound_card", PciCardItem.Kind.SOUND)
    @JvmField val RTL8169    = pciCard("rtl8169",    PciCardItem.Kind.NET)
    @JvmField val GPIO_CARD: DeferredItem<PciCardItem> =  // GpioItem extends PciCardItem; doesn't fit the [pciCard] mold
        ITEMS.register("gpio_card", Supplier { GpioItem(singleProps()) })

    // ---- Motherboards -------------------------------------------------------
    @JvmField val MOTHERBOARD1 = motherboardTier(1)
    @JvmField val MOTHERBOARD2 = motherboardTier(2)
    @JvmField val MOTHERBOARD3 = motherboardTier(3)

    // ---- Handheld computers (item-form RISC-V machines) ---------------------
    // POCKET_COMPUTER is the first concrete `IHandheldComputer` — the same
    // motherboard layout as a tier-2 board, served as an item the player
    // carries. VM lifecycle driven by `HandheldTickHost`. Future variants
    // (phone, tablet, watch, e-reader) just register more `HandheldItem`
    // subclasses with different chassis profiles.
    @JvmField val POCKET_COMPUTER = ITEMS.register("pocket_computer", Supplier {
        TinkerpadHandheldItem(singleProps(), motherboardLevel = 2)
    })

    // ---- Block entities -----------------------------------------------------
    @JvmField val WORKSTATION_BE      = blockEntityType("workstation",     WORKSTATION,     ::WorkstationBlockEntity)
    @JvmField val POWERMARK_BE        = blockEntityType("powermark",       POWERMARK,       ::PowermarkBlockEntity)
    @JvmField val TINKERPAD_BE        = blockEntityType("tinkerpad",       TINKERPAD,       ::TinkerpadBlockEntity)
    @JvmField val TERMINAL_BE            = blockEntityType("terminal",
        ::TerminalBlockEntity, VT100, VT220, VT340, VT420, VT520)
    @JvmField val CRT_BE              = blockEntityType("crt_monitor",     CRT_MONITOR,     ::CRTBlockEntity)
    @JvmField val KEYBOARD_BE       = keyboardBE("keyboard",       KEYBOARD,       hasMouse = false)
    @JvmField val KEYBOARD_MOUSE_BE = keyboardBE("keyboard_mouse", KEYBOARD_MOUSE, hasMouse = true)
    @JvmField val MCU_BOARD_BE        = blockEntityType("mcu_board",        MCU_BOARD,        ::McuBoardBlockEntity)
    @JvmField val CABLE_BE            = blockEntityType("cable",            CABLE,            ::CableBlockEntity)
    @JvmField val FLASH_PROGRAMMER_BE = blockEntityType("flash_programmer", FLASH_PROGRAMMER, ::FlashProgrammerBlockEntity)
    @JvmField val INK_MIXER_BE          = blockEntityType("ink_mixer",          INK_MIXER,          ::InkMixerBlockEntity)
    @JvmField val RIBBON_IMPREGNATOR_BE = blockEntityType("ribbon_impregnator", RIBBON_IMPREGNATOR, ::RibbonImpregnatorBlockEntity)
    @JvmField val TELETYPE_BE           = blockEntityType("teletype",           TELETYPE,           ::TeletypeBlockEntity)

    // ---- Menu types ---------------------------------------------------------
    @JvmField val COMPUTER_CASE_MENU    = menuType("computer_case",    ComputerCaseMenu::fromNetwork)
    @JvmField val MOTHERBOARD_MENU      = menuType("motherboard",      MotherboardMenu::fromNetwork)
    @JvmField val MACHINE_MENU          = menuType("machine",          MachineMenu::fromNetwork)
    @JvmField val MCU_BOARD_MENU        = menuType("mcu_board",        McuBoardMenu::fromNetwork)
    @JvmField val FLASH_PROGRAMMER_MENU = menuType("flash_programmer", FlashProgrammerMenu::fromNetwork)
    @JvmField val INK_MIXER_MENU          = menuType("ink_mixer",          InkMixerMenu::fromNetwork)
    @JvmField val RIBBON_IMPREGNATOR_MENU = menuType("ribbon_impregnator", RibbonImpregnatorMenu::fromNetwork)
    @JvmField val TELETYPE_MENU           = menuType("teletype",           TeletypeMenu::fromNetwork)
    @JvmField val TERMINAL_MENU            = menuType("vt100",            TerminalMenu::fromNetwork)

    // ---- Creative-tab sections (matched to assets/scev/scev/sections/*.json)
    @JvmField val SECTION_CASES        = ScalarEvolution.rl("cases_peripherals")
    @JvmField val SECTION_CRAFTING     = ScalarEvolution.rl("crafting")
    @JvmField val SECTION_COMPUTING    = ScalarEvolution.rl("computing")
    @JvmField val SECTION_STORAGE      = ScalarEvolution.rl("storage")
    @JvmField val SECTION_EXPANSION    = ScalarEvolution.rl("expansion")
    @JvmField val SECTION_MOTHERBOARDS = ScalarEvolution.rl("motherboards")

    /**
     * SCEv's main creative tab. The `displayItems` callback is intentionally
     * empty — the `CreativeModeTabMixin` detects this tab via
     * [ScevCreativeTab.isScevMainTab] and routes building through
     * [ScevCreativeTab.processItems] for the sectioned layout. The supplier
     * also captures the built tab into [ScevCreativeTab.MAIN_TAB_INSTANCE]
     * so the mixin can identify "our" tab without depending on this class.
     */
    @JvmField val MAIN_TAB: Supplier<CreativeModeTab> =
        CREATIVE_MODE_TABS.register("main", Supplier {
            CreativeModeTab.builder()
                .title(Component.translatable("itemGroup.scev.main"))
                .icon { ItemStack(VT100.get().asItem()) }
                .displayItems { _, _ -> }
                .build()
                .also { ScevCreativeTab.MAIN_TAB_INSTANCE = it }
        })

    /**
     * Separate tab for the ink / ribbon production line, the teletype,
     * and the expansion-card hardware.
     *
     * Sections in display order:
     *   1. Machines  — InkMixer, RibbonImpregnator, Teletype
     *   2. Items     — PaperRoll, Pigment, Binder, InkJar, Ribbon
     *   3. Cards     — SerialPort, I2C, RTC, GPIO
     */
    @JvmField val FABRICATION_TAB: Supplier<CreativeModeTab> =
        CREATIVE_MODE_TABS.register("fabrication", Supplier {
            CreativeModeTab.builder()
                .title(Component.translatable("itemGroup.scev.fabrication"))
                .icon { ItemStack(INK_MIXER.get().asItem()) }
                .displayItems { _, output ->
                    // Section 1: Machines.
                    output.accept(INK_MIXER.get())
                    output.accept(RIBBON_IMPREGNATOR.get())
                    output.accept(TELETYPE.get())

                    // Section 2: Chain items.
                    output.accept(PAPER_ROLL.get())
                    output.accept(PIGMENT.get())
                    output.accept(BINDER.get())
                    output.accept(INK_JAR.get())
                    output.accept(RIBBON.get())
                    // Sample printout populated with a debug bitmap so
                    // creative players can drop one into a frame and
                    // verify the renderer end-to-end without needing
                    // the printer block. Real (printed) printouts come
                    // out of the printer's out-tray with content set
                    // by the ESC/P driver.
                    output.accept(samplePrintoutStack())

                    // Section 3: Expansion cards.
                    output.accept(SERIAL_PORT_CARD.get())
                    output.accept(I2C_CARD.get())
                    output.accept(RTC_CARD.get())
                    output.accept(GPIO_EXPANSION_CARD.get())
                }
                .build()
        })

    init {
        // Item -> section assignment. The init block runs after every property
        // initializer above, so every DeferredItem/DeferredBlock has its id
        // resolved. Walk the DeferredHolders directly — single source of truth
        // for "what id is this thing registered under".
        fun assign(section: ResourceLocation, vararg holders: DeferredHolder<*, *>) {
            holders.forEach { ScevSectionRegistry.assign(it.id, section) }
        }

        // Cases & peripherals — block items, registered alongside each block
        // by [blockWithItem] under the same name. Ink / ribbon production
        // machines live in the separate FABRICATION_TAB below, not here.
        assign(SECTION_CASES,
            WORKSTATION, POWERMARK, TINKERPAD, POCKET_COMPUTER,
            VT100, VT220, VT340, VT420, VT520,
            CRT_MONITOR,
            KEYBOARD, KEYBOARD_MOUSE, MCU_BOARD, CABLE, FLASH_PROGRAMMER)

        assign(SECTION_CRAFTING,
            SOLDERING_IRON, EPOXY, SILICA_COMPOUND, MOLD_COMPOUND, FIBERGLASS,
            SILICON_WAFER, PCB_BASE, DSUB_CONNECTOR, CRYSTAL_OSCILLATOR,
            ELECTRONIC_PARTS, VOLTAGE_REGULATOR, RTC_MODULE, MEMORY_CHIP,
            CHAR_DISPLAY, GFX_DISPLAY)

        assign(SECTION_COMPUTING,
            CPU1, CPU2, CPU3, SOC1, SOC2, SOC3,
            RAM_SODIMM1, RAM_SODIMM2, RAM_SODIMM3, RAM_SODIMM4, RAM_SODIMM5)

        assign(SECTION_STORAGE, FLASH_CHIP, HDD, NVME, NVME_PRELOADED)
        assign(SECTION_EXPANSION, VGA_CARD, GPIO_CARD, SOUND_CARD, RTL8169)
        assign(SECTION_MOTHERBOARDS, MOTHERBOARD1, MOTHERBOARD2, MOTHERBOARD3)
    }

    @JvmStatic
    fun register(modBus: IEventBus) {
        BLOCKS.register(modBus)
        ITEMS.register(modBus)
        BLOCK_ENTITY_TYPES.register(modBus);
        MENU_TYPES.register(modBus);
        CREATIVE_MODE_TABS.register(modBus);
        // Recipe types + serializers for ProcessingMachineBlockEntity.
        lekkit.scev.recipe.MachineRecipes.register(modBus);
    }

    /**
     * Build a creative-mode demo printout: 192×256, single page,
     * with a thin border, a centred filled circle (palette index
     * 1 / ink), and a small color-bar at the bottom showing
     * palette indices 1..4. Enough to verify pixel layout, color
     * palette, and per-context rendering without needing a live
     * printer block.
     *
     * Drawn programmatically so we don't ship a binary asset;
     * cheap and runs once per creative-tab build.
     */
    private fun samplePrintoutStack(): ItemStack {
        val w = 192
        val h = 256
        val stride = (w + 1) ushr 1
        val pixels = ByteArray(stride * h)

        fun put(x: Int, y: Int, idx: Int) {
            if (x !in 0 until w || y !in 0 until h) return
            val off = y * stride + (x ushr 1)
            val byte = pixels[off].toInt() and 0xFF
            val masked = if ((x and 1) == 0) (byte and 0xF0) or (idx and 0x0F)
                         else (byte and 0x0F) or ((idx and 0x0F) shl 4)
            pixels[off] = masked.toByte()
        }

        // Page border (1 = ink).
        for (x in 0 until w) { put(x, 0, 1); put(x, h - 1, 1) }
        for (y in 0 until h) { put(0, y, 1); put(w - 1, y, 1) }

        // Centred filled disc. Drawn just inside the border;
        // radius leaves room for the palette bar at the bottom.
        val cx = w / 2
        val cy = (h - 32) / 2
        val r = minOf(cx, cy) - 12
        val r2 = r * r
        for (y in (cy - r) until (cy + r)) {
            val dy = y - cy
            val dy2 = dy * dy
            for (x in (cx - r) until (cx + r)) {
                val dx = x - cx
                if (dx * dx + dy2 <= r2) put(x, y, 1)
            }
        }

        // Bottom palette bar (4 swatches, indices 1..4) so we can
        // visually confirm the palette pipeline is wired even
        // before the printer driver uses any of these.
        val barY0 = h - 28
        val barY1 = h - 8
        val swatchW = (w - 16) / 4
        for (i in 0 until 4) {
            val x0 = 8 + i * swatchW
            val x1 = if (i == 3) w - 8 else x0 + swatchW - 2
            for (y in barY0 until barY1) for (x in x0 until x1) put(x, y, i + 1)
        }

        val printout = Printout(w, h, 1, "Sample Printout",
            Printout.MONO_PALETTE.copyOf(), pixels)
        val stack = ItemStack(PRINTOUT.get())
        stack.set(ScevDataComponents.PRINTOUT_CONTENT.get(), printout)
        return stack
    }
}
