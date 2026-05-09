/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.main

import java.util.UUID
import lekkit.scev.items.FirmwareBlob
import lekkit.scev.items.FlashFirmware
import lekkit.scev.items.Printout
import net.minecraft.core.UUIDUtil
import net.minecraft.core.component.DataComponentType
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import com.mojang.serialization.Codec
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.world.item.component.ItemContainerContents
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister

/**
 * All ItemStack-level data components the mod registers.
 *
 * ## Flash-chip firmware components (added with the MCU tier)
 *
 * Three components decide what firmware a flash chip carries.
 * Resolved by [lekkit.scev.machine.MachineSpecParser] in precedence
 * order:
 *
 * 1. [FIRMWARE_BYTES] — player-authored (or mod-authored) raw payload.
 *    Wins over everything else; bypasses
 *    [lekkit.scev.machine.firmware.FirmwareRegistry] entirely.
 * 2. [FIRMWARE_ID_OVERRIDE] — arbitrary registry id, for third-party
 *    firmwares registered by other mods that aren't in the typed
 *    [FlashFirmware] enum.
 * 3. [FIRMWARE_KIND] — built-in kind enum. The typed happy path for
 *    our own firmwares (`LINUX`, `BLINKY`, ...).
 * 4. (no component) — parser falls back to `FlashFirmware.LINUX` so
 *    existing worlds keep booting as before.
 */
