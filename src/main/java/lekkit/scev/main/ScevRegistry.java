/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.main;

import java.util.function.Supplier;
import lekkit.scev.blocks.DirectionalBlock;
import lekkit.scev.blocks.KeyboardBlock;
import lekkit.scev.blocks.McuBoardBlock;
import lekkit.scev.blocks.WorkstationBlock;
import lekkit.scev.client.sections.ScevCreativeTab;
import lekkit.scev.client.sections.ScevSectionRegistry;
import lekkit.scev.blockentity.ComputerCaseBlockEntity;
import lekkit.scev.blockentity.KeyboardBlockEntity;
import lekkit.scev.blockentity.McuBoardBlockEntity;
import lekkit.scev.blockentity.TinkerpadBlockEntity;
import lekkit.scev.blockentity.VT100BlockEntity;
import lekkit.scev.blockentity.WorkstationBlockEntity;
import lekkit.scev.blockentity.PowermarkBlockEntity;
import lekkit.scev.blockentity.CRTBlockEntity;
import lekkit.scev.items.CpuItem;
import lekkit.scev.items.FlashItem;
import lekkit.scev.items.GpioItem;
import lekkit.scev.items.MotherboardItem;
import lekkit.scev.items.NvmeItem;
import lekkit.scev.items.PciCardItem;
import lekkit.scev.items.PreloadedNvmeItem;
import lekkit.scev.items.RamItem;
import lekkit.scev.items.SocItem;
import lekkit.scev.items.SolderingIronItem;
import lekkit.scev.items.StorageItem;
import lekkit.scev.items.TinkerpadItem;
import lekkit.scev.machine.storage.DiskTemplateRegistry;
import lekkit.scev.menu.ComputerCaseMenu;
import lekkit.scev.menu.MachineMenu;
import lekkit.scev.menu.McuBoardMenu;
import lekkit.scev.menu.MotherboardMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Central registration of every block, item, block entity type, menu type, and creative tab.
 */
public final class ScevRegistry {

    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(ScalarEvolution.MODID);
    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(ScalarEvolution.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ScalarEvolution.MODID);
    public static final DeferredRegister<MenuType<?>> MENU_TYPES =
            DeferredRegister.create(Registries.MENU, ScalarEvolution.MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ScalarEvolution.MODID);

    //
    // Block properties
    //

