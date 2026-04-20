/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.blocks;

import lekkit.scev.blockentity.KeyboardBlockEntity;
import lekkit.scev.main.ScevRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Flat pizza-box keyboard block (optionally with mouse side too).
 */
public class KeyboardBlock extends DirectionalBlock {
    private static final VoxelShape NS = Shapes.box(0.0, 0.0, 0.0, 1.0, 0.0625, 0.5625);
    private static final VoxelShape EW = Shapes.box(0.0, 0.0, 0.0, 0.5625, 0.0625, 1.0);
    private static final VoxelShape SN = Shapes.box(0.0, 0.0, 0.4375, 1.0, 0.0625, 1.0);
    private static final VoxelShape WE = Shapes.box(0.4375, 0.0, 0.0, 1.0, 0.0625, 1.0);

    private final boolean hasMouse;

    public KeyboardBlock(Properties props, boolean hasMouse) {
        super(props);
        this.hasMouse = hasMouse;
    }

    public boolean hasMouse() {
        return hasMouse;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new KeyboardBlockEntity(
                hasMouse ? ScevRegistry.KEYBOARD_MOUSE_BE.get() : ScevRegistry.KEYBOARD_BE.get(),
                pos, state, hasMouse);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        Direction d = state.getValue(FACING);
        return switch (d) {
            case NORTH -> NS;
            case EAST -> EW;
            case SOUTH -> SN;
            case WEST -> WE;
            default -> NS;
        };
    }
}
