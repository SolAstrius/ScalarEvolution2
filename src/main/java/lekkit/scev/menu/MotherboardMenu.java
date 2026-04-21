/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.menu;

import lekkit.scev.items.MotherboardInventory;
import lekkit.scev.items.MotherboardItem;
import lekkit.scev.main.ScevRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Menu opened by right-clicking a motherboard item in the player's hand.
 * Layout: 14 component slots + player inventory (fat shape).
 *
 * <p>The 14 component slots are backed by the held motherboard stack's
 * {@code MOTHERBOARD_INVENTORY} data component. The menu carries an
 * inventory slot index that identifies which of the player's slots holds
 * the motherboard; the view follows that slot so mutations write back to
 * the actual held ItemStack.
 */
public class MotherboardMenu extends AbstractContainerMenu {
    private final MotherboardInventory motherboardSlots;
    private final Inventory playerInv;
    /** Slot index in the player's main inventory that holds the motherboard. */
    private final int motherboardInvSlot;

    public MotherboardMenu(int id, Inventory inv, int motherboardInvSlot) {
        super(ScevRegistry.MOTHERBOARD_MENU.get(), id);
        this.playerInv = inv;
        this.motherboardInvSlot = motherboardInvSlot;
        this.motherboardSlots = new MotherboardInventory(
                () -> inv.getItem(motherboardInvSlot),
                () -> {});

        for (SlotDef def : SlotDef.MOTHERBOARD) {
            addSlot(new MotherboardComponentSlot(motherboardSlots, def.index(), def.x(), def.y(), null));
        }

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inv, col + row * 9 + 9,
                        8 + col * 18, SlotDef.FAT_PLAYER_INV_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inv, col, 8 + col * 18, SlotDef.FAT_HOTBAR_Y));
        }
    }

    public static MotherboardMenu fromNetwork(int id, Inventory inv, RegistryFriendlyByteBuf buf) {
        int slot = buf.readVarInt();
        return new MotherboardMenu(id, inv, slot);
    }

    /**
     * @return the {@link MotherboardItem} the menu is currently backed by, or
     *         {@code null} if the stack at {@link #motherboardInvSlot} is no
     *         longer a motherboard (e.g. the player moved it away). Screens
     *         use this for tier-dependent rendering decisions — slot hints,
     *         background selection — without having to re-read the stack each
     *         frame.
     */
    public MotherboardItem getMotherboardItem() {
        ItemStack held = playerInv.getItem(motherboardInvSlot);
        return held.getItem() instanceof MotherboardItem mi ? mi : null;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        final int mbSlotCount = SlotDef.MOTHERBOARD.size();
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (index < mbSlotCount) {
            if (!moveItemStackTo(stack, mbSlotCount, slots.size(), true)) return ItemStack.EMPTY;
        } else {
            if (!moveItemStackTo(stack, 0, mbSlotCount, false)) return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY); else slot.setChanged();
        return original;
    }

    @Override
    public boolean stillValid(Player player) {
        // Still valid as long as the motherboard is where we expect it to be.
        ItemStack held = playerInv.getItem(motherboardInvSlot);
        return !held.isEmpty() && held.getItem() instanceof MotherboardItem;
    }
}
