/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.blocks;

import com.mojang.serialization.MapCodec;
import lekkit.scev.blockentity.FlashProgrammerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Flash programmer block — right-click to open a 2-slot GUI where the
 * player drops a source flash chip + a blank target, presses Write, and
 * the target comes out with the source's resolved firmware bytes stamped
 * into its {@code FIRMWARE_BYTES} data component.
 *
 * <p>The block itself is a plain cube — no rotation, no directional
 * rendering. All the interesting state lives on the BE + menu.
 */
public class FlashProgrammerBlock extends BaseEntityBlock {
    public FlashProgrammerBlock(Properties props) {
        super(props);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return simpleCodec(p -> { throw new UnsupportedOperationException("codec-less block"); });
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FlashProgrammerBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof FlashProgrammerBlockEntity prog) || !(player instanceof ServerPlayer sp)) {
            return InteractionResult.PASS;
        }
        sp.openMenu(new MenuProvider() {
            @Override
            public Component getDisplayName() {
                return Component.translatable("container.scev.flash_programmer");
            }

            @Override
            public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) {
                return new lekkit.scev.menu.FlashProgrammerMenu(id, inv, prog);
            }
        }, buf -> buf.writeBlockPos(pos));
        return InteractionResult.CONSUME;
    }

    /**
     * Break-block: drop both slot contents before the BE is removed.
     * Standard container-block hygiene — players shouldn't lose chips
     * mid-flash to a careless pickaxe swing.
     */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.is(newState.getBlock())) {
            if (level.getBlockEntity(pos) instanceof FlashProgrammerBlockEntity prog) {
                Containers.dropContents(level, pos, prog);
                level.updateNeighbourForOutputSignal(pos, this);
            }
        }
        super.onRemove(state, level, pos, newState, moved);
    }
}
