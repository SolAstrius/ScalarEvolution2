/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.blocks;

import com.mojang.serialization.MapCodec;
import lekkit.scev.blockentity.ComputerCaseBlockEntity;
import lekkit.scev.blockentity.ScevBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import org.jetbrains.annotations.Nullable;

/**
 * Base for any block that has a HORIZONTAL_FACING state and a BlockEntity.
 *
 * <p><b>Rendering:</b> we override {@link #getRenderShape} to return
 * {@link RenderShape#MODEL}. {@link BaseEntityBlock#getRenderShape} defaults
 * to {@link RenderShape#INVISIBLE} — appropriate for blocks like chests and
 * beacons that render entirely via a {@code BlockEntityRenderer}, but wrong
 * for us: we want the normal JSON/OBJ block model to draw. Without this
 * override, every scev block appeared as invisible geometry in the world
 * despite the OBJ parsing and baking correctly. Locked down by
 * {@link lekkit.scev.test.BlockRenderShapeTest}.
 */
public abstract class DirectionalBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    protected DirectionalBlock(Properties props) {
        super(props);
        registerDefaultState(getStateDefinition().any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        // Not needed for non-vanilla blocks that aren't data-driven; return null-ish minimal codec.
        return simpleCodec(p -> { throw new UnsupportedOperationException("codec-less block"); });
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        // See the class javadoc — BaseEntityBlock defaults to INVISIBLE, which
        // would hide our OBJ model. MODEL tells the chunk mesher to render the
        // block's JSON/OBJ model normally.
        return RenderShape.MODEL;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rot) {
        return state.setValue(FACING, rot.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    /* --------------------------- Redstone --------------------------- */

    /**
     * Emit a signal whenever the block entity is a running computer-case with
     * an installed GPIO card. {@link ComputerCaseBlockEntity#serverTick}
     * polls the GPIO and pushes state into the BE's redstoneSignals bitmap.
     */
    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction dir) {
        // Minecraft's convention: `dir` is "direction from the querier to the
        // emitter" (i.e. the side of the emitter facing AWAY from the querier).
        // See SignalGetter#hasNeighborSignal: for a querier at P, checking
        // its NORTH neighbour, MC calls getSignal(P.north(), NORTH).
        //
        // We track emitted signals by "which of OUR sides is outputting". The
        // side that reaches the querier is the opposite of `dir` — the face
        // of us pointing TOWARDS the querier is `dir.getOpposite()`.
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof ScevBlockEntity sbe) {
            return sbe.getOutRedstoneSignal(dir.getOpposite());
        }
        return 0;
    }

    @Override
    protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction dir) {
        return getSignal(state, level, pos, dir);
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbour,
                                   BlockPos fromPos, boolean isMoving) {
        if (level.isClientSide) return;
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof ScevBlockEntity sbe)) return;
        // Sample redstone power on all 6 sides, pack one bit per direction.
        int signals = 0;
        for (Direction d : Direction.values()) {
            if (level.getSignal(pos.relative(d), d) > 0) {
                signals |= 1 << d.ordinal();
            }
        }
        sbe.onRedstoneInput(signals);
    }

    /* --------------------------- Ticking --------------------------- */

    /**
     * Ticker that forwards to {@link ScevBlockEntity#serverTick}. Subclasses
     * that own a machine (computer cases) use this so GPIO state is polled
     * each tick and pushed back out as redstone.
     */
    protected static <T extends BlockEntity> @Nullable BlockEntityTicker<T> scevTicker(
            Level level, BlockEntityType<T> type, BlockEntityType<? extends ScevBlockEntity> expected) {
        if (level.isClientSide) return null;
        if (type != expected) return null;
        return (lvl, pos, st, be) -> {
            if (be instanceof ScevBlockEntity sbe) sbe.serverTick(lvl, pos, st);
        };
    }
}
