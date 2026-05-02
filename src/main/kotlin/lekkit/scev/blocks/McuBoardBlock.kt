/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.blocks

import lekkit.scev.blockentity.McuBoardBlockEntity
import lekkit.scev.items.FlashItem
import lekkit.scev.items.SocItem
import lekkit.scev.main.ScevRegistry
import lekkit.scev.menu.McuBoardMenu
import lekkit.scev.menu.openScevMenu
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Containers
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.ItemInteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult

/**
 * Block class for the MCU board — a small integrated computer placed in
 * the world. Interaction matches the rest of the SCEv machine family
 * for player muscle memory:
 *
 * - **Right-click, empty hand** → open the 2-slot install menu. The
 *   in-menu power button handles on/off, same as workstations.
 * - **Shift+right-click on a powered MCU** → open the console readout
 *   — live machine state, firmware, SoC spec, GPIO pin LEDs, and a
 *   reserved panel for future UART output. Parallels the workstation's
 *   "shift+right-click while powered = view framebuffer" convention.
 * - **Right-click with a SoC or Flash item** → auto-insert into the
 *   matching slot (swap the current item into the player's hand if the
 *   slot was already occupied). A small OC1-style QoL shortcut so
 *   players don't need to open the menu just to swap a firmware chip.
 * - **Right-click with any other item** → default block interaction
 *   (same as workstations).
 */
class McuBoardBlock(props: Properties) : DirectionalBlock(props) {

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        McuBoardBlockEntity(pos, state)

    override fun <T : BlockEntity> getTicker(
        level: Level, state: BlockState, type: BlockEntityType<T>,
    ): BlockEntityTicker<T>? = scevTicker(level, type, ScevRegistry.MCU_BOARD_BE.get())

    override fun useWithoutItem(
        state: BlockState, level: Level, pos: BlockPos, player: Player, hit: BlockHitResult,
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val mcu = level.getBlockEntity(pos) as? McuBoardBlockEntity ?: return InteractionResult.PASS
        val sp = player as? ServerPlayer ?: return InteractionResult.PASS

        // Single behavior: open the install menu. Power lives on the
        // in-menu button, identical to the workstation family. Keeps the
        // interaction model consistent across every SCEv machine.
        sp.openScevMenu("container.scev.mcu_board", pos) { id, inv ->
            McuBoardMenu(id, inv, mcu)
        }
        return InteractionResult.CONSUME
    }

    /**
     * Right-click while holding a SoC or Flash item — auto-install into
     * the matching slot. If the slot is already occupied, hot-swap: the
     * existing item returns to the player's hand, the new one goes into
     * the slot.
     *
     * This is a quality-of-life shortcut so players don't have to open
     * the menu just to pop a firmware chip in. Covers the common case;
     * advanced interactions (moving items between arbitrary slots)
     * still require the menu.
     */
    override fun useItemOn(
        stack: ItemStack, state: BlockState, level: Level, pos: BlockPos,
        player: Player, hand: InteractionHand, hit: BlockHitResult,
    ): ItemInteractionResult {
        if (level.isClientSide) return ItemInteractionResult.SUCCESS
        val mcu = level.getBlockEntity(pos) as? McuBoardBlockEntity
            ?: return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION

        val slot = when (stack.item) {
            is SocItem -> McuBoardBlockEntity.SLOT_SOC
            is FlashItem -> McuBoardBlockEntity.SLOT_FLASH
            else -> return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
        }

        // Pull the existing slot content back into the hand (or float if the
        // hand is the swap source — hand always holds the single item stack).
        val existing = mcu.getItem(slot)
        val incoming = stack.copyWithCount(1)

        // Swap: put incoming into the slot, existing goes into the player's
        // hand. If the player's hand had >1 items, decrement by 1; we only
        // flash one chip at a time per interaction.
        mcu.setItem(slot, incoming)
        stack.shrink(1)
        if (!existing.isEmpty) {
            if (stack.isEmpty) {
                player.setItemInHand(hand, existing)
            } else {
                // Hand still has more SoC/Flash items; stash the removed one
                // in the player's inventory (or drop it at their feet).
                if (!player.inventory.add(existing)) {
                    player.drop(existing, false)
                }
            }
        }
        mcu.setChanged()
        return ItemInteractionResult.CONSUME
    }

    /**
     * Break-block: drop both slot contents as items before the block
     * entity is removed. Without this, installed chips would vanish —
     * unlike `ComputerCaseBlock`-equivalents that persist their
     * components via the motherboard's NBT, MCU's slots live on the BE
     * itself and need the drop.
     */
    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, moved: Boolean) {
        if (!state.`is`(newState.block)) {
            (level.getBlockEntity(pos) as? McuBoardBlockEntity)?.let { mcu ->
                Containers.dropContents(level, pos, mcu)
                level.updateNeighbourForOutputSignal(pos, this)
            }
        }
        super.onRemove(state, level, pos, newState, moved)
    }
}
