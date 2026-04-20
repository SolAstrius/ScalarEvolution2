/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.menu;

import java.util.UUID;
import lekkit.scev.blockentity.ComputerCaseBlockEntity;
import lekkit.scev.items.MotherboardInventory;
import lekkit.scev.main.ScevRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Menu for a computer case block (workstation / powermark / tinkerpad placed in world).
 *
 * <p>Layout: 1 case motherboard slot + 14 component slots (CPU / flash / RAM / NVMe / PCI)
 * at the exact coordinates from the 1.7.10 {@code ContainerComputerCase}, followed by
 * the player's inventory (27 slots) and hotbar (9 slots) — 51 slots total.
 *
 * <p>The 14 component slots are backed by the motherboard item's
 * {@code MOTHERBOARD_INVENTORY} data component via {@link MotherboardInventory}.
 * Contents persist when the menu closes, the motherboard is removed, or the
 * case is broken — the components follow the motherboard around.
 */
public class ComputerCaseMenu extends AbstractContainerMenu {
    private final ComputerCaseBlockEntity caseBE;
    private final MotherboardInventory motherboardSlots;

    public ComputerCaseMenu(int id, Inventory inv, ComputerCaseBlockEntity caseBE) {
        super(ScevRegistry.COMPUTER_CASE_MENU.get(), id);
        this.caseBE = caseBE;
        // Component slots 1..14 read/write through the motherboard stack that
        // lives in the case's own slot 0. If the motherboard is removed, the
        // view becomes empty and mutations are dropped (the motherboard would
        // need to be re-seated to store components).
        this.motherboardSlots = new MotherboardInventory(
                () -> caseBE.getItem(0),
                caseBE::setChanged);

        addMenuSlots();
        addPlayerInventory(inv);
    }

    private void addMenuSlots() {
        // SlotDef.COMPUTER_CASE lists all 15 slots:
        //   index 0 : motherboard slot on the case BE (container slot 0)
        //   index 1..14 : motherboard component slots (own container slots 0..13)
        for (SlotDef def : SlotDef.COMPUTER_CASE) {
            if (def.index() == 0) {
                addSlot(new Slot(caseBE, 0, def.x(), def.y()));
            } else {
                addSlot(new MotherboardComponentSlot(
                        motherboardSlots, def.index() - 1, def.x(), def.y(), caseBE));
            }
        }
    }

    private void addPlayerInventory(Inventory inv) {
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

    public static ComputerCaseMenu fromNetwork(int id, Inventory inv, RegistryFriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        BlockEntity be = inv.player.level().getBlockEntity(pos);
        if (be instanceof ComputerCaseBlockEntity cc) return new ComputerCaseMenu(id, inv, cc);
        throw new IllegalStateException("No ComputerCaseBlockEntity at " + pos);
    }

    public UUID getMachineUuid() { return caseBE.getMachineUUID(); }
    public ComputerCaseBlockEntity getCaseBE() { return caseBE; }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        final int caseSlotCount = SlotDef.COMPUTER_CASE.size();
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (index < caseSlotCount) {
            if (!moveItemStackTo(stack, caseSlotCount, slots.size(), true)) return ItemStack.EMPTY;
        } else {
            if (!moveItemStackTo(stack, 0, caseSlotCount, false)) return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY); else slot.setChanged();
        return original;
    }

    @Override
    public boolean stillValid(Player player) {
        return caseBE.stillValid(player);
    }
}
