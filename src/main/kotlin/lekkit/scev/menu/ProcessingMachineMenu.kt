/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.menu

import lekkit.scev.blockentity.ProcessingMachineBlockEntity
import lekkit.scev.expansion.IExpansionCard
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.DataSlot
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

/**
 * Shared menu base. Slot indices laid out positionally:
 *  - 0..(inputSlotCount-1)         : input slots
 *  - inputSlotCount                : output slot (extract-only)
 *  - (inputSlotCount+1)..ioEnd-1   : expansion-card slots
 *  - then 27 inventory + 9 hotbar
 *
 * Slot pixel coords are stand-ins; owo's `slotAsComponent` overrides
 * them at layout time anyway, but vanilla still requires *something*
 * for click-routing fallbacks to land at sane locations.
 */
abstract class ProcessingMachineMenu(
    type: MenuType<*>,
    containerId: Int,
    val inv: Inventory,
    val be: ProcessingMachineBlockEntity,
) : AbstractContainerMenu(type, containerId) {

    private val progressSlot: DataSlot = DataSlot.standalone()
    private val progressMaxSlot: DataSlot = DataSlot.standalone()

    init {
        // Input slots — anything goes (BE-side recipe match decides).
        for (i in 0 until be.inputSlotCount) {
            addSlot(Slot(be, i, INPUT_X + i * SLOT_PITCH, INPUT_Y))
        }
        // Output slot — extract-only.
        addSlot(object : Slot(be, be.outputSlotIndex, OUTPUT_X, OUTPUT_Y) {
            override fun mayPlace(stack: ItemStack): Boolean = false
        })
        // Expansion-card slots.
        for (i in 0 until be.expansionSlotCount) {
            val expIndex = be.firstExpansionSlotIndex + i
            addSlot(object : Slot(be, expIndex,
                EXPANSION_X, EXPANSION_Y_BASE + i * SLOT_PITCH) {
                override fun mayPlace(stack: ItemStack): Boolean = stack.item is IExpansionCard
                override fun getMaxStackSize(): Int = 1
            })
        }

        // Player inventory + hotbar (standard fat-shape coords).
        for (row in 0 until 3) {
            for (col in 0 until 9) {
                addSlot(Slot(inv, col + row * 9 + 9,
                    8 + col * 18,
                    SlotDef.FAT_PLAYER_INV_Y + row * 18))
            }
        }
        for (col in 0 until 9) {
            addSlot(Slot(inv, col,
                8 + col * 18, SlotDef.FAT_HOTBAR_Y))
        }

        addDataSlot(progressSlot)
        addDataSlot(progressMaxSlot)
    }

    override fun broadcastChanges() {
        progressSlot.set(be.progressForDisplay())
        progressMaxSlot.set(be.progressMax())
        super.broadcastChanges()
    }

    fun progress(): Int = progressSlot.get()
    fun progressMax(): Int = progressMaxSlot.get()

    override fun stillValid(player: Player): Boolean = be.stillValid(player)

    override fun quickMoveStack(player: Player, index: Int): ItemStack {
        val totalMachineSlots = be.ioSlotCount + be.expansionSlotCount
        val slot = slots[index]
        if (!slot.hasItem()) return ItemStack.EMPTY
        val stack = slot.item
        val original = stack.copy()

        if (index < totalMachineSlots) {
            // Machine → player.
            if (!moveItemStackTo(stack, totalMachineSlots, slots.size, true)) return ItemStack.EMPTY
        } else {
            val item = stack.item
            val ok = if (item is IExpansionCard) {
                // Try the expansion-slot range.
                moveItemStackTo(stack,
                    be.firstExpansionSlotIndex, totalMachineSlots, false)
            } else {
                // Try every input slot in order — first one that takes it wins.
                moveItemStackTo(stack, 0, be.inputSlotCount, false)
            }
            if (!ok) return ItemStack.EMPTY
        }
        if (stack.isEmpty) slot.set(ItemStack.EMPTY) else slot.setChanged()
        return original
    }

    companion object {
        const val INPUT_X: Int = 44
        const val INPUT_Y: Int = 35
        const val OUTPUT_X: Int = 116
        const val OUTPUT_Y: Int = 35
        const val EXPANSION_X: Int = 152
        const val EXPANSION_Y_BASE: Int = 17
        const val SLOT_PITCH: Int = 18
    }
}
