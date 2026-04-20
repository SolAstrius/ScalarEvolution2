/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.menu;

import java.util.UUID;
import lekkit.scev.blockentity.ComputerCaseBlockEntity;
import lekkit.scev.blockentity.TinkerpadBlockEntity;
import lekkit.scev.main.ScevRegistry;
import lekkit.scev.server.IMachineHandle;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

/**
 * Menu opened to view a running machine's screen. Has no inventory slots —
 * just the player inventory so shift-click doesn't crash.
 */
public class MachineMenu extends AbstractContainerMenu {
    private final UUID machineUuid;
    private final @Nullable IMachineHandle machineHandle;

    public MachineMenu(int containerId, Inventory inv, UUID machineUuid,
                       @Nullable IMachineHandle handle) {
        super(ScevRegistry.MACHINE_MENU.get(), containerId);
        this.machineUuid = machineUuid;
        this.machineHandle = handle;

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new net.minecraft.world.inventory.Slot(inv, col + row * 9 + 9,
                        8 + col * 18, SlotDef.FAT_PLAYER_INV_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new net.minecraft.world.inventory.Slot(inv, col, 8 + col * 18, SlotDef.FAT_HOTBAR_Y));
        }
    }

    public static MachineMenu fromNetwork(int containerId, Inventory inv, RegistryFriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        BlockEntity be = inv.player.level().getBlockEntity(pos);
        if (be instanceof TinkerpadBlockEntity t) {
            return new MachineMenu(containerId, inv, t.getMachineUUID(), t);
        }
        if (be instanceof ComputerCaseBlockEntity c) {
            return new MachineMenu(containerId, inv, c.getMachineUUID(), c);
        }
        return new MachineMenu(containerId, inv, UUID.randomUUID(), null);
    }

    public UUID getMachineUuid() { return machineUuid; }
    public @Nullable IMachineHandle getMachineHandle() { return machineHandle; }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return machineHandle != null && machineHandle.isValid();
    }
}
