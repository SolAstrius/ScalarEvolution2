/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.blocks;

import com.mojang.serialization.MapCodec;
import lekkit.scev.blockentity.CableBlockEntity;
import lekkit.scev.blockentity.ComputerCaseBlockEntity;
import lekkit.scev.blockentity.McuBoardBlockEntity;
import lekkit.scev.bus.PeripheralBusElement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Cable block — passive conduit on the peripheral bus.
 *
 * <p>Carries one {@link BooleanProperty} per face indicating whether the
 * neighbour on that side is connectable (another bus element or a
 * computer block). The blockstate-driven multipart model then draws an
 * arm only on the connected sides, so the cable visibly terminates at
 * keyboards / computers / other cables rather than rendering six stubs.
 *
 * <p>Connectable = any {@link PeripheralBusElement} (other cable,
 * keyboard, VT100, CRT, programmer) OR a {@link ComputerCaseBlockEntity}
 * / {@link McuBoardBlockEntity} (which are bus roots, not bus elements,
 * but we want cables to physically terminate at them).
 */
public class CableBlock extends BaseEntityBlock {
    public static final BooleanProperty UP    = BlockStateProperties.UP;
    public static final BooleanProperty DOWN  = BlockStateProperties.DOWN;
    public static final BooleanProperty NORTH = BlockStateProperties.NORTH;
    public static final BooleanProperty SOUTH = BlockStateProperties.SOUTH;
    public static final BooleanProperty EAST  = BlockStateProperties.EAST;
    public static final BooleanProperty WEST  = BlockStateProperties.WEST;

    /**
     * Precomputed VoxelShape per connection bitmask. Index is a 6-bit mask
     * ordered by {@link Direction#ordinal()} (DOWN=0, UP=1, NORTH=2, SOUTH=3,
     * WEST=4, EAST=5). Each entry is the union of the 4×4×4 core cuboid plus
     * one 4×4×6 arm per connected face — matches the multipart model so the
     * selection wireframe hugs the visible geometry instead of the full cube.
     * Approach follows OC2r's {@code BusCableBlock.makeShapes} / OC1's
     * {@code Cable.cachedBounds}: union AABBs per bit, cache 64 combinations.
     */
    private static final VoxelShape[] SHAPES = buildShapes();

    private static VoxelShape[] buildShapes() {
        VoxelShape[] arms = {
            Block.box(6, 0,  6, 10, 6,  10),
            Block.box(6, 10, 6, 10, 16, 10),
            Block.box(6, 6,  0, 10, 10, 6),
            Block.box(6, 6, 10, 10, 10, 16),
            Block.box(0, 6,  6, 6,  10, 10),
            Block.box(10, 6, 6, 16, 10, 10),
        };
        VoxelShape[] out = new VoxelShape[64];
        VoxelShape center = Block.box(6, 6, 6, 10, 10, 10);
        for (int mask = 0; mask < 64; mask++) {
            VoxelShape shape = center;
            for (int i = 0; i < 6; i++) {
                if ((mask & (1 << i)) != 0) shape = Shapes.or(shape, arms[i]);
            }
            out[mask] = shape;
        }
        return out;
    }

    private static int shapeIndex(BlockState state) {
        int mask = 0;
        if (state.getValue(DOWN))  mask |= 1 << Direction.DOWN.ordinal();
        if (state.getValue(UP))    mask |= 1 << Direction.UP.ordinal();
        if (state.getValue(NORTH)) mask |= 1 << Direction.NORTH.ordinal();
        if (state.getValue(SOUTH)) mask |= 1 << Direction.SOUTH.ordinal();
        if (state.getValue(WEST))  mask |= 1 << Direction.WEST.ordinal();
        if (state.getValue(EAST))  mask |= 1 << Direction.EAST.ordinal();
        return mask;
    }

    public CableBlock(Properties props) {
        super(props);
        registerDefaultState(getStateDefinition().any()
                .setValue(UP, false).setValue(DOWN, false)
                .setValue(NORTH, false).setValue(SOUTH, false)
                .setValue(EAST, false).setValue(WEST, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return simpleCodec(p -> { throw new UnsupportedOperationException("codec-less block"); });
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(UP, DOWN, NORTH, SOUTH, EAST, WEST);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CableBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPES[shapeIndex(state)];
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPES[shapeIndex(state)];
    }

    /**
     * Return the {@link BooleanProperty} for a given direction. Keyed on
     * Direction.ordinal so the switch compiles to a tableswitch — tight
     * because this runs on every neighbour-update.
     */
    public static BooleanProperty propertyFor(Direction d) {
        return switch (d) {
            case DOWN  -> DOWN;
            case UP    -> UP;
            case NORTH -> NORTH;
            case SOUTH -> SOUTH;
            case WEST  -> WEST;
            case EAST  -> EAST;
        };
    }

    /**
     * Something on the other side of a cable face counts as "connectable"
     * when its block entity is a bus element (a peripheral, or another
     * cable) or a machine root. Non-BE blocks and empty air never count
     * — players can't accidentally wire cables into dirt.
     */
    private static boolean isConnectable(@Nullable BlockEntity be) {
        return be instanceof PeripheralBusElement
                || be instanceof ComputerCaseBlockEntity
                || be instanceof McuBoardBlockEntity;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        BlockState state = defaultBlockState();
        LevelAccessor level = ctx.getLevel();
        BlockPos pos = ctx.getClickedPos();
        for (Direction d : Direction.values()) {
            state = state.setValue(propertyFor(d), isConnectable(level.getBlockEntity(pos.relative(d))));
        }
        return state;
    }

    /**
     * A neighbour changed on face {@code d}. Re-check that one face and
     * return the updated state; the other five are unaffected, so we do
     * the minimal single-property edit rather than rescanning all sides.
     */
    @Override
    protected BlockState updateShape(BlockState state, Direction d, BlockState neighborState,
                                      LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        return state.setValue(propertyFor(d), isConnectable(level.getBlockEntity(neighborPos)));
    }
}
