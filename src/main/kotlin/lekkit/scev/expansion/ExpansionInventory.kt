/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.expansion

import net.minecraft.core.HolderLookup
import net.minecraft.core.NonNullList
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.Container
import net.minecraft.world.ContainerHelper
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack

/**
 * Fixed-size slot bank holding [IExpansionCard]-implementing items.
 * Owned by every [lekkit.scev.blockentity.ProcessingMachineBlockEntity];
 * size is set at construction time by the concrete BE class.
 *
 * Cards are stack-of-1 — they're physical hardware, not stackable
 * supplies. mayPlace gates against [IExpansionCard], so any item the
 * mod adds is automatically slot-compatible just by implementing the
 * marker interface.
 *
 * **No callbacks on install/uninstall** today. Capability discovery is
 * pull-based: the host queries [installedKinds] when it needs to know
 * what's available. If an installed card requires per-tick work
 * (currently none do), add a `tick()` hook that walks the slots.
 */
class ExpansionInventory(val size: Int) : Container {

    private val items: NonNullList<ItemStack> = NonNullList.withSize(size, ItemStack.EMPTY)

    /** Optional dirty callback — wired by the owning BE to propagate
     *  setChanged() up to its own setChanged. */
    var onChanged: (() -> Unit)? = null

    /* ---------------- NBT ---------------- */

    fun load(tag: CompoundTag, registries: HolderLookup.Provider) {
        // items is a fixed-size NonNullList — clear() resets entries to
        // EMPTY in place, but add() would throw UnsupportedOperationException.
        // ContainerHelper.loadAllItems uses set(index, ...) so it works
        // on the fixed-size list directly without needing to repopulate.
        items.clear()
        if (tag.contains(NBT_KEY)) {
            val sub = tag.getCompound(NBT_KEY)
            ContainerHelper.loadAllItems(sub, items, registries)
        }
    }

    fun save(tag: CompoundTag, registries: HolderLookup.Provider) {
        val sub = CompoundTag()
        ContainerHelper.saveAllItems(sub, items, registries)
        tag.put(NBT_KEY, sub)
    }

    /* ---------------- Container ---------------- */

    override fun getContainerSize(): Int = size
    override fun isEmpty(): Boolean = items.all { it.isEmpty }
    override fun getItem(slot: Int): ItemStack =
        if (slot in 0 until size) items[slot] else ItemStack.EMPTY

    override fun removeItem(slot: Int, amount: Int): ItemStack {
        val out = ContainerHelper.removeItem(items, slot, amount)
        if (!out.isEmpty) setChanged()
        return out
    }

    override fun removeItemNoUpdate(slot: Int): ItemStack {
        val out = ContainerHelper.takeItem(items, slot)
        if (!out.isEmpty) setChanged()
        return out
    }

    override fun setItem(slot: Int, stack: ItemStack) {
        if (slot !in 0 until size) return
        items[slot] = stack
        if (stack.count > maxStackSize) stack.count = maxStackSize
        setChanged()
    }

    override fun getMaxStackSize(): Int = 1

    override fun setChanged() { onChanged?.invoke() }

    override fun stillValid(player: Player): Boolean = true

    override fun clearContent() {
        items.clear()  // resets to EMPTY; size unchanged on fixed-size list
        setChanged()
    }

    override fun canPlaceItem(slot: Int, stack: ItemStack): Boolean =
        stack.item is IExpansionCard

    /* ---------------- Capability queries ---------------- */

    /** Set of kinds currently installed across all slots. Empty when
     *  no cards are seated. Computed on each call — cheap, called only
     *  on capability lookup or BE/JADE refresh. */
    fun installedKinds(): Set<ExpansionCardKind> {
        val out = LinkedHashSet<ExpansionCardKind>(size)
        for (i in 0 until size) {
            val card = items[i].item as? IExpansionCard ?: continue
            out.add(card.cardKind)
        }
        return out
    }

    /** True if any installed card is of [kind]. Convenience over the
     *  set lookup for hot checks (e.g. "do I expose a serial port?"). */
    fun has(kind: ExpansionCardKind): Boolean {
        for (i in 0 until size) {
            val card = items[i].item as? IExpansionCard ?: continue
            if (card.cardKind == kind) return true
        }
        return false
    }

    companion object {
        private const val NBT_KEY: String = "expansion"
    }
}
