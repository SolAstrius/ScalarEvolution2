/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.blocks

import com.mojang.serialization.MapCodec
import lekkit.scev.blockentity.CableBlockEntity
import lekkit.scev.blockentity.ComputerCaseBlockEntity
import lekkit.scev.blockentity.McuBoardBlockEntity
import lekkit.scev.bus.PeripheralBusElement
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * Cable block — passive conduit on the peripheral bus.
 *
 * Carries one [BooleanProperty] per face indicating whether the
 * neighbour on that side is connectable (another bus element or a
 * computer block). The blockstate-driven multipart model then draws an
 * arm only on the connected sides, so the cable visibly terminates at
 * keyboards / computers / other cables rather than rendering six stubs.
 *
 * Connectable = any [PeripheralBusElement] (other cable, keyboard,
 * VT100, CRT, programmer) OR a [ComputerCaseBlockEntity] /
 * [McuBoardBlockEntity] (which are bus roots, not bus elements, but we
 * want cables to physically terminate at them).
 */
class CableBlock(props: Properties) : BaseEntityBlock(props) {

    init {
        registerDefaultState(stateDefinition.any()
            .setValue(UP, false).setValue(DOWN, false)
            .setValue(NORTH, false).setValue(SOUTH, false)
            .setValue(EAST, false).setValue(WEST, false))
    }

    override fun codec(): MapCodec<out BaseEntityBlock> =
        simpleCodec { _ -> throw UnsupportedOperationException("codec-less block") }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(UP, DOWN, NORTH, SOUTH, EAST, WEST)
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        CableBlockEntity(pos, state)

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, ctx: CollisionContext): VoxelShape =
        SHAPES[shapeIndex(state)]

    override fun getCollisionShape(state: BlockState, level: BlockGetter, pos: BlockPos, ctx: CollisionContext): VoxelShape =
        SHAPES[shapeIndex(state)]

    override fun getStateForPlacement(ctx: BlockPlaceContext): BlockState {
        var state = defaultBlockState()
        val level = ctx.level
        val pos = ctx.clickedPos
        for (d in Direction.values()) {
            state = state.setValue(propertyFor(d), isConnectable(level.getBlockEntity(pos.relative(d))))
        }
        return state
    }

    /**
     * A neighbour changed on face [d]. Re-check that one face and return
     * the updated state; the other five are unaffected, so we do the
     * minimal single-property edit rather than rescanning all sides.
     */
    override fun updateShape(
        state: BlockState, d: Direction, neighborState: BlockState,
        level: LevelAccessor, pos: BlockPos, neighborPos: BlockPos,
    ): BlockState = state.setValue(propertyFor(d), isConnectable(level.getBlockEntity(neighborPos)))

    companion object {
        @JvmField val UP:    BooleanProperty = BlockStateProperties.UP
        @JvmField val DOWN:  BooleanProperty = BlockStateProperties.DOWN
        @JvmField val NORTH: BooleanProperty = BlockStateProperties.NORTH
        @JvmField val SOUTH: BooleanProperty = BlockStateProperties.SOUTH
        @JvmField val EAST:  BooleanProperty = BlockStateProperties.EAST
        @JvmField val WEST:  BooleanProperty = BlockStateProperties.WEST

        /**
         * Precomputed VoxelShape per connection bitmask. Index is a 6-bit
         * mask ordered by [Direction.ordinal] (DOWN=0, UP=1, NORTH=2,
         * SOUTH=3, WEST=4, EAST=5). Each entry is the union of the 4×4×4
         * core cuboid plus one 4×4×6 arm per connected face — matches the
         * multipart model so the selection wireframe hugs the visible
         * geometry instead of the full cube. Approach follows OC2r's
         * `BusCableBlock.makeShapes` / OC1's `Cable.cachedBounds`.
         */
        private val SHAPES: Array<VoxelShape> = buildShapes()

        private fun buildShapes(): Array<VoxelShape> {
            val arms = arrayOf(
                Block.box(6.0, 0.0,  6.0, 10.0, 6.0,  10.0),
                Block.box(6.0, 10.0, 6.0, 10.0, 16.0, 10.0),
                Block.box(6.0, 6.0,  0.0, 10.0, 10.0, 6.0),
                Block.box(6.0, 6.0, 10.0, 10.0, 10.0, 16.0),
                Block.box(0.0, 6.0,  6.0, 6.0,  10.0, 10.0),
                Block.box(10.0, 6.0, 6.0, 16.0, 10.0, 10.0),
            )
            val center = Block.box(6.0, 6.0, 6.0, 10.0, 10.0, 10.0)
            return Array(64) { mask ->
                var shape: VoxelShape = center
                for (i in 0 until 6) {
                    if ((mask and (1 shl i)) != 0) shape = Shapes.or(shape, arms[i])
                }
                shape
            }
        }

        private fun shapeIndex(state: BlockState): Int {
            var mask = 0
            if (state.getValue(DOWN))  mask = mask or (1 shl Direction.DOWN.ordinal)
            if (state.getValue(UP))    mask = mask or (1 shl Direction.UP.ordinal)
            if (state.getValue(NORTH)) mask = mask or (1 shl Direction.NORTH.ordinal)
            if (state.getValue(SOUTH)) mask = mask or (1 shl Direction.SOUTH.ordinal)
            if (state.getValue(WEST))  mask = mask or (1 shl Direction.WEST.ordinal)
            if (state.getValue(EAST))  mask = mask or (1 shl Direction.EAST.ordinal)
            return mask
        }

        /**
         * Return the [BooleanProperty] for a given direction. Keyed on
         * Direction.ordinal so the switch compiles to a tableswitch —
         * tight because this runs on every neighbour-update.
         */
        @JvmStatic
        fun propertyFor(d: Direction): BooleanProperty = when (d) {
            Direction.DOWN  -> DOWN
            Direction.UP    -> UP
            Direction.NORTH -> NORTH
            Direction.SOUTH -> SOUTH
            Direction.WEST  -> WEST
            Direction.EAST  -> EAST
        }

        /**
         * Something on the other side of a cable face counts as
         * "connectable" when its block entity is a bus element (a
         * peripheral, or another cable) or a machine root. Non-BE blocks
         * and empty air never count — players can't accidentally wire
         * cables into dirt.
         */
        private fun isConnectable(be: BlockEntity?): Boolean =
            be is PeripheralBusElement || be is ComputerCaseBlockEntity || be is McuBoardBlockEntity
    }
}