object ScevDataComponents {
    @JvmField
    val DATA_COMPONENTS: DeferredRegister.DataComponents =
        DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, ScalarEvolution.MODID)

    /**
     * Persistent disk image UUID, attached to flash / HDD / NVMe
     * items. Each UUID points at an image file under
     * `./scev/images/<uuid>.img`.
     */
    @JvmField
    val STORAGE_UUID: DeferredHolder<DataComponentType<*>, DataComponentType<UUID>> =
        DATA_COMPONENTS.registerComponentType("storage_uuid") { b ->
            b.persistent(UUIDUtil.CODEC)
             .networkSynchronized(UUIDUtil.STREAM_CODEC)
        }

    /**
     * Stable machine UUID for handheld computer items (phones, tablets,
     * Game-Boy-style consoles). Plays the same role as the
     * `MachineUUID` NBT tag on `ComputerCaseBlockEntity`: keys the
     * running `MachineState` in `MachineManager` across re-equips and
     * server restarts. Allocated lazily by the first tick that sees
     * the stack — see `lekkit.scev.server.HandheldTickHost`.
     *
     * **Item duplication.** Vanilla stack copy carries components
     * verbatim, so `/give`-style duplication or creative pick-block
     * yields two stacks pointing at the same VM. The tick host
     * dedupes per-tick (first stack wins). If duplication becomes a
     * real concern, hook stack copy to clear this component (and any
     * nested STORAGE_UUIDs) so the copy gets a fresh identity on
     * first tick.
     */
    @JvmField
    val MACHINE_UUID: DeferredHolder<DataComponentType<*>, DataComponentType<UUID>> =
        DATA_COMPONENTS.registerComponentType("machine_uuid") { b ->
            b.persistent(UUIDUtil.CODEC)
             .networkSynchronized(UUIDUtil.STREAM_CODEC)
        }

    /**
     * Components installed into a motherboard item: CPU, flash, RAM,
     * NVMe, PCI cards. Up to 14 slots, laid out per
     * [lekkit.scev.items.MotherboardItem] constants.
     */
    @JvmField
    val MOTHERBOARD_INVENTORY: DeferredHolder<DataComponentType<*>, DataComponentType<ItemContainerContents>> =
        DATA_COMPONENTS.registerComponentType("motherboard_inventory") { b ->
            b.persistent(ItemContainerContents.CODEC)
             .networkSynchronized(ItemContainerContents.STREAM_CODEC)
        }

    /**
     * Which built-in firmware this flash chip carries. Absent
     * component means "uninitialized" and the parser defaults to
     * [FlashFirmware.LINUX]. See class kdoc for precedence.
     */
    @JvmField
    val FIRMWARE_KIND: DeferredHolder<DataComponentType<*>, DataComponentType<FlashFirmware>> =
        DATA_COMPONENTS.registerComponentType("firmware_kind") { b ->
            b.persistent(FlashFirmware.CODEC)
             .networkSynchronized(FlashFirmware.STREAM_CODEC)
        }

    /**
     * Escape hatch for third-party firmwares registered in
     * [lekkit.scev.machine.firmware.FirmwareRegistry] by other mods.
     * Wins over [FIRMWARE_KIND] when both are present so integration
     * mods can override our built-ins without a typed enum entry.
     */
    @JvmField
    val FIRMWARE_ID_OVERRIDE: DeferredHolder<DataComponentType<*>, DataComponentType<ResourceLocation>> =
        DATA_COMPONENTS.registerComponentType("firmware_id_override") { b ->
            b.persistent(ResourceLocation.CODEC)
             .networkSynchronized(ResourceLocation.STREAM_CODEC)
        }

    /**
     * Raw firmware bytes loaded directly at the reset vector,
     * bypassing both [FlashFirmware] and `FirmwareRegistry`. This is
     * how player-authored custom programs (and the flash-programmer
     * block) attach content to a chip.
     *
     * Wins over both other components — the parser treats a non-empty
     * blob as the single source of truth for what the guest executes.
     */
    @JvmField
    val FIRMWARE_BYTES: DeferredHolder<DataComponentType<*>, DataComponentType<FirmwareBlob>> =
        DATA_COMPONENTS.registerComponentType("firmware_bytes") { b ->
            b.persistent(FirmwareBlob.CODEC)
             .networkSynchronized(FirmwareBlob.STREAM_CODEC)
        }

    /**
     * Overrides the [lekkit.scev.items.PreloadedNvmeItem]'s
     * constructor-provided default template id, letting one
     * registered item surface multiple disk templates via per-stack
     * variants (the creative tab emits one stack per registered
     * template).
     *
     * Parallels [FIRMWARE_KIND] for flash chips: one item, N variants
     * discriminated by this component.
     */
    @JvmField
    val DISK_TEMPLATE: DeferredHolder<DataComponentType<*>, DataComponentType<ResourceLocation>> =
        DATA_COMPONENTS.registerComponentType("disk_template") { b ->
            b.persistent(ResourceLocation.CODEC)
             .networkSynchronized(ResourceLocation.STREAM_CODEC)
        }

    /* ---------------- Teletype consumable counters ---------------- */

    /**
     * Lines remaining on a paper roll (loaded into a teletype). Starts
     * at [PAPER_ROLL_INITIAL_LINES] and decrements per printed line.
     * When 0 the roll is consumed and the teletype prints "OUT OF
     * PAPER" until a new one is loaded.
     */
    @JvmField
    val PAPER_LINES_REMAINING: DeferredHolder<DataComponentType<*>, DataComponentType<Int>> =
        DATA_COMPONENTS.registerComponentType("paper_lines_remaining") { b ->
            b.persistent(Codec.INT)
             .networkSynchronized(ByteBufCodecs.VAR_INT.cast())
        }

    /**
     * Characters of ink left in a ribbon. Decrements per printed
     * character. At 0 the teletype still prints (advance is purely
     * mechanical) but with no ink — visually faded text.
     */
    @JvmField
    val RIBBON_INK_REMAINING: DeferredHolder<DataComponentType<*>, DataComponentType<Int>> =
        DATA_COMPONENTS.registerComponentType("ribbon_ink_remaining") { b ->
            b.persistent(Codec.INT)
             .networkSynchronized(ByteBufCodecs.VAR_INT.cast())
        }

    /** Lines a fresh roll prints before exhaustion. */
    const val PAPER_ROLL_INITIAL_LINES: Int = 500
    /** Characters per fresh ribbon. */
    const val RIBBON_INITIAL_INK: Int = 8000

    /* ---------------- Printout payload ---------------- */

    /**
     * Static bitmap content carried by a
     * [lekkit.scev.items.PrintoutItem]. Burned in at print time and
     * never mutated afterwards — unlike framebuffer content (which
     * lives outside the item), the printout's pixels travel inside
     * the ItemStack via this component, persist through chest
     * storage, hopper transit, and item-frame display, and replicate
     * to every client that sees the stack.
     *
     * 4bpp palette-indexed; see [Printout] for the wire format.
     */
    @JvmField
    val PRINTOUT_CONTENT: DeferredHolder<DataComponentType<*>, DataComponentType<Printout>> =
        DATA_COMPONENTS.registerComponentType("printout_content") { b ->
            b.persistent(Printout.CODEC)
             .networkSynchronized(Printout.STREAM_CODEC.cast())
        }

    @JvmStatic
    fun register(modBus: IEventBus) {
        DATA_COMPONENTS.register(modBus)
    }
}

/** Coerce a STREAM_CODEC bound to ByteBuf into one bound to
 *  RegistryFriendlyByteBuf — every NeoForge component registration
 *  wants the registry-friendly variant but ByteBufCodecs only ships
 *  the plain one. Adapter is purely a type lift. */
private fun <V> StreamCodec<io.netty.buffer.ByteBuf, V>.cast():
        StreamCodec<RegistryFriendlyByteBuf, V> = StreamCodec.of(
    { buf, v -> this@cast.encode(buf, v) },
    { buf -> this@cast.decode(buf) },
)
