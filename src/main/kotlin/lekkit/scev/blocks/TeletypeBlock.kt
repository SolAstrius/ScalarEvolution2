/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.blocks

import com.mojang.serialization.MapCodec
import lekkit.scev.blockentity.TeletypeBlockEntity
import lekkit.scev.main.ScevRegistry
import lekkit.scev.menu.TeletypeMenu
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
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult

/** ASR-33-style teletype. Right-click to load paper / ribbon and
 *  print test pages; future PR wires it up to consume bytes from
 *  a serial-bus peripheral. */
class TeletypeBlock(props: Properties) : BaseEntityBlock(props) {

    override fun codec(): MapCodec<out BaseEntityBlock> =
        simpleCodec { _ -> throw UnsupportedOperationException("codec-less block") }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        TeletypeBlockEntity(pos, state)

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    /** Drives the BE's per-tick drain of buffered serial bytes onto
     *  the print queue. Without this the byte queue from
     *  [KernelConsoleSink] would just fill up and never print. */
    @Suppress("UNCHECKED_CAST")
    override fun <T : BlockEntity> getTicker(
        level: Level, state: BlockState, type: BlockEntityType<T>,
    ): BlockEntityTicker<T>? {
        if (level.isClientSide) return null
        if (type != ScevRegistry.TELETYPE_BE.get()) return null
        val ticker: BlockEntityTicker<TeletypeBlockEntity> =
            BlockEntityTicker { lvl, pos, st, be -> be.serverTick(lvl, pos, st) }
        return ticker as BlockEntityTicker<T>
    }

    override fun useWithoutItem(
        state: BlockState, level: Level, pos: BlockPos, player: Player, hit: BlockHitResult,
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val sp = player as? ServerPlayer ?: return InteractionResult.PASS
        val be = level.getBlockEntity(pos) as? TeletypeBlockEntity ?: return InteractionResult.PASS
        sp.openScevMenu("container.scev.teletype", pos) { id, inv -> TeletypeMenu(id, inv, be) }
        return InteractionResult.CONSUME
    }

    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, moved: Boolean) {
        if (!state.`is`(newState.block)) {
            (level.getBlockEntity(pos) as? TeletypeBlockEntity)?.let { be ->
                Containers.dropContents(level, pos, be)
                level.updateNeighbourForOutputSignal(pos, this)
            }
        }
        super.onRemove(state, level, pos, newState, moved)
    }
}
