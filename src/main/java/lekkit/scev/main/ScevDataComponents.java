/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.main;

import java.util.UUID;
import lekkit.scev.items.FirmwareBlob;
import lekkit.scev.items.FlashFirmware;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.component.ItemContainerContents;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * All ItemStack-level data components the mod registers.
 *
 * <h2>Flash-chip firmware components (added with the MCU tier)</h2>
 *
 * <p>Three components decide what firmware a flash chip carries. Resolved
 * by {@link lekkit.scev.machine.MachineSpecParser} in precedence order:
 *
 * <ol>
 *   <li>{@link #FIRMWARE_BYTES} — player-authored (or mod-authored) raw
 *       payload. Wins over everything else; bypasses
 *       {@link lekkit.scev.machine.firmware.FirmwareRegistry} entirely.</li>
 *   <li>{@link #FIRMWARE_ID_OVERRIDE} — arbitrary registry id, for
 *       third-party firmwares registered by other mods that aren't in the
 *       typed {@link FlashFirmware} enum.</li>
 *   <li>{@link #FIRMWARE_KIND} — built-in kind enum. The typed happy path
 *       for our own firmwares ({@code LINUX}, {@code BLINKY}, ...).</li>
 *   <li>(no component) — parser falls back to {@code FlashFirmware.LINUX}
 *       so existing worlds keep booting as before.</li>
 * </ol>
 */
public final class ScevDataComponents {
    public static final DeferredRegister.DataComponents DATA_COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, ScalarEvolution.MODID);

    /**
     * Persistent disk image UUID, attached to flash / HDD / NVMe items.
     * Each UUID points at an image file under {@code ./scev/images/<uuid>.img}.
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<UUID>> STORAGE_UUID =
            DATA_COMPONENTS.registerComponentType("storage_uuid", b -> b
                    .persistent(UUIDUtil.CODEC)
                    .networkSynchronized(UUIDUtil.STREAM_CODEC));

    /**
     * Components installed into a motherboard item: CPU, flash, RAM, NVMe, PCI cards.
     * Up to 14 slots, laid out per {@link lekkit.scev.items.MotherboardItem} constants.
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ItemContainerContents>> MOTHERBOARD_INVENTORY =
            DATA_COMPONENTS.registerComponentType("motherboard_inventory", b -> b
                    .persistent(ItemContainerContents.CODEC)
                    .networkSynchronized(ItemContainerContents.STREAM_CODEC));

    /**
     * Which built-in firmware this flash chip carries. Absent component
     * means "uninitialized" and the parser defaults to
     * {@link FlashFirmware#LINUX}. See class javadoc for precedence.
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<FlashFirmware>> FIRMWARE_KIND =
            DATA_COMPONENTS.registerComponentType("firmware_kind", b -> b
                    .persistent(FlashFirmware.CODEC)
                    .networkSynchronized(FlashFirmware.STREAM_CODEC));

    /**
     * Escape hatch for third-party firmwares registered in
     * {@link lekkit.scev.machine.firmware.FirmwareRegistry} by other mods.
     * Wins over {@link #FIRMWARE_KIND} when both are present so integration
     * mods can override our built-ins without a typed enum entry.
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ResourceLocation>> FIRMWARE_ID_OVERRIDE =
            DATA_COMPONENTS.registerComponentType("firmware_id_override", b -> b
                    .persistent(ResourceLocation.CODEC)
                    .networkSynchronized(ResourceLocation.STREAM_CODEC));

    /**
     * Raw firmware bytes loaded directly at the reset vector, bypassing
     * both {@link FlashFirmware} and {@link FirmwareRegistry}. This is how
     * player-authored custom programs (and the future flash-programmer
     * block) attach content to a chip.
     *
     * <p>Wins over both other components — the parser treats a non-empty
     * blob as the single source of truth for what the guest executes.
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<FirmwareBlob>> FIRMWARE_BYTES =
            DATA_COMPONENTS.registerComponentType("firmware_bytes", b -> b
                    .persistent(FirmwareBlob.CODEC)
                    .networkSynchronized(FirmwareBlob.STREAM_CODEC));

    /**
     * Overrides the {@link lekkit.scev.items.PreloadedNvmeItem}'s
     * constructor-provided default template id, letting one registered
     * item surface multiple disk templates via per-stack variants (the
     * creative tab emits one stack per registered template).
     *
     * <p>Parallels {@link #FIRMWARE_KIND} for flash chips: one item,
     * N variants discriminated by this component.
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ResourceLocation>> DISK_TEMPLATE =
            DATA_COMPONENTS.registerComponentType("disk_template", b -> b
                    .persistent(ResourceLocation.CODEC)
                    .networkSynchronized(ResourceLocation.STREAM_CODEC));

    public static void register(IEventBus modBus) {
        DATA_COMPONENTS.register(modBus);
    }

    private ScevDataComponents() {}
}
