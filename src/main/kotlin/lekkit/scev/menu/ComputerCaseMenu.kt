/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.menu

import java.util.Locale
import lekkit.scev.blockentity.ComputerCaseBlockEntity
import lekkit.scev.items.CpuItem
import lekkit.scev.items.FlashItem
import lekkit.scev.items.MotherboardInventory
import lekkit.scev.items.MotherboardItem
import lekkit.scev.items.RamItem
import lekkit.scev.main.ScevRegistry
import lekkit.scev.server.NativeLoader
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.DataSlot
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

/**
 * Menu for a computer case block (workstation / powermark / tinkerpad
 * placed in world).
 *
 * Layout: 1 case motherboard slot + 14 component slots (CPU / flash /
 * RAM / NVMe / PCI) at the exact coordinates from the 1.7.10
 * `ContainerComputerCase`, followed by the player's inventory (27
 * slots) and hotbar (9 slots) — 51 slots total.
 *
 * The 14 component slots are backed by the motherboard item's
 * `MOTHERBOARD_INVENTORY` data component via [MotherboardInventory].
 * Contents persist when the menu closes, the motherboard is removed,
 * or the case is broken — the components follow the motherboard
 * around.
 *
 * A [DataSlot] carries the machine's power state (0 = off, 1 = on)
 * from the server to the client each tick. The screen's power button
 * reads it via [isMachinePowered] so the visual reflects actual state,
 * not just the last click.
 */
