/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.blocks

import com.mojang.serialization.MapCodec
import lekkit.scev.blockentity.FlashProgrammerBlockEntity
import lekkit.scev.menu.FlashProgrammerMenu
import lekkit.scev.menu.openScevMenu
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Containers
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult

/**
 * Flash programmer block — right-click to open a 2-slot GUI where the
 * player drops a source flash chip + a blank target, presses Write, and
 * the target comes out with the source's resolved firmware bytes
 * stamped into its `FIRMWARE_BYTES` data component.
 *
 * The block itself is a plain cube — no rotation, no directional
 * rendering. All the interesting state lives on the BE + menu.
 */
class FlashProgrammerBlock(props: Properties) : BaseEntityBlock(props) {

    override fun codec(): MapCodec<out BaseEntityBlock> =
        simpleCodec { _ -> throw UnsupportedOperationException("codec-less block") }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        FlashProgrammerBlockEntity(pos, state)

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun useWithoutItem(
        state: BlockState, level: Level, pos: BlockPos, player: Player, hit: BlockHitResult,
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val prog = level.getBlockEntity(pos) as? FlashProgrammerBlockEntity ?: return InteractionResult.PASS
        val sp = player as? ServerPlayer ?: return InteractionResult.PASS
        sp.openScevMenu("container.scev.flash_programmer", pos) { id, inv ->
            FlashProgrammerMenu(id, inv, prog)
        }
        return InteractionResult.CONSUME
    }

    /**
     * Break-block: drop both slot contents before the BE is removed.
     * Standard container-block hygiene — players shouldn't lose chips
     * mid-flash to a careless pickaxe swing.
     */
    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, moved: Boolean) {
        if (!state.`is`(newState.block)) {
            (level.getBlockEntity(pos) as? FlashProgrammerBlockEntity)?.let { prog ->
                Containers.dropContents(level, pos, prog)
                level.updateNeighbourForOutputSignal(pos, this)
            }
        }
        super.onRemove(state, level, pos, newState, moved)
    }
}
