/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.blockentity;

import lekkit.scev.items.FlashItem;
import lekkit.scev.items.NvmeItem;
import lekkit.scev.items.StorageItem;
import lekkit.scev.main.ScevRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Server-side state for the flash programmer block — a 2-slot Container
 * holding a source flash and a target flash. The actual byte-copy happens
 * on a player click routed through a {@link lekkit.scev.network.FlashProgrammerWritePayload};
 * the BE just holds the slot contents and drops them on removal.
 */
public class FlashProgrammerBlockEntity extends ScevBlockEntity implements Container {
    public static final int SLOT_SOURCE = 0;
    public static final int SLOT_TARGET = 1;
    public static final int SLOT_COUNT = 2;

    protected NonNullList<ItemStack> items = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);

    public FlashProgrammerBlockEntity(BlockPos pos, BlockState state) {
        super(ScevRegistry.FLASH_PROGRAMMER_BE.get(), pos, state);
    }

    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        items = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(tag, items, registries);
    }

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ContainerHelper.saveAllItems(tag, items, registries);
    }

    /* ---------------- Container ---------------- */

    @Override public int getContainerSize() { return SLOT_COUNT; }

    @Override public boolean isEmpty() {
        for (ItemStack s : items) if (!s.isEmpty()) return false;
        return true;
    }

    @Override public ItemStack getItem(int slot) {
        return slot >= 0 && slot < SLOT_COUNT ? items.get(slot) : ItemStack.EMPTY;
    }

    @Override public ItemStack removeItem(int slot, int amount) {
        ItemStack removed = ContainerHelper.removeItem(items, slot, amount);
        if (!removed.isEmpty()) setChanged();
        return removed;
    }

    @Override public ItemStack removeItemNoUpdate(int slot) {
        return ContainerHelper.takeItem(items, slot);
    }

    @Override public void setItem(int slot, ItemStack stack) {
        if (slot >= 0 && slot < SLOT_COUNT) {
            items.set(slot, stack);
            if (stack.getCount() > getMaxStackSize()) stack.setCount(getMaxStackSize());
            setChanged();
        }
    }

    @Override public int getMaxStackSize() { return 1; }

    @Override public boolean stillValid(Player player) {
        return !isRemoved() && level != null
                && level.getBlockEntity(worldPosition) == this
                && player.distanceToSqr(
                        worldPosition.getX() + 0.5,
                        worldPosition.getY() + 0.5,
                        worldPosition.getZ() + 0.5) <= 64.0;
    }

    @Override public void clearContent() {
        items.clear();
        setChanged();
    }

    @Override public boolean canPlaceItem(int slot, ItemStack stack) {
        return switch (slot) {
            // Source is any disk-image-backed storage — NVMe today, HDD if
            // that lineage ever grows a file-backed image. The programmer
            // slurps the first MAX_SIZE bytes from the backing .img file.
            case SLOT_SOURCE -> stack.getItem() instanceof StorageItem
                    && !(stack.getItem() instanceof FlashItem);
            // Target receives the stamped FIRMWARE_BYTES.
            case SLOT_TARGET -> stack.getItem() instanceof FlashItem;
            default -> false;
        };
    }
}
