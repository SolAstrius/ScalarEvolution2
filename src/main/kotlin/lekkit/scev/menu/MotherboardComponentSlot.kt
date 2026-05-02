/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.menu

import lekkit.scev.items.MotherboardInventory
import net.minecraft.world.Container
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

/**
 * [Slot] that backs a motherboard component. Delegates placement
 * validation to [MotherboardInventory] so unusable slots (disabled by
 * motherboard level, no motherboard installed) reject items. An
 * optional owner [Container] receives `setChanged` events so the case
 * block entity marks itself dirty when components move.
 */
class MotherboardComponentSlot(
    private val motherboard: MotherboardInventory,
    index: Int,
    x: Int,
    y: Int,
    private val owner: Container?,
) : Slot(motherboard, index, x, y) {

    override fun mayPlace(stack: ItemStack): Boolean =
        motherboard.canPlaceItem(slotIndex, stack)

    /**
     * Inactive slots are invisible to hover detection, click routing,
     * and background-hint rendering — i.e. the slot behaves as if it
     * doesn't exist. We use this to hide component slots when no
     * motherboard is installed (or when the installed motherboard's
     * tier doesn't enable this slot). Prevents the "hover reveals 14
     * phantom slot positions on an empty panel" UX problem where
     * players could mouse over invisible-but-clickable rectangles.
     *
     * If the slot already holds an item (e.g. a motherboard was yanked
     * out while components were installed), [mayPickup] still allows
     * the player to retrieve it — the slot disappearing visually
     * shouldn't trap items inside.
     */
    override fun isActive(): Boolean = motherboard.isSlotUsable(slotIndex)

    override fun mayPickup(player: Player): Boolean =
        motherboard.isSlotUsable(slotIndex) || !item.isEmpty

    override fun setChanged() {
        super.setChanged()
        owner?.setChanged()
    }

    override fun getMaxStackSize(): Int = 1
}
