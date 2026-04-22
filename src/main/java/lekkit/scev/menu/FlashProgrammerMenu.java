/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.menu;

import java.util.Locale;
import lekkit.scev.blockentity.FlashProgrammerBlockEntity;
import lekkit.scev.items.FlashItem;
import lekkit.scev.items.StorageItem;
import lekkit.scev.main.ScevRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Menu for the flash programmer. Two flash-gated slots (source + target),
 * player inventory, and a single {@link DataSlot} carrying the last
 * write-operation status so the screen can show a success/fail badge.
 */
public class FlashProgrammerMenu extends AbstractContainerMenu {
    /** Slot pixel coords — mirror the MCU board layout so the player eye
     *  goes to the same place in both GUIs. */
    private static final int SOURCE_SLOT_X = 62;
    private static final int SOURCE_SLOT_Y = 32;
    private static final int TARGET_SLOT_X = 98;
    private static final int TARGET_SLOT_Y = 32;

    private static final int PLAYER_INV_X = 8;
    private static final int PLAYER_INV_Y = 66;
    private static final int PLAYER_HOTBAR_Y = 124;

    private final FlashProgrammerBlockEntity prog;
    private final DataSlot statusSlot = DataSlot.standalone();

    public FlashProgrammerMenu(int id, Inventory inv, FlashProgrammerBlockEntity prog) {
        super(ScevRegistry.FLASH_PROGRAMMER_MENU.get(), id);
        this.prog = prog;

        // Source = any disk-backed StorageItem (NVMe, HDD) EXCEPT flash —
        // flash doesn't have a server-side image file, you'd just be
        // copying nothing. Use a computer to write your firmware onto an
        // NVMe first, then slot the NVMe here.
        addSlot(new FilteredSlot(prog, FlashProgrammerBlockEntity.SLOT_SOURCE,
                SOURCE_SLOT_X, SOURCE_SLOT_Y,
                s -> s.getItem() instanceof StorageItem && !(s.getItem() instanceof FlashItem)));
        // Target: flash only — receives the stamped bytes.
        addSlot(new FilteredSlot(prog, FlashProgrammerBlockEntity.SLOT_TARGET,
                TARGET_SLOT_X, TARGET_SLOT_Y,
                s -> s.getItem() instanceof FlashItem));

        addPlayerInventory(inv);
        addDataSlot(statusSlot);
    }

    private void addPlayerInventory(Inventory inv) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inv, col + row * 9 + 9,
                        PLAYER_INV_X + col * 18, PLAYER_INV_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inv, col, PLAYER_INV_X + col * 18, PLAYER_HOTBAR_Y));
        }
    }

    public static FlashProgrammerMenu fromNetwork(int id, Inventory inv, RegistryFriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        BlockEntity be = inv.player.level().getBlockEntity(pos);
        if (be instanceof FlashProgrammerBlockEntity p) return new FlashProgrammerMenu(id, inv, p);
        throw new IllegalStateException("No FlashProgrammerBlockEntity at " + pos);
    }

    public FlashProgrammerBlockEntity getProgrammer() { return prog; }

    /**
     * Possible outcomes of a write click. Numeric ordinals flow through
     * the data slot; the screen maps back to enum values for display.
     */
    public enum WriteStatus {
        IDLE,
        OK,
        NO_SOURCE,
        NO_TARGET,
        UNREADABLE_SOURCE,
        TOO_LARGE;

        public static WriteStatus fromOrdinal(int n) {
            WriteStatus[] vs = values();
            return n >= 0 && n < vs.length ? vs[n] : IDLE;
        }

        public String langKey() {
            return "text.scev.programmer.status." + name().toLowerCase(Locale.ROOT);
        }
    }

    /** Server-side: called by the write packet handler after it runs. */
    public void reportStatus(WriteStatus status) {
        statusSlot.set(status.ordinal());
    }

    public WriteStatus lastStatus() { return WriteStatus.fromOrdinal(statusSlot.get()); }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        final int mySlotCount = FlashProgrammerBlockEntity.SLOT_COUNT;
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (index < mySlotCount) {
            if (!moveItemStackTo(stack, mySlotCount, slots.size(), true)) return ItemStack.EMPTY;
        } else {
            if (!moveItemStackTo(stack, 0, mySlotCount, false)) return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY); else slot.setChanged();
        return original;
    }

    @Override
    public boolean stillValid(Player player) {
        return prog.stillValid(player);
    }

    /** Predicate-gated slot, max stack 1. Same pattern as McuBoardMenu. */
    private static final class FilteredSlot extends Slot {
        private final java.util.function.Predicate<ItemStack> accept;

        FilteredSlot(net.minecraft.world.Container container, int slotIndex, int x, int y,
                     java.util.function.Predicate<ItemStack> accept) {
            super(container, slotIndex, x, y);
            this.accept = accept;
        }

        @Override public boolean mayPlace(ItemStack stack) { return accept.test(stack); }
        @Override public int getMaxStackSize() { return 1; }
    }
}
