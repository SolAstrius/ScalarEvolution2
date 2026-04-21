/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.menu;

import lekkit.scev.items.MotherboardInventory;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * {@link Slot} that backs a motherboard component. Delegates placement
 * validation to {@link MotherboardInventory} so unusable slots (disabled by
 * motherboard level, no motherboard installed) reject items. An optional
 * owner {@link Container} receives {@code setChanged} events so the case
 * block entity marks itself dirty when components move.
 */
public class MotherboardComponentSlot extends Slot {
    private final MotherboardInventory motherboard;
    private final @Nullable Container owner;

    public MotherboardComponentSlot(MotherboardInventory motherboard, int index, int x, int y,
                                    @Nullable Container owner) {
        super(motherboard, index, x, y);
        this.motherboard = motherboard;
        this.owner = owner;
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        return motherboard.canPlaceItem(getSlotIndex(), stack);
    }

    /**
     * Inactive slots are invisible to hover detection, click routing, and
     * background-hint rendering — i.e. the slot behaves as if it doesn't
     * exist. We use this to hide component slots when no motherboard is
     * installed (or when the installed motherboard's tier doesn't enable
     * this slot). Prevents the "hover reveals 14 phantom slot positions
     * on an empty panel" UX problem where players could mouse over
     * invisible-but-clickable rectangles.
     *
     * <p>If the slot already holds an item (e.g. a motherboard was yanked
     * out while components were installed), {@link #mayPickup} still
     * allows the player to retrieve it — the slot disappearing visually
     * shouldn't trap items inside.
     */
    @Override
    public boolean isActive() {
        return motherboard.isSlotUsable(getSlotIndex());
    }

    @Override
    public boolean mayPickup(Player player) {
        return motherboard.isSlotUsable(getSlotIndex()) || !getItem().isEmpty();
    }

    @Override
    public void setChanged() {
        super.setChanged();
        if (owner != null) owner.setChanged();
    }

    @Override
    public int getMaxStackSize() { return 1; }
}