    private static BlockBehaviour.Properties machineProps() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.METAL)
                .strength(2.5f)
                .noOcclusion();
    }

    //
    // Blocks
    //

    public static final DeferredBlock<WorkstationBlock> WORKSTATION =
            BLOCKS.register("workstation", () -> new WorkstationBlock(machineProps()));
    public static final DeferredBlock<WorkstationBlock> POWERMARK =
            BLOCKS.register("powermark", () -> new WorkstationBlock(machineProps()));
    public static final DeferredBlock<WorkstationBlock> TINKERPAD =
            BLOCKS.register("tinkerpad", () -> new WorkstationBlock(machineProps()));
    public static final DeferredBlock<DirectionalBlock> VT100 =
            BLOCKS.register("vt100", () -> new DirectionalBlock(machineProps()) {
                @Override
                public net.minecraft.world.level.block.entity.BlockEntity newBlockEntity(
                        net.minecraft.core.BlockPos pos,
                        net.minecraft.world.level.block.state.BlockState state) {
                    return new VT100BlockEntity(pos, state);
                }
            });
    public static final DeferredBlock<DirectionalBlock> CRT_MONITOR =
            BLOCKS.register("crt_monitor", () -> new DirectionalBlock(machineProps()) {
                @Override
                public net.minecraft.world.level.block.entity.BlockEntity newBlockEntity(
                        net.minecraft.core.BlockPos pos,
                        net.minecraft.world.level.block.state.BlockState state) {
                    return new CRTBlockEntity(pos, state);
                }
            });
    public static final DeferredBlock<KeyboardBlock> KEYBOARD =
            BLOCKS.register("keyboard", () -> new KeyboardBlock(machineProps(), false));
    public static final DeferredBlock<KeyboardBlock> KEYBOARD_MOUSE =
            BLOCKS.register("keyboard_mouse", () -> new KeyboardBlock(machineProps(), true));
    public static final DeferredBlock<McuBoardBlock> MCU_BOARD =
            BLOCKS.register("mcu_board", () -> new McuBoardBlock(machineProps()));

    //
    // Block items
    //

    private static final Item.Properties itemProps() { return new Item.Properties(); }
    private static final Item.Properties singleProps() { return new Item.Properties().stacksTo(1); }

    public static final DeferredItem<BlockItem> WORKSTATION_ITEM =
            ITEMS.register("workstation", () -> new BlockItem(WORKSTATION.get(), singleProps()));
    public static final DeferredItem<BlockItem> POWERMARK_ITEM =
            ITEMS.register("powermark", () -> new BlockItem(POWERMARK.get(), singleProps()));
    public static final DeferredItem<TinkerpadItem> TINKERPAD_ITEM =
            ITEMS.register("tinkerpad", () -> new TinkerpadItem(TINKERPAD.get(), singleProps()));
    public static final DeferredItem<BlockItem> VT100_ITEM =
            ITEMS.register("vt100", () -> new BlockItem(VT100.get(), itemProps()));
    public static final DeferredItem<BlockItem> CRT_MONITOR_ITEM =
            ITEMS.register("crt_monitor", () -> new BlockItem(CRT_MONITOR.get(), itemProps()));
    public static final DeferredItem<BlockItem> KEYBOARD_ITEM =
            ITEMS.register("keyboard", () -> new BlockItem(KEYBOARD.get(), itemProps()));
    public static final DeferredItem<BlockItem> KEYBOARD_MOUSE_ITEM =
            ITEMS.register("keyboard_mouse", () -> new BlockItem(KEYBOARD_MOUSE.get(), itemProps()));
    public static final DeferredItem<BlockItem> MCU_BOARD_ITEM =
            ITEMS.register("mcu_board", () -> new BlockItem(MCU_BOARD.get(), singleProps()));

    //
    // Raw-material items
    //

    public static final DeferredItem<Item> EPOXY = ITEMS.register("epoxy", () -> new Item(itemProps()));
    public static final DeferredItem<Item> SILICA_COMPOUND = ITEMS.register("silica_compound", () -> new Item(itemProps()));
    public static final DeferredItem<Item> MOLD_COMPOUND = ITEMS.register("mold_compound", () -> new Item(itemProps()));
    public static final DeferredItem<Item> FIBERGLASS = ITEMS.register("fiberglass", () -> new Item(itemProps()));
    public static final DeferredItem<Item> SILICON_WAFER = ITEMS.register("silicon_wafer", () -> new Item(itemProps()));
    public static final DeferredItem<Item> PCB_BASE = ITEMS.register("pcb_base", () -> new Item(itemProps()));
    public static final DeferredItem<Item> DSUB_CONNECTOR = ITEMS.register("dsub_connector", () -> new Item(itemProps()));
    public static final DeferredItem<Item> CRYSTAL_OSCILLATOR = ITEMS.register("crystal_oscillator", () -> new Item(itemProps()));
    public static final DeferredItem<Item> ELECTRONIC_PARTS = ITEMS.register("electronic_parts", () -> new Item(itemProps()));
    public static final DeferredItem<Item> VOLTAGE_REGULATOR = ITEMS.register("voltage_regulator", () -> new Item(itemProps()));
    public static final DeferredItem<Item> RTC_MODULE = ITEMS.register("rtc_module", () -> new Item(itemProps()));
    public static final DeferredItem<Item> MEMORY_CHIP = ITEMS.register("memory_chip", () -> new Item(itemProps()));
    public static final DeferredItem<Item> CHAR_DISPLAY = ITEMS.register("char_display", () -> new Item(itemProps()));
    public static final DeferredItem<Item> GFX_DISPLAY = ITEMS.register("gfx_display", () -> new Item(itemProps()));
    // SoC tier ladder. Three integrated-package variants spanning microcontroller
    // (rv32, kilobytes of on-die RAM, bare-metal) through small embedded Linux
    // (rv64, tens of MiB). See SocItem's javadoc for the spec table. These
    // items are not yet accepted into any slot — the matching "MCU board" block
    // will consume them in a follow-up PR; the spec surface is settled here so
    // that block can be built against a stable contract.
    public static final DeferredItem<SocItem> SOC1 = ITEMS.register("soc1",
            () -> new SocItem(itemProps(), 1, "rv32im",   1,     4));   // 4 KiB, bare-metal tier
    public static final DeferredItem<SocItem> SOC2 = ITEMS.register("soc2",
            () -> new SocItem(itemProps(), 2, "rv32imac", 1,   256));   // 256 KiB, MCU+RTOS tier
    public static final DeferredItem<SocItem> SOC3 = ITEMS.register("soc3",
            () -> new SocItem(itemProps(), 3, "rv64imac", 2, 32768));   // 32 MiB, embedded Linux tier

    //
    // Tools
    //

    public static final DeferredItem<SolderingIronItem> SOLDERING_IRON =
            ITEMS.register("soldering_iron", () -> new SolderingIronItem(singleProps().durability(25)));

    //
    // Components
    //

    public static final DeferredItem<CpuItem> CPU1 = ITEMS.register("cpu1", () -> new CpuItem(singleProps(), 1, 1));
    public static final DeferredItem<CpuItem> CPU2 = ITEMS.register("cpu2", () -> new CpuItem(singleProps(), 2, 2));
    public static final DeferredItem<CpuItem> CPU3 = ITEMS.register("cpu3", () -> new CpuItem(singleProps(), 3, 4));

    public static final DeferredItem<RamItem> RAM_SODIMM1 = ITEMS.register("ram_sodimm1", () -> new RamItem(singleProps(), 0));
    public static final DeferredItem<RamItem> RAM_SODIMM2 = ITEMS.register("ram_sodimm2", () -> new RamItem(singleProps(), 1));
    public static final DeferredItem<RamItem> RAM_SODIMM3 = ITEMS.register("ram_sodimm3", () -> new RamItem(singleProps(), 2));
    public static final DeferredItem<RamItem> RAM_SODIMM4 = ITEMS.register("ram_sodimm4", () -> new RamItem(singleProps(), 3));

    public static final DeferredItem<FlashItem> FLASH_CHIP = ITEMS.register("flash_chip", () -> new FlashItem(singleProps()));
    public static final DeferredItem<StorageItem> HDD = ITEMS.register("hdd", () -> new StorageItem(singleProps(), "hdd.ext2", 1024));
    public static final DeferredItem<NvmeItem> NVME = ITEMS.register("nvme", () -> new NvmeItem(singleProps()));
    /**
     * Preloaded NVMe SSD — ships with the {@link DiskTemplateRegistry#BUILDROOT}
     * ext2 Linux rootfs pre-installed. Occupies the same NVMe slot as
     * {@link #NVME} (extends {@code NvmeItem} so the motherboard slot
     * predicate accepts both).
     */
    public static final DeferredItem<PreloadedNvmeItem> NVME_PRELOADED = ITEMS.register("nvme_preloaded",
            () -> new PreloadedNvmeItem(singleProps(), DiskTemplateRegistry.BUILDROOT));

    public static final DeferredItem<PciCardItem> VGA_CARD =
            ITEMS.register("vga_card", () -> new PciCardItem(singleProps(), PciCardItem.Kind.VGA));
    public static final DeferredItem<PciCardItem> GPIO_CARD =
            ITEMS.register("gpio_card", () -> new GpioItem(singleProps()));
    public static final DeferredItem<PciCardItem> SOUND_CARD =
            ITEMS.register("sound_card", () -> new PciCardItem(singleProps(), PciCardItem.Kind.SOUND));
    public static final DeferredItem<PciCardItem> RTL8169 =
            ITEMS.register("rtl8169", () -> new PciCardItem(singleProps(), PciCardItem.Kind.NET));

    public static final DeferredItem<MotherboardItem> MOTHERBOARD1 =
            ITEMS.register("motherboard1", () -> new MotherboardItem(singleProps(), 1));
    public static final DeferredItem<MotherboardItem> MOTHERBOARD2 =
            ITEMS.register("motherboard2", () -> new MotherboardItem(singleProps(), 2));
    public static final DeferredItem<MotherboardItem> MOTHERBOARD3 =
            ITEMS.register("motherboard3", () -> new MotherboardItem(singleProps(), 3));

    //
    // Block Entities
    //

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<WorkstationBlockEntity>> WORKSTATION_BE =
            BLOCK_ENTITY_TYPES.register("workstation",
                    () -> BlockEntityType.Builder.of(WorkstationBlockEntity::new, WORKSTATION.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PowermarkBlockEntity>> POWERMARK_BE =
            BLOCK_ENTITY_TYPES.register("powermark",
                    () -> BlockEntityType.Builder.of(PowermarkBlockEntity::new, POWERMARK.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TinkerpadBlockEntity>> TINKERPAD_BE =
            BLOCK_ENTITY_TYPES.register("tinkerpad",
                    () -> BlockEntityType.Builder.of(TinkerpadBlockEntity::new, TINKERPAD.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<VT100BlockEntity>> VT100_BE =
            BLOCK_ENTITY_TYPES.register("vt100",
                    () -> BlockEntityType.Builder.of(VT100BlockEntity::new, VT100.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CRTBlockEntity>> CRT_BE =
            BLOCK_ENTITY_TYPES.register("crt_monitor",
                    () -> BlockEntityType.Builder.of(CRTBlockEntity::new, CRT_MONITOR.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<KeyboardBlockEntity>> KEYBOARD_BE =
            BLOCK_ENTITY_TYPES.register("keyboard",
                    () -> BlockEntityType.Builder.of(
                            (pos, state) -> new KeyboardBlockEntity(ScevRegistry.KEYBOARD_BE.get(), pos, state, false),
                            KEYBOARD.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<KeyboardBlockEntity>> KEYBOARD_MOUSE_BE =
            BLOCK_ENTITY_TYPES.register("keyboard_mouse",
                    () -> BlockEntityType.Builder.of(
                            (pos, state) -> new KeyboardBlockEntity(ScevRegistry.KEYBOARD_MOUSE_BE.get(), pos, state, true),
                            KEYBOARD_MOUSE.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<McuBoardBlockEntity>> MCU_BOARD_BE =
            BLOCK_ENTITY_TYPES.register("mcu_board",
                    () -> BlockEntityType.Builder.of(McuBoardBlockEntity::new, MCU_BOARD.get()).build(null));

    //
    // Menu types
    //

    public static final DeferredHolder<MenuType<?>, MenuType<ComputerCaseMenu>> COMPUTER_CASE_MENU =
            MENU_TYPES.register("computer_case", () -> IMenuTypeExtension.create(ComputerCaseMenu::fromNetwork));
    public static final DeferredHolder<MenuType<?>, MenuType<MotherboardMenu>> MOTHERBOARD_MENU =
            MENU_TYPES.register("motherboard", () -> IMenuTypeExtension.create(MotherboardMenu::fromNetwork));
    public static final DeferredHolder<MenuType<?>, MenuType<MachineMenu>> MACHINE_MENU =
            MENU_TYPES.register("machine", () -> IMenuTypeExtension.create(MachineMenu::fromNetwork));
    public static final DeferredHolder<MenuType<?>, MenuType<McuBoardMenu>> MCU_BOARD_MENU =
            MENU_TYPES.register("mcu_board", () -> IMenuTypeExtension.create(McuBoardMenu::fromNetwork));

    //
    // Creative tabs
    //

    // Section ids for the sectioned creative-tab layout. Matching JSON
    // definitions live at assets/scev/scev/sections/*.json — see ScevSection
    // for the schema. Keeping the ids here keeps the item→section wiring
    // below colocated with item declarations.
    public static final ResourceLocation SECTION_CASES        = ScalarEvolution.rl("cases_peripherals");
    public static final ResourceLocation SECTION_CRAFTING     = ScalarEvolution.rl("crafting");
    public static final ResourceLocation SECTION_COMPUTING    = ScalarEvolution.rl("computing");
    public static final ResourceLocation SECTION_STORAGE      = ScalarEvolution.rl("storage");
    public static final ResourceLocation SECTION_EXPANSION    = ScalarEvolution.rl("expansion");
    public static final ResourceLocation SECTION_MOTHERBOARDS = ScalarEvolution.rl("motherboards");

    /**
     * SCEv's main creative tab. The {@code displayItems} callback is a
     * no-op here — the {@code CreativeModeTabMixin} detects this tab via
     * {@link ScevCreativeTab#isScevMainTab} and routes the build through
     * {@link ScevCreativeTab#processItems} for the sectioned layout.
     *
     * <p>The supplier also captures the built tab into
     * {@link ScevCreativeTab#MAIN_TAB_INSTANCE} so the mixin can identify
     * "our" tab without a hard dependency on this registry class from the
     * client-side mixin package.
     */
    public static final Supplier<CreativeModeTab> MAIN_TAB = CREATIVE_MODE_TABS.register("main",
            () -> {
                CreativeModeTab tab = CreativeModeTab.builder()
                        .title(Component.translatable("itemGroup.scev.main"))
                        .icon(() -> new net.minecraft.world.item.ItemStack(VT100_ITEM.get()))
                        // Intentionally empty: mixin-driven sectioned layout
                        // replaces this callback's output at buildContents time.
                        .displayItems((params, out) -> {})
                        .build();
                ScevCreativeTab.MAIN_TAB_INSTANCE = tab;
                return tab;
            });

    // -----------------------------------------------------------------
    // Item → section assignment
    //
    // Static-init block: runs once after all DeferredItem fields above are
    // initialized (Java guarantees field initializers run in source order
    // before any static{} blocks at the same class level). Uses getId()
    // which is safe pre-resolution — the id is known at register time.
    // -----------------------------------------------------------------
    static {
        // Cases & peripherals — whole blocks you place in the world.
        ScevSectionRegistry.assign(WORKSTATION_ITEM,    SECTION_CASES);
        ScevSectionRegistry.assign(POWERMARK_ITEM,      SECTION_CASES);
        ScevSectionRegistry.assign(TINKERPAD_ITEM,      SECTION_CASES);
        ScevSectionRegistry.assign(VT100_ITEM,          SECTION_CASES);
        ScevSectionRegistry.assign(CRT_MONITOR_ITEM,    SECTION_CASES);
        ScevSectionRegistry.assign(KEYBOARD_ITEM,       SECTION_CASES);
        ScevSectionRegistry.assign(KEYBOARD_MOUSE_ITEM, SECTION_CASES);
        ScevSectionRegistry.assign(MCU_BOARD_ITEM,      SECTION_CASES);

        // Crafting — tools + raw materials + fabricated intermediate
        // components. Groups the whole "benchtop fabrication" flow.
        ScevSectionRegistry.assign(SOLDERING_IRON,     SECTION_CRAFTING);
        ScevSectionRegistry.assign(EPOXY,              SECTION_CRAFTING);
        ScevSectionRegistry.assign(SILICA_COMPOUND,    SECTION_CRAFTING);
        ScevSectionRegistry.assign(MOLD_COMPOUND,      SECTION_CRAFTING);
        ScevSectionRegistry.assign(FIBERGLASS,         SECTION_CRAFTING);
        ScevSectionRegistry.assign(SILICON_WAFER,      SECTION_CRAFTING);
        ScevSectionRegistry.assign(PCB_BASE,           SECTION_CRAFTING);
        ScevSectionRegistry.assign(DSUB_CONNECTOR,     SECTION_CRAFTING);
        ScevSectionRegistry.assign(CRYSTAL_OSCILLATOR, SECTION_CRAFTING);
        ScevSectionRegistry.assign(ELECTRONIC_PARTS,   SECTION_CRAFTING);
        ScevSectionRegistry.assign(VOLTAGE_REGULATOR,  SECTION_CRAFTING);
        ScevSectionRegistry.assign(RTC_MODULE,         SECTION_CRAFTING);
        ScevSectionRegistry.assign(MEMORY_CHIP,        SECTION_CRAFTING);
        ScevSectionRegistry.assign(CHAR_DISPLAY,       SECTION_CRAFTING);
        ScevSectionRegistry.assign(GFX_DISPLAY,        SECTION_CRAFTING);

        // Computing — CPU packages + memory + SoCs. What makes a machine tick.
        ScevSectionRegistry.assign(CPU1,        SECTION_COMPUTING);
        ScevSectionRegistry.assign(CPU2,        SECTION_COMPUTING);
        ScevSectionRegistry.assign(CPU3,        SECTION_COMPUTING);
        ScevSectionRegistry.assign(SOC1,        SECTION_COMPUTING);
        ScevSectionRegistry.assign(SOC2,        SECTION_COMPUTING);
        ScevSectionRegistry.assign(SOC3,        SECTION_COMPUTING);
        ScevSectionRegistry.assign(RAM_SODIMM1, SECTION_COMPUTING);
        ScevSectionRegistry.assign(RAM_SODIMM2, SECTION_COMPUTING);
        ScevSectionRegistry.assign(RAM_SODIMM3, SECTION_COMPUTING);
        ScevSectionRegistry.assign(RAM_SODIMM4, SECTION_COMPUTING);

        // Storage — flash, HDD, NVMe (+ preloaded).
        ScevSectionRegistry.assign(FLASH_CHIP,     SECTION_STORAGE);
        ScevSectionRegistry.assign(HDD,            SECTION_STORAGE);
        ScevSectionRegistry.assign(NVME,           SECTION_STORAGE);
        ScevSectionRegistry.assign(NVME_PRELOADED, SECTION_STORAGE);

        // Expansion cards — PCIe slot-fillers.
        ScevSectionRegistry.assign(VGA_CARD,  SECTION_EXPANSION);
        ScevSectionRegistry.assign(GPIO_CARD, SECTION_EXPANSION);
        ScevSectionRegistry.assign(SOUND_CARD, SECTION_EXPANSION);
        ScevSectionRegistry.assign(RTL8169,   SECTION_EXPANSION);

        // Motherboards — substrate that sockets everything above together.
        ScevSectionRegistry.assign(MOTHERBOARD1, SECTION_MOTHERBOARDS);
        ScevSectionRegistry.assign(MOTHERBOARD2, SECTION_MOTHERBOARDS);
        ScevSectionRegistry.assign(MOTHERBOARD3, SECTION_MOTHERBOARDS);
    }

    private ScevRegistry() {}

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITY_TYPES.register(modBus);
        MENU_TYPES.register(modBus);
        CREATIVE_MODE_TABS.register(modBus);
    }
}
