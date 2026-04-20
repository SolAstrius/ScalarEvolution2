/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.items;

import java.util.function.Supplier;
import lekkit.scev.main.ScevDataComponents;
import net.minecraft.core.NonNullList;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

/**
 * {@link Container} view over a motherboard {@link ItemStack}'s 14 component
 * slots. Contents are backed by the stack's {@code MOTHERBOARD_INVENTORY}
 * data component; mutations write back to the stack so the components persist
 * inside the motherboard itself.
 *
 * <p>The backing stack is supplied lazily — callers pass a
 * {@link Supplier<ItemStack>} so that the view follows the stack if it moves
 * (e.g. the motherboard is pulled out of the case's slot 0 during a menu
 * session). If the supplier returns an empty stack the view behaves as an
 * all-empty container and mutations are discarded (they'd have no home).
 *
 * <p>This is the persistent replacement for the placeholder
 * {@code SimpleContainer(14)} that used to live inside the menus.
 */
public final class MotherboardInventory implements Container {
    public static final int SIZE = MotherboardItem.INVENTORY_SIZE;

    private final Supplier<ItemStack> stackSupplier;
    private final Runnable onChanged;

    public MotherboardInventory(Supplier<ItemStack> stackSupplier) {
        this(stackSupplier, () -> {});
    }

    public MotherboardInventory(Supplier<ItemStack> stackSupplier, Runnable onChanged) {
        this.stackSupplier = stackSupplier;
        this.onChanged = onChanged;
    }

    /** Read the current contents; returns a fresh 14-element list. */
    private NonNullList<ItemStack> read() {
        NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
        ItemStack stack = stackSupplier.get();
        if (stack == null || stack.isEmpty()) return items;
        ItemContainerContents contents = stack.getOrDefault(
                ScevDataComponents.MOTHERBOARD_INVENTORY.get(),
                ItemContainerContents.EMPTY);
        contents.copyInto(items);
        return items;
    }

    /** Write the given list back to the motherboard's data component. */
    private void write(NonNullList<ItemStack> items) {
        ItemStack stack = stackSupplier.get();
        if (stack == null || stack.isEmpty()) return;
        stack.set(ScevDataComponents.MOTHERBOARD_INVENTORY.get(),
                ItemContainerContents.fromItems(items));
    }

    /** Snapshot: copy of the current inventory as a plain list. */
    public NonNullList<ItemStack> snapshot() {
        return read();
    }

    /* ---------------- Container ---------------- */

    @Override
    public int getContainerSize() { return SIZE; }

    @Override
    public boolean isEmpty() {
        for (ItemStack s : read()) if (!s.isEmpty()) return false;
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        if (slot < 0 || slot >= SIZE) return ItemStack.EMPTY;
        return read().get(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        if (slot < 0 || slot >= SIZE || amount <= 0) return ItemStack.EMPTY;
        NonNullList<ItemStack> items = read();
        ItemStack existing = items.get(slot);
        if (existing.isEmpty()) return ItemStack.EMPTY;
        ItemStack removed = existing.split(amount);
        items.set(slot, existing);
        write(items);
        setChanged();
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        if (slot < 0 || slot >= SIZE) return ItemStack.EMPTY;
        NonNullList<ItemStack> items = read();
        ItemStack removed = items.get(slot);
        items.set(slot, ItemStack.EMPTY);
        write(items);
        return removed;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot < 0 || slot >= SIZE) return;
        NonNullList<ItemStack> items = read();
        items.set(slot, stack);
        if (stack.getCount() > getMaxStackSize()) stack.setCount(getMaxStackSize());
        write(items);
        setChanged();
    }

    @Override
    public int getMaxStackSize() { return 1; }

    @Override
    public void setChanged() {
        onChanged.run();
    }

    @Override
    public boolean stillValid(Player player) { return true; }

    @Override
    public void clearContent() {
        write(NonNullList.withSize(SIZE, ItemStack.EMPTY));
        setChanged();
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        if (slot < 0 || slot >= SIZE) return false;
        if (stack.isEmpty()) return true;
        // No motherboard installed -> can't accept anything (would silently drop).
        ItemStack mb = stackSupplier.get();
        if (mb == null || mb.isEmpty() || !(mb.getItem() instanceof MotherboardItem mbi)) {
            return false;
        }
        // Gate by motherboard level (e.g. a level-1 board has only 2 RAM slots).
        if (!mbi.isSlotEnabled(slot)) return false;
        // Component-kind validation. Invalid items are rejected so dropping an
        // unrelated item into, say, the CPU slot doesn't silently stick.
        Class<?> expected = expectedKind(slot);
        return expected != null && expected.isInstance(stack.getItem());
    }

    /** True iff the slot exists and the current motherboard enables it. */
    public boolean isSlotUsable(int slot) {
        if (slot < 0 || slot >= SIZE) return false;
        ItemStack mb = stackSupplier.get();
        if (mb == null || mb.isEmpty() || !(mb.getItem() instanceof MotherboardItem mbi)) {
            return false;
        }
        return mbi.isSlotEnabled(slot);
    }

    /**
     * Returns the Item class expected in {@code slot} according to motherboard
     * layout, or {@code null} if the slot is unused. Used by
     * {@link #canPlaceItem(int, ItemStack)}.
     */
    public static Class<?> expectedKind(int slot) {
        if (slot == MotherboardItem.SLOT_CPU) return CpuItem.class;
        if (slot == MotherboardItem.SLOT_FLASH) return FlashItem.class;
        if (slot >= MotherboardItem.SLOT_RAM_START && slot <= MotherboardItem.SLOT_RAM_END)
            return RamItem.class;
        if (slot >= MotherboardItem.SLOT_NVME_START && slot <= MotherboardItem.SLOT_NVME_END)
            return NvmeItem.class;
        if (slot >= MotherboardItem.SLOT_PCI_START && slot <= MotherboardItem.SLOT_PCI_END)
            return PciCardItem.class;
        return null;
    }
}
