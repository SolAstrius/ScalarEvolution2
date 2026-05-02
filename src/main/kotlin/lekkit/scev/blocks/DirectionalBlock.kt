/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.blocks

import com.mojang.serialization.MapCodec
import lekkit.scev.blockentity.ScevBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DirectionProperty

/**
 * Base for any block that has a HORIZONTAL_FACING state and a
 * BlockEntity.
 *
 * **Rendering:** we override [getRenderShape] to return
 * [RenderShape.MODEL]. [BaseEntityBlock.getRenderShape] defaults to
 * [RenderShape.INVISIBLE] — appropriate for blocks like chests and
 * beacons that render entirely via a `BlockEntityRenderer`, but wrong
 * for us: we want the normal JSON/OBJ block model to draw. Without this
 * override, every scev block appeared as invisible geometry in the
 * world despite the OBJ parsing and baking correctly. Locked down by
 * [lekkit.scev.test.BlockRenderShapeTest].
 */
abstract class DirectionalBlock protected constructor(props: Properties) : BaseEntityBlock(props) {

    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH))
    }

    override fun codec(): MapCodec<out BaseEntityBlock> =
        // Not needed for non-vanilla blocks that aren't data-driven; return a minimal codec.
        simpleCodec { _ -> throw UnsupportedOperationException("codec-less block") }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING)
    }

    override fun getRenderShape(state: BlockState): RenderShape =
        // See the class kdoc — BaseEntityBlock defaults to INVISIBLE, which
        // would hide our OBJ model. MODEL tells the chunk mesher to render
        // the block's JSON/OBJ model normally.
        RenderShape.MODEL

    override fun getStateForPlacement(ctx: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(FACING, ctx.horizontalDirection.opposite)

    override fun rotate(state: BlockState, rot: Rotation): BlockState =
        state.setValue(FACING, rot.rotate(state.getValue(FACING)))

    override fun mirror(state: BlockState, mirror: Mirror): BlockState =
        state.rotate(mirror.getRotation(state.getValue(FACING)))

    /* --------------------------- Redstone --------------------------- */

    /**
     * Emit a signal whenever the block entity is a running computer-case
     * with an installed GPIO card. `ComputerCaseBlockEntity.serverTick`
     * polls the GPIO and pushes state into the BE's redstoneSignals
     * bitmap.
     */
    override fun isSignalSource(state: BlockState): Boolean = true

    override fun getSignal(state: BlockState, level: BlockGetter, pos: BlockPos, dir: Direction): Int {
        // Minecraft's convention: `dir` is "direction from the querier to the
        // emitter" (i.e. the side of the emitter facing AWAY from the querier).
        // See SignalGetter#hasNeighborSignal: for a querier at P, checking
        // its NORTH neighbour, MC calls getSignal(P.north(), NORTH).
        //
        // We track emitted signals by "which of OUR sides is outputting". The
        // side that reaches the querier is the opposite of `dir` — the face
        // of us pointing TOWARDS the querier is `dir.getOpposite()`.
        val be = level.getBlockEntity(pos)
        return if (be is ScevBlockEntity) be.getOutRedstoneSignal(dir.opposite) else 0
    }

    override fun getDirectSignal(state: BlockState, level: BlockGetter, pos: BlockPos, dir: Direction): Int =
        getSignal(state, level, pos, dir)

    override fun neighborChanged(
        state: BlockState, level: Level, pos: BlockPos, neighbour: Block,
        fromPos: BlockPos, isMoving: Boolean,
    ) {
        if (level.isClientSide) return
        val be = level.getBlockEntity(pos) as? ScevBlockEntity ?: return
        // Sample redstone power on all 6 sides, pack one bit per direction.
        var signals = 0
        for (d in Direction.values()) {
            if (level.getSignal(pos.relative(d), d) > 0) {
                signals = signals or (1 shl d.ordinal)
            }
        }
        be.onRedstoneInput(signals)
        // Peripheral-bus hook: let machine BEs know a neighbour changed so
        // they can rescan their bus. Cheap no-op on non-machine BEs.
        be.onNeighborBlockChanged(fromPos)
    }

    companion object {
        @JvmField
        val FACING: DirectionProperty = BlockStateProperties.HORIZONTAL_FACING

        /**
         * Ticker that forwards to [ScevBlockEntity.serverTick]. Subclasses
         * that own a machine (computer cases) use this so GPIO state is
         * polled each tick and pushed back out as redstone.
         */
        @JvmStatic
        protected fun <T : BlockEntity> scevTicker(
            level: Level, type: BlockEntityType<T>, expected: BlockEntityType<out ScevBlockEntity>,
        ): BlockEntityTicker<T>? {
            if (level.isClientSide) return null
            if (type != expected) return null
            return BlockEntityTicker { lvl, pos, st, be ->
                if (be is ScevBlockEntity) be.serverTick(lvl, pos, st)
            }
        }
    }
}