class ComputerCaseMenu(
    id: Int,
    inv: Inventory,
    val caseBE: ComputerCaseBlockEntity,
) : AbstractContainerMenu(ScevRegistry.COMPUTER_CASE_MENU.get(), id) {

    private val motherboardSlots: MotherboardInventory
    /** Mirrors caseBE.isPowered()() on the server; read on the client via [isMachinePowered]. */
    private val powerState: DataSlot = DataSlot.standalone()

    init {
        // Component slots 1..14 read/write through the motherboard stack that
        // lives in the case's own slot 0. If the motherboard is removed, the
        // view becomes empty and mutations are dropped (the motherboard would
        // need to be re-seated to store components).
        motherboardSlots = MotherboardInventory(
            { caseBE.getItem(0) },
            Runnable { caseBE.setChanged() },
        )

        addMenuSlots()
        addPlayerInventory(inv)
        addDataSlot(powerState)
    }

    private fun addMenuSlots() {
        // SlotDef.COMPUTER_CASE lists all 15 slots:
        //   index 0 : motherboard slot on the case BE (container slot 0)
        //   index 1..14 : motherboard component slots (own container slots 0..13)
        for (def in SlotDef.COMPUTER_CASE) {
            if (def.index == 0) {
                addSlot(Slot(caseBE, 0, def.x, def.y))
            } else {
                addSlot(MotherboardComponentSlot(
                    motherboardSlots, def.index - 1, def.x, def.y, caseBE))
            }
        }
    }

    private fun addPlayerInventory(inv: Inventory) {
        for (row in 0 until 3) {
            for (col in 0 until 9) {
                addSlot(Slot(inv, col + row * 9 + 9,
                    8 + col * 18, SlotDef.FAT_PLAYER_INV_Y + row * 18))
            }
        }
        for (col in 0 until 9) {
            addSlot(Slot(inv, col, 8 + col * 18, SlotDef.FAT_HOTBAR_Y))
        }
    }


    /**
     * @return `true` iff the server last reported the machine as
     *         powered. Safe to call on both sides; on the server always
     *         reflects live state, on the client it's a one-tick-lagged
     *         mirror from the last [broadcastChanges].
     */
    fun isMachinePowered(): Boolean = powerState.get() != 0

    /**
     * Possible outcomes of [validateForPower]. Each non-OK value
     * corresponds to a specific missing-component lang key
     * (`text.scev.power.fail.<enum.name.lower()>`) for localized UI
     * feedback.
     */
    enum class ValidationResult {
        OK,
        NATIVE_NOT_LOADED,
        NO_MOTHERBOARD,
        NO_CPU,
        NO_FLASH,
        NO_RAM;

        fun langKey(): String = "text.scev.power.fail." + name.lowercase(Locale.ROOT)
    }

    /**
     * Client-side preflight: confirms the minimum components are
     * installed before firing a power-on packet. The server does its
     * own validation (tier-gating, slot-kind checks) independently;
     * this is purely a UX gate to avoid sending packets that would
     * silently no-op.
     *
     * Checked in order; the first failing condition wins so the player
     * can fix one thing at a time:
     * 1. **native-loaded** — [NativeLoader.isLoaded] must have
     *    reported success. Without librvvm, the backend initialization
     *    returns null and every power-on click silently no-ops;
     *    surfacing this as a visible error beats logs-only diagnostic.
     * 2. motherboard → CPU → flash → at least one RAM.
     *
     * In multiplayer, the client's [NativeLoader.isLoaded] reflects
     * its own JVM's state (FMLCommonSetupEvent runs on both sides),
     * which is normally the same as the server's when both installed
     * the mod correctly. If a mis-deployed server somehow has the
     * native loaded but the client doesn't, the client gets a spurious
     * "native not loaded" error — that's a config problem worth
     * surfacing regardless.
     */
    fun validateForPower(): ValidationResult {
        if (!NativeLoader.isLoaded()) return ValidationResult.NATIVE_NOT_LOADED

        val mbStack = caseBE.getItem(0)
        if (mbStack.item !is MotherboardItem) return ValidationResult.NO_MOTHERBOARD
        val cpu = motherboardSlots.getItem(MotherboardItem.SLOT_CPU)
        if (cpu.item !is CpuItem) return ValidationResult.NO_CPU

        val flash = motherboardSlots.getItem(MotherboardItem.SLOT_FLASH)
        if (flash.item !is FlashItem) return ValidationResult.NO_FLASH

        var anyRam = false
        for (i in MotherboardItem.SLOT_RAM_START..MotherboardItem.SLOT_RAM_END) {
            if (motherboardSlots.getItem(i).item is RamItem) {
                anyRam = true
                break
            }
        }
        if (!anyRam) return ValidationResult.NO_RAM

        return ValidationResult.OK
    }

    override fun broadcastChanges() {
        // Push live power state to the data-slot before the base class
        // diffs and sends container updates. Called from the server's
        // ServerGamePacketListener tick; safe to query caseBE.isPowered()()
        // here — on the server that talks to MachineManager directly.
        powerState.set(if (caseBE.isPowered()) 1 else 0)
        super.broadcastChanges()
    }

    override fun quickMoveStack(player: Player, index: Int): ItemStack {
        val caseSlotCount = SlotDef.COMPUTER_CASE.size
        val slot = slots[index]
        if (!slot.hasItem()) return ItemStack.EMPTY
        val stack = slot.item
        val original = stack.copy()
        if (index < caseSlotCount) {
            if (!moveItemStackTo(stack, caseSlotCount, slots.size, true)) return ItemStack.EMPTY
        } else {
            if (!moveItemStackTo(stack, 0, caseSlotCount, false)) return ItemStack.EMPTY
        }
        if (stack.isEmpty) slot.set(ItemStack.EMPTY) else slot.setChanged()
        return original
    }

    override fun stillValid(player: Player): Boolean = caseBE.stillValid(player)

    companion object {
        @JvmStatic
        fun fromNetwork(id: Int, inv: Inventory, buf: RegistryFriendlyByteBuf): ComputerCaseMenu {
            val pos = buf.readBlockPos()
            val be = inv.player.level().getBlockEntity(pos)
            if (be is ComputerCaseBlockEntity) return ComputerCaseMenu(id, inv, be)
            throw IllegalStateException("No ComputerCaseBlockEntity at $pos")
        }
    }
}
