/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.blockentity

import lekkit.scev.main.ScevRegistry
import net.minecraft.core.Direction
import net.minecraft.world.item.ItemStack
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.capabilities.Capabilities
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent
import net.neoforged.neoforge.items.IItemHandler

/**
 * Wires [Capabilities.ItemHandler.BLOCK] onto every
 * [ProcessingMachineBlockEntity] subtype so neighbouring automation
 * mods (Create's funnels, Mekanism's logistical transporters, AE2
 * import buses, etc.) can pipe items in and out without modspecial
 * integration code.
 *
 * **Side-aware routing:**
 *  - TOP    → input slots (insert allowed)
 *  - BOTTOM → output slot (extract allowed)
 *  - SIDES  → both (insert into inputs, extract from output)
 *  - null (no specific side) → both
 *
 * Expansion-card slots are intentionally **excluded** from the
 * exposed handler — those are player-installed configuration, not
 * an automation surface.
 */
object ProcessingMachineCapabilities {

    @JvmStatic
    fun register(modBus: IEventBus) {
        modBus.addListener(::onRegisterCapabilities)
    }

    private fun onRegisterCapabilities(event: RegisterCapabilitiesEvent) {
        val beTypes = listOf(
            ScevRegistry.INK_MIXER_BE,
            ScevRegistry.RIBBON_IMPREGNATOR_BE,
        )
        for (typeHolder in beTypes) {
            event.registerBlockEntity(
                Capabilities.ItemHandler.BLOCK,
                typeHolder.get(),
            ) { be, side -> ProcessingMachineItemHandler(be, side) }
        }
    }
}

/**
 * Side-aware [IItemHandler] view of a processing machine. Reads
 * directly from / writes directly to the BE's slots; no buffering.
 *
 * Side filtering policy (see [ProcessingMachineCapabilities] kdoc):
 * input slots accept inserts from TOP + side faces, output slot
 * permits extracts from BOTTOM + side faces.
 */
private class ProcessingMachineItemHandler(
    private val be: ProcessingMachineBlockEntity,
    private val side: Direction?,
) : IItemHandler {

    /** True if `side` permits inserting into the given slot. */
    private fun canInsert(slot: Int): Boolean {
        if (slot >= be.outputSlotIndex) return false  // never insert into output / expansion
        return when (side) {
            null, Direction.UP -> true                // top OR no-side: insert OK
            Direction.DOWN     -> false               // bottom is extract-only
            else               -> true                // sides: insert OK
        }
    }

    /** True if `side` permits extracting from the given slot. */
    private fun canExtract(slot: Int): Boolean {
        if (slot != be.outputSlotIndex) return false  // only output is extractable
        return when (side) {
            null, Direction.DOWN -> true              // bottom OR no-side: extract OK
            Direction.UP         -> false             // top is insert-only
            else                 -> true              // sides: extract OK
        }
    }

    override fun getSlots(): Int = be.ioSlotCount

    override fun getStackInSlot(slot: Int): ItemStack = be.getItem(slot)

    override fun insertItem(slot: Int, stack: ItemStack, simulate: Boolean): ItemStack {
        if (stack.isEmpty || !canInsert(slot)) return stack
        if (!be.canPlaceItem(slot, stack)) return stack
        val current = be.getItem(slot)
        if (current.isEmpty) {
            val limit = minOf(stack.maxStackSize, be.maxStackSize)
            val placed = minOf(stack.count, limit)
            if (!simulate) {
                val copy = stack.copyWithCount(placed)
                be.setItem(slot, copy)
            }
            return if (placed == stack.count) ItemStack.EMPTY
                   else stack.copyWithCount(stack.count - placed)
        }
        if (!ItemStack.isSameItemSameComponents(current, stack)) return stack
        val limit = minOf(current.maxStackSize, be.maxStackSize)
        val space = limit - current.count
        if (space <= 0) return stack
        val placed = minOf(stack.count, space)
        if (!simulate) {
            current.grow(placed)
            be.setChanged()
        }
        return if (placed == stack.count) ItemStack.EMPTY
               else stack.copyWithCount(stack.count - placed)
    }

    override fun extractItem(slot: Int, amount: Int, simulate: Boolean): ItemStack {
        if (amount <= 0 || !canExtract(slot)) return ItemStack.EMPTY
        val current = be.getItem(slot)
        if (current.isEmpty) return ItemStack.EMPTY
        val taken = minOf(amount, current.count)
        return if (simulate) current.copyWithCount(taken)
               else be.removeItem(slot, taken)
    }

    override fun getSlotLimit(slot: Int): Int = be.maxStackSize

    override fun isItemValid(slot: Int, stack: ItemStack): Boolean =
        canInsert(slot) && be.canPlaceItem(slot, stack)
}
