/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.menu

import java.util.function.Predicate
import lekkit.scev.blockentity.TeletypeBlockEntity
import lekkit.scev.main.ScevRegistry
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.world.Container
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

class TeletypeMenu(
    containerId: Int, val inv: Inventory, val be: TeletypeBlockEntity,
) : AbstractContainerMenu(ScevRegistry.TELETYPE_MENU.get(), containerId) {

    init {
        // Paper roll slot — gates on item type.
        addSlot(FilteredSlot(be, TeletypeBlockEntity.SLOT_PAPER, PAPER_X, SLOT_Y) {
            it.item === ScevRegistry.PAPER_ROLL.get()
        })
        // Ribbon slot.
        addSlot(FilteredSlot(be, TeletypeBlockEntity.SLOT_RIBBON, RIBBON_X, SLOT_Y) {
            it.item === ScevRegistry.RIBBON.get()
        })
        // Player inventory + hotbar.
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

    override fun stillValid(player: Player): Boolean = be.stillValid(player)

    override fun quickMoveStack(player: Player, index: Int): ItemStack {
        val total = TeletypeBlockEntity.SLOT_COUNT
        val slot = slots[index]
        if (!slot.hasItem()) return ItemStack.EMPTY
        val stack = slot.item
        val original = stack.copy()
        if (index < total) {
            if (!moveItemStackTo(stack, total, slots.size, true)) return ItemStack.EMPTY
        } else {
            // Player → machine: try paper first, then ribbon.
            val ok = when {
                stack.item === ScevRegistry.PAPER_ROLL.get() ->
                    moveItemStackTo(stack, TeletypeBlockEntity.SLOT_PAPER,
                        TeletypeBlockEntity.SLOT_PAPER + 1, false)
                stack.item === ScevRegistry.RIBBON.get() ->
                    moveItemStackTo(stack, TeletypeBlockEntity.SLOT_RIBBON,
                        TeletypeBlockEntity.SLOT_RIBBON + 1, false)
                else -> false
            }
            if (!ok) return ItemStack.EMPTY
        }
        if (stack.isEmpty) slot.set(ItemStack.EMPTY) else slot.setChanged()
        return original
    }

    private class FilteredSlot(
        c: Container, idx: Int, x: Int, y: Int,
        private val accept: Predicate<ItemStack>,
    ) : Slot(c, idx, x, y) {
        override fun mayPlace(stack: ItemStack): Boolean = accept.test(stack)
        override fun getMaxStackSize(): Int = 1
    }

    companion object {
        private const val PAPER_X = 44
        private const val RIBBON_X = 116
        private const val SLOT_Y = 35

        @JvmStatic
        fun fromNetwork(id: Int, inv: Inventory, buf: RegistryFriendlyByteBuf): TeletypeMenu {
            val pos = buf.readBlockPos()
            val be = inv.player.level().getBlockEntity(pos) as? TeletypeBlockEntity
                ?: throw IllegalStateException("No TeletypeBlockEntity at $pos")
            return TeletypeMenu(id, inv, be)
        }
    }
}
