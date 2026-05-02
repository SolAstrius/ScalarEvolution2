/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.menu

import java.util.Locale
import java.util.function.Predicate
import lekkit.scev.blockentity.FlashProgrammerBlockEntity
import lekkit.scev.items.FlashItem
import lekkit.scev.items.StorageItem
import lekkit.scev.main.ScevRegistry
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.world.Container
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.DataSlot
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

/**
 * Menu for the flash programmer. Two flash-gated slots (source +
 * target), player inventory, and a single [DataSlot] carrying the last
 * write-operation status so the screen can show a success/fail badge.
 */
class FlashProgrammerMenu(
    id: Int,
    inv: Inventory,
    val prog: FlashProgrammerBlockEntity,
) : AbstractContainerMenu(ScevRegistry.FLASH_PROGRAMMER_MENU.get(), id) {

    private val statusSlot: DataSlot = DataSlot.standalone()

    init {
        // Source = any disk-backed StorageItem (NVMe, HDD) EXCEPT flash —
        // flash doesn't have a server-side image file, you'd just be
        // copying nothing. Use a computer to write your firmware onto an
        // NVMe first, then slot the NVMe here.
        addSlot(FilteredSlot(prog, FlashProgrammerBlockEntity.SLOT_SOURCE,
            SOURCE_SLOT_X, SOURCE_SLOT_Y) { s -> s.item is StorageItem && s.item !is FlashItem })
        // Target: flash only — receives the stamped bytes.
        addSlot(FilteredSlot(prog, FlashProgrammerBlockEntity.SLOT_TARGET,
            TARGET_SLOT_X, TARGET_SLOT_Y) { s -> s.item is FlashItem })

        addPlayerInventory(inv)
        addDataSlot(statusSlot)
    }

    private fun addPlayerInventory(inv: Inventory) {
        for (row in 0 until 3) {
            for (col in 0 until 9) {
                addSlot(Slot(inv, col + row * 9 + 9,
                    PLAYER_INV_X + col * 18, PLAYER_INV_Y + row * 18))
            }
        }
        for (col in 0 until 9) {
            addSlot(Slot(inv, col, PLAYER_INV_X + col * 18, PLAYER_HOTBAR_Y))
        }
    }

    /**
     * Possible outcomes of a write click. Numeric ordinals flow through
     * the data slot; the screen maps back to enum values for display.
     */
    enum class WriteStatus {
        IDLE,
        OK,
        NO_SOURCE,
        NO_TARGET,
        UNREADABLE_SOURCE,
        TOO_LARGE;

        fun langKey(): String = "text.scev.programmer.status." + name.lowercase(Locale.ROOT)

        companion object {
            @JvmStatic
            fun fromOrdinal(n: Int): WriteStatus {
                val vs = values()
                return if (n in vs.indices) vs[n] else IDLE
            }
        }
    }

    /** Server-side: called by the write packet handler after it runs. */
    fun reportStatus(status: WriteStatus) {
        statusSlot.set(status.ordinal)
    }

    fun lastStatus(): WriteStatus = WriteStatus.fromOrdinal(statusSlot.get())

    override fun quickMoveStack(player: Player, index: Int): ItemStack {
        val mySlotCount = FlashProgrammerBlockEntity.SLOT_COUNT
        val slot = slots[index]
        if (!slot.hasItem()) return ItemStack.EMPTY
        val stack = slot.item
        val original = stack.copy()
        if (index < mySlotCount) {
            if (!moveItemStackTo(stack, mySlotCount, slots.size, true)) return ItemStack.EMPTY
        } else {
            if (!moveItemStackTo(stack, 0, mySlotCount, false)) return ItemStack.EMPTY
        }
        if (stack.isEmpty) slot.set(ItemStack.EMPTY) else slot.setChanged()
        return original
    }

    override fun stillValid(player: Player): Boolean = prog.stillValid(player)

    /** Predicate-gated slot, max stack 1. Same pattern as McuBoardMenu. */
    private class FilteredSlot(
        container: Container,
        slotIndex: Int,
        x: Int,
        y: Int,
        private val accept: Predicate<ItemStack>,
    ) : Slot(container, slotIndex, x, y) {
        override fun mayPlace(stack: ItemStack): Boolean = accept.test(stack)
        override fun getMaxStackSize(): Int = 1
    }

    companion object {
        /** Slot pixel coords — mirror the MCU board layout so the player eye
         *  goes to the same place in both GUIs. */
        private const val SOURCE_SLOT_X = 62
        private const val SOURCE_SLOT_Y = 32
        private const val TARGET_SLOT_X = 98
        private const val TARGET_SLOT_Y = 32

        private const val PLAYER_INV_X = 8
        private const val PLAYER_INV_Y = 66
        private const val PLAYER_HOTBAR_Y = 124

        @JvmStatic
        fun fromNetwork(id: Int, inv: Inventory, buf: RegistryFriendlyByteBuf): FlashProgrammerMenu {
            val pos = buf.readBlockPos()
            val be = inv.player.level().getBlockEntity(pos)
            if (be is FlashProgrammerBlockEntity) return FlashProgrammerMenu(id, inv, be)
            throw IllegalStateException("No FlashProgrammerBlockEntity at $pos")
        }
    }
}
