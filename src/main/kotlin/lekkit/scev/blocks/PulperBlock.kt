/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.blocks

import lekkit.scev.blockentity.PulperBlockEntity
import lekkit.scev.main.ScevRegistry
import lekkit.scev.menu.PulperMenu
import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionHand
import net.minecraft.world.ItemInteractionResult
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.BucketItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.neoforged.neoforge.fluids.FluidUtil

/** Pulper — first machine in the paper-production chain. Right-
 *  clicking with a water bucket pours into the BE's water tank
 *  (handled via NeoForge's [FluidUtil.interactWithFluidHandler]);
 *  any other right-click opens the GUI. */
class PulperBlock(props: Properties) :
    ProcessingMachineBlockBase<PulperBlockEntity>(props, "container.scev.pulper") {

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        PulperBlockEntity(pos, state)

    override fun beType(): BlockEntityType<PulperBlockEntity> = ScevRegistry.PULPER_BE.get()

    override fun openMenu(id: Int, inv: Inventory, be: PulperBlockEntity): AbstractContainerMenu =
        PulperMenu(id, inv, be)

    /** Bucket-on-pulper: drain the bucket into the tank instead of
     *  opening the GUI. Falls through to the base block's open-menu
     *  path for any other held item. */
    override fun useItemOn(
        stack: ItemStack, state: BlockState, level: Level, pos: BlockPos,
        player: Player, hand: InteractionHand, hit: BlockHitResult,
    ): ItemInteractionResult {
        if (stack.item is BucketItem && !level.isClientSide) {
            val didFill = FluidUtil.interactWithFluidHandler(player, hand, level, pos, hit.direction)
            if (didFill) return ItemInteractionResult.sidedSuccess(level.isClientSide)
        }
        return super.useItemOn(stack, state, level, pos, player, hand, hit)
    }
}
