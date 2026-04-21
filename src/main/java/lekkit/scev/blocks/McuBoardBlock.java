/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.blocks;

import lekkit.scev.blockentity.McuBoardBlockEntity;
import lekkit.scev.items.FlashItem;
import lekkit.scev.items.SocItem;
import lekkit.scev.main.ScevRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * Block class for the MCU board — a small integrated computer placed in
 * the world. Interaction matches the rest of the SCEv machine family for
 * player muscle memory:
 *
 * <ul>
 *   <li><b>Right-click, empty hand</b> → open the 2-slot install menu.
 *       The in-menu power button handles on/off, same as workstations.</li>
 *   <li><b>Shift+right-click on a powered MCU</b> → open the console readout
 *       — live machine state, firmware, SoC spec, GPIO pin LEDs, and a
 *       reserved panel for future UART output. Parallels the workstation's
 *       "shift+right-click while powered = view framebuffer" convention.</li>
 *   <li><b>Right-click with a SoC or Flash item</b> → auto-insert into the
 *       matching slot (swap the current item into the player's hand if the
 *       slot was already occupied). A small OC1-style QoL shortcut so
 *       players don't need to open the menu just to swap a firmware chip.</li>
 *   <li><b>Right-click with any other item</b> → default block interaction
 *       (same as workstations).</li>
 * </ul>
 */
public class McuBoardBlock extends DirectionalBlock {
    public McuBoardBlock(Properties props) {
        super(props);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new McuBoardBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        return scevTicker(level, type, ScevRegistry.MCU_BOARD_BE.get());
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof McuBoardBlockEntity mcu) || !(player instanceof ServerPlayer sp)) {
            return InteractionResult.PASS;
        }

        // Single behavior: open the install menu. Power lives on the in-menu
        // button, identical to the workstation family. Keeps the interaction
        // model consistent across every SCEv machine.
        sp.openMenu(new MenuProvider() {
            @Override
            public Component getDisplayName() {
                return Component.translatable("container.scev.mcu_board");
            }

            @Override
            public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) {
                return new lekkit.scev.menu.McuBoardMenu(id, inv, mcu);
            }
        }, buf -> buf.writeBlockPos(pos));
        return InteractionResult.CONSUME;
    }

    /**
     * Right-click while holding a SoC or Flash item — auto-install into the
     * matching slot. If the slot is already occupied, hot-swap: the existing
     * item returns to the player's hand, the new one goes into the slot.
     *
     * <p>This is a quality-of-life shortcut so players don't have to open
     * the menu just to pop a firmware chip in. Covers the common case;
     * advanced interactions (moving items between arbitrary slots) still
     * require the menu.
     */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
                                               BlockPos pos, Player player, InteractionHand hand,
                                               BlockHitResult hit) {
        if (level.isClientSide) return ItemInteractionResult.SUCCESS;
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof McuBoardBlockEntity mcu)) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;

        int slot;
        if (stack.getItem() instanceof SocItem)   slot = McuBoardBlockEntity.SLOT_SOC;
        else if (stack.getItem() instanceof FlashItem) slot = McuBoardBlockEntity.SLOT_FLASH;
        else return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;

        // Pull the existing slot content back into the hand (or float if the
        // hand is the swap source — hand always holds the single item stack).
        ItemStack existing = mcu.getItem(slot);
        ItemStack incoming = stack.copyWithCount(1);

        // Swap: put incoming into the slot, existing goes into the player's
        // hand. If the player's hand had >1 items, decrement by 1; we only
        // flash one chip at a time per interaction.
        mcu.setItem(slot, incoming);
        stack.shrink(1);
        if (!existing.isEmpty()) {
            if (stack.isEmpty()) {
                player.setItemInHand(hand, existing);
            } else {
                // Hand still has more SoC/Flash items; stash the removed one
                // in the player's inventory (or drop it at their feet).
                if (!player.getInventory().add(existing)) {
                    player.drop(existing, false);
                }
            }
        }
        mcu.setChanged();
        return ItemInteractionResult.CONSUME;
    }

    /**
     * Break-block: drop both slot contents as items before the block
     * entity is removed. Without this, installed chips would vanish —
     * unlike {@link ComputerCaseBlock}-equivalents that persist their
     * components via the motherboard's NBT, MCU's slots live on the BE
     * itself and need the drop.
     */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.is(newState.getBlock())) {
            if (level.getBlockEntity(pos) instanceof McuBoardBlockEntity mcu) {
                Containers.dropContents(level, pos, mcu);
                level.updateNeighbourForOutputSignal(pos, this);
            }
        }
        super.onRemove(state, level, pos, newState, moved);
    }
}
