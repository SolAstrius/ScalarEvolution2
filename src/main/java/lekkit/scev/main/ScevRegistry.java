/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.main;

import java.util.function.Supplier;
import lekkit.scev.blocks.DirectionalBlock;
import lekkit.scev.blocks.KeyboardBlock;
import lekkit.scev.blocks.WorkstationBlock;
import lekkit.scev.blockentity.ComputerCaseBlockEntity;
import lekkit.scev.blockentity.KeyboardBlockEntity;
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
import lekkit.scev.items.SolderingIronItem;
import lekkit.scev.items.StorageItem;
import lekkit.scev.items.TinkerpadItem;
import lekkit.scev.machine.storage.DiskTemplateRegistry;
import lekkit.scev.menu.ComputerCaseMenu;
import lekkit.scev.menu.MachineMenu;
import lekkit.scev.menu.MotherboardMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
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
    public static final DeferredItem<Item> SOC = ITEMS.register("soc", () -> new Item(itemProps()));

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

    //
    // Menu types
    //

    public static final DeferredHolder<MenuType<?>, MenuType<ComputerCaseMenu>> COMPUTER_CASE_MENU =
            MENU_TYPES.register("computer_case", () -> IMenuTypeExtension.create(ComputerCaseMenu::fromNetwork));
    public static final DeferredHolder<MenuType<?>, MenuType<MotherboardMenu>> MOTHERBOARD_MENU =
            MENU_TYPES.register("motherboard", () -> IMenuTypeExtension.create(MotherboardMenu::fromNetwork));
    public static final DeferredHolder<MenuType<?>, MenuType<MachineMenu>> MACHINE_MENU =
            MENU_TYPES.register("machine", () -> IMenuTypeExtension.create(MachineMenu::fromNetwork));

    //
    // Creative tabs
    //

    public static final Supplier<CreativeModeTab> MAIN_TAB = CREATIVE_MODE_TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.scev.main"))
                    .icon(() -> new net.minecraft.world.item.ItemStack(VT100_ITEM.get()))
                    .displayItems((params, out) -> {
                        out.accept(WORKSTATION_ITEM.get());
                        out.accept(POWERMARK_ITEM.get());
                        out.accept(TINKERPAD_ITEM.get());
                        out.accept(VT100_ITEM.get());
                        out.accept(CRT_MONITOR_ITEM.get());
                        out.accept(KEYBOARD_ITEM.get());
                        out.accept(KEYBOARD_MOUSE_ITEM.get());

                        out.accept(SOLDERING_IRON.get());

                        out.accept(EPOXY.get());
                        out.accept(SILICA_COMPOUND.get());
                        out.accept(MOLD_COMPOUND.get());
                        out.accept(FIBERGLASS.get());
                        out.accept(SILICON_WAFER.get());
                        out.accept(PCB_BASE.get());
                        out.accept(DSUB_CONNECTOR.get());
                        out.accept(CRYSTAL_OSCILLATOR.get());
                        out.accept(ELECTRONIC_PARTS.get());
                        out.accept(VOLTAGE_REGULATOR.get());
                        out.accept(RTC_MODULE.get());
                        out.accept(MEMORY_CHIP.get());
                        out.accept(CHAR_DISPLAY.get());
                        out.accept(GFX_DISPLAY.get());
                        out.accept(SOC.get());

                        out.accept(CPU1.get());
                        out.accept(CPU2.get());
                        out.accept(CPU3.get());
                        out.accept(RAM_SODIMM1.get());
                        out.accept(RAM_SODIMM2.get());
                        out.accept(RAM_SODIMM3.get());
                        out.accept(RAM_SODIMM4.get());
out.accept(FLASH_CHIP.get());
                        out.accept(HDD.get());
                        out.accept(NVME.get());
                        out.accept(NVME_PRELOADED.get());
                        out.accept(VGA_CARD.get());
                        out.accept(GPIO_CARD.get());
                        out.accept(SOUND_CARD.get());
                        out.accept(RTL8169.get());
                        out.accept(MOTHERBOARD1.get());
                        out.accept(MOTHERBOARD2.get());
                        out.accept(MOTHERBOARD3.get());
                    })
                    .build());

    private ScevRegistry() {}

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITY_TYPES.register(modBus);
        MENU_TYPES.register(modBus);
        CREATIVE_MODE_TABS.register(modBus);
    }
}
