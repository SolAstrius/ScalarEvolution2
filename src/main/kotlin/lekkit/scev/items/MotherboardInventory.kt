/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.items

import java.util.function.Supplier
import lekkit.scev.main.ScevDataComponents
import net.minecraft.core.NonNullList
import net.minecraft.world.Container
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.ItemContainerContents

/**
 * [Container] view over a motherboard [ItemStack]'s 14 component slots.
 * Contents are backed by the stack's `MOTHERBOARD_INVENTORY` data
 * component; mutations write back to the stack so the components persist
 * inside the motherboard itself.
 *
 * The backing stack is supplied lazily — callers pass a
 * `Supplier<ItemStack>` so that the view follows the stack if it moves
 * (e.g. the motherboard is pulled out of the case's slot 0 during a menu
 * session). If the supplier returns an empty stack the view behaves as
 * an all-empty container and mutations are discarded (they'd have no
 * home).
 *
 * This is the persistent replacement for the placeholder
 * `SimpleContainer(14)` that used to live inside the menus.
 */
class MotherboardInventory @JvmOverloads constructor(
    private val stackSupplier: Supplier<ItemStack>,
    private val onChanged: Runnable = Runnable {},
) : Container {

    /** Read the current contents; returns a fresh 14-element list. */
    private fun read(): NonNullList<ItemStack> {
        val items = NonNullList.withSize(SIZE, ItemStack.EMPTY)
        val stack = stackSupplier.get()
        if (stack == null || stack.isEmpty) return items
        val contents = stack.getOrDefault(
            ScevDataComponents.MOTHERBOARD_INVENTORY.get(),
            ItemContainerContents.EMPTY,
        )
        contents.copyInto(items)
        return items
    }

    /** Write the given list back to the motherboard's data component. */
    private fun write(items: NonNullList<ItemStack>) {
        val stack = stackSupplier.get()
        if (stack == null || stack.isEmpty) return
        stack.set(ScevDataComponents.MOTHERBOARD_INVENTORY.get(),
            ItemContainerContents.fromItems(items))
    }

    /** Snapshot: copy of the current inventory as a plain list. */
    fun snapshot(): NonNullList<ItemStack> = read()

    /* ---------------- Container ---------------- */

    override fun getContainerSize(): Int = SIZE

    override fun isEmpty(): Boolean = read().all { it.isEmpty }

    override fun getItem(slot: Int): ItemStack {
        if (slot < 0 || slot >= SIZE) return ItemStack.EMPTY
        return read()[slot]
    }

    override fun removeItem(slot: Int, amount: Int): ItemStack {
        if (slot < 0 || slot >= SIZE || amount <= 0) return ItemStack.EMPTY
        val items = read()
        val existing = items[slot]
        if (existing.isEmpty) return ItemStack.EMPTY
        val removed = existing.split(amount)
        items[slot] = existing
        write(items)
        setChanged()
        return removed
    }

    override fun removeItemNoUpdate(slot: Int): ItemStack {
        if (slot < 0 || slot >= SIZE) return ItemStack.EMPTY
        val items = read()
        val removed = items[slot]
        items[slot] = ItemStack.EMPTY
        write(items)
        return removed
    }

    override fun setItem(slot: Int, stack: ItemStack) {
        if (slot < 0 || slot >= SIZE) return
        val items = read()
        items[slot] = stack
        if (stack.count > maxStackSize) stack.count = maxStackSize
        write(items)
        setChanged()
    }

    override fun getMaxStackSize(): Int = 1

    override fun setChanged() {
        onChanged.run()
    }

    override fun stillValid(player: Player): Boolean = true

    override fun clearContent() {
        write(NonNullList.withSize(SIZE, ItemStack.EMPTY))
        setChanged()
    }

    override fun canPlaceItem(slot: Int, stack: ItemStack): Boolean {
        if (slot < 0 || slot >= SIZE) return false
        if (stack.isEmpty) return true
        // No motherboard installed -> can't accept anything (would silently drop).
        val mb = stackSupplier.get()
        if (mb == null || mb.isEmpty) return false
        val mbi = mb.item as? MotherboardItem ?: return false
        // Gate by motherboard level (e.g. a level-1 board has only 2 RAM slots).
        if (!mbi.isSlotEnabled(slot)) return false
        // Component-kind validation. Invalid items are rejected so dropping an
        // unrelated item into, say, the CPU slot doesn't silently stick.
        val expected = expectedKind(slot) ?: return false
        return expected.isInstance(stack.item)
    }

    /** True iff the slot exists and the current motherboard enables it. */
    fun isSlotUsable(slot: Int): Boolean {
        if (slot < 0 || slot >= SIZE) return false
        val mb = stackSupplier.get()
        if (mb == null || mb.isEmpty) return false
        val mbi = mb.item as? MotherboardItem ?: return false
        return mbi.isSlotEnabled(slot)
    }

    companion object {
        @JvmField
        val SIZE: Int = MotherboardItem.INVENTORY_SIZE

        /**
         * Returns the Item class expected in [slot] according to motherboard
         * layout, or `null` if the slot is unused. Used by [canPlaceItem].
         */
        @JvmStatic
        fun expectedKind(slot: Int): Class<*>? = when {
            slot == MotherboardItem.SLOT_CPU -> CpuItem::class.java
            slot == MotherboardItem.SLOT_FLASH -> FlashItem::class.java
            slot in MotherboardItem.SLOT_RAM_START..MotherboardItem.SLOT_RAM_END -> RamItem::class.java
            slot in MotherboardItem.SLOT_NVME_START..MotherboardItem.SLOT_NVME_END -> NvmeItem::class.java
            slot in MotherboardItem.SLOT_PCI_START..MotherboardItem.SLOT_PCI_END -> PciCardItem::class.java
            else -> null
        }
    }
}
