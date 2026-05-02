/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.blockentity

import lekkit.scev.items.FlashItem
import lekkit.scev.items.StorageItem
import lekkit.scev.main.ScevRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.core.NonNullList
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.Container
import net.minecraft.world.ContainerHelper
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.state.BlockState

/**
 * Server-side state for the flash programmer block — a 2-slot Container
 * holding a source flash and a target flash. The actual byte-copy happens
 * on a player click routed through a [lekkit.scev.network.FlashProgrammerWritePayload];
 * the BE just holds the slot contents and drops them on removal.
 */
class FlashProgrammerBlockEntity(pos: BlockPos, state: BlockState) :
    ScevBlockEntity(ScevRegistry.FLASH_PROGRAMMER_BE.get(), pos, state),
    Container {

    protected var items: NonNullList<ItemStack> = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY)

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        items = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY)
        ContainerHelper.loadAllItems(tag, items, registries)
    }

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        ContainerHelper.saveAllItems(tag, items, registries)
    }

    /* ---------------- Container ---------------- */

    override fun getContainerSize(): Int = SLOT_COUNT
    override fun isEmpty(): Boolean = items.all { it.isEmpty }
    override fun getItem(slot: Int): ItemStack =
        if (slot in 0 until SLOT_COUNT) items[slot] else ItemStack.EMPTY

    override fun removeItem(slot: Int, amount: Int): ItemStack {
        val removed = ContainerHelper.removeItem(items, slot, amount)
        if (!removed.isEmpty) setChanged()
        return removed
    }

    override fun removeItemNoUpdate(slot: Int): ItemStack = ContainerHelper.takeItem(items, slot)

    override fun setItem(slot: Int, stack: ItemStack) {
        if (slot in 0 until SLOT_COUNT) {
            items[slot] = stack
            if (stack.count > maxStackSize) stack.count = maxStackSize
            setChanged()
        }
    }

    override fun getMaxStackSize(): Int = 1

    override fun stillValid(player: Player): Boolean =
        !isRemoved && level != null && level!!.getBlockEntity(blockPos) === this &&
        player.distanceToSqr(blockPos.x + 0.5, blockPos.y + 0.5, blockPos.z + 0.5) <= 64.0

    override fun clearContent() {
        items.clear()
        setChanged()
    }

    /**
     * Source: any disk-image-backed storage (NVMe today). The programmer
     * slurps the first MAX_SIZE bytes from the backing .img file.
     * Target: receives the stamped FIRMWARE_BYTES.
     */
    override fun canPlaceItem(slot: Int, stack: ItemStack): Boolean = when (slot) {
        SLOT_SOURCE -> stack.item is StorageItem && stack.item !is FlashItem
        SLOT_TARGET -> stack.item is FlashItem
        else -> false
    }

    companion object {
        const val SLOT_SOURCE = 0
        const val SLOT_TARGET = 1
        const val SLOT_COUNT = 2
    }
}
