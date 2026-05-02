/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.menu

import lekkit.scev.items.MotherboardInventory
import lekkit.scev.items.MotherboardItem
import lekkit.scev.main.ScevRegistry
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

/**
 * Menu opened by right-clicking a motherboard item in the player's
 * hand. Layout: 14 component slots + player inventory (fat shape).
 *
 * The 14 component slots are backed by the held motherboard stack's
 * `MOTHERBOARD_INVENTORY` data component. The menu carries an
 * inventory slot index that identifies which of the player's slots
 * holds the motherboard; the view follows that slot so mutations write
 * back to the actual held ItemStack.
 */
class MotherboardMenu(
    id: Int,
    private val playerInv: Inventory,
    /** Slot index in the player's main inventory that holds the motherboard. */
    private val motherboardInvSlot: Int,
) : AbstractContainerMenu(ScevRegistry.MOTHERBOARD_MENU.get(), id) {

    private val motherboardSlots: MotherboardInventory = MotherboardInventory(
        { playerInv.getItem(motherboardInvSlot) },
        Runnable {},
    )

    init {
        for (def in SlotDef.MOTHERBOARD) {
            addSlot(MotherboardComponentSlot(motherboardSlots, def.index, def.x, def.y, null))
        }

        for (row in 0 until 3) {
            for (col in 0 until 9) {
                addSlot(Slot(playerInv, col + row * 9 + 9,
                    8 + col * 18, SlotDef.FAT_PLAYER_INV_Y + row * 18))
            }
        }
        for (col in 0 until 9) {
            addSlot(Slot(playerInv, col, 8 + col * 18, SlotDef.FAT_HOTBAR_Y))
        }
    }

    /**
     * @return the [MotherboardItem] the menu is currently backed by, or
     *         `null` if the stack at [motherboardInvSlot] is no longer
     *         a motherboard (e.g. the player moved it away). Screens
     *         use this for tier-dependent rendering decisions — slot
     *         hints, background selection — without having to re-read
     *         the stack each frame.
     */
    fun getMotherboardItem(): MotherboardItem? {
        val held = playerInv.getItem(motherboardInvSlot)
        return held.item as? MotherboardItem
    }

    override fun quickMoveStack(player: Player, index: Int): ItemStack {
        val mbSlotCount = SlotDef.MOTHERBOARD.size
        val slot = slots[index]
        if (!slot.hasItem()) return ItemStack.EMPTY
        val stack = slot.item
        val original = stack.copy()
        if (index < mbSlotCount) {
            if (!moveItemStackTo(stack, mbSlotCount, slots.size, true)) return ItemStack.EMPTY
        } else {
            if (!moveItemStackTo(stack, 0, mbSlotCount, false)) return ItemStack.EMPTY
        }
        if (stack.isEmpty) slot.set(ItemStack.EMPTY) else slot.setChanged()
        return original
    }

    override fun stillValid(player: Player): Boolean {
        // Still valid as long as the motherboard is where we expect it to be.
        val held = playerInv.getItem(motherboardInvSlot)
        return !held.isEmpty && held.item is MotherboardItem
    }

    companion object {
        @JvmStatic
        fun fromNetwork(id: Int, inv: Inventory, buf: RegistryFriendlyByteBuf): MotherboardMenu =
            MotherboardMenu(id, inv, buf.readVarInt())
    }
}
