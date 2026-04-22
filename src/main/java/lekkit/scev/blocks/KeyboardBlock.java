/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.blocks;

import java.util.UUID;
import lekkit.scev.blockentity.ComputerCaseBlockEntity;
import lekkit.scev.blockentity.KeyboardBlockEntity;
import lekkit.scev.main.ScevRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
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

    /**
     * Right-click the keyboard → open the bound machine's framebuffer view.
     * Relies on the peripheral-bus controller having stamped this keyboard
     * with the computer's UUID + position during its last scan.
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof KeyboardBlockEntity kb) || !(player instanceof ServerPlayer sp)) {
            return InteractionResult.PASS;
        }

        UUID boundUuid = kb.boundMachineUuid();
        BlockPos boundPos = kb.boundMachinePos();
        if (boundUuid == null || boundPos == null) {
            // Unbound keyboard — no computer adjacent and no cable route.
            // Tell the player rather than failing silently.
            sp.displayClientMessage(
                    Component.translatable("text.scev.keyboard.unbound")
                            .withStyle(ChatFormatting.YELLOW), true);
            return InteractionResult.CONSUME;
        }

        // Resolve the actual ComputerCaseBlockEntity at the recorded
        // position. If the computer was broken between scan and click, the
        // lookup returns null and we fall through with a friendly message.
        final BlockPos computerPos = boundPos;
        BlockEntity target = level.getBlockEntity(computerPos);
        if (!(target instanceof ComputerCaseBlockEntity cc)) {
            sp.displayClientMessage(
                    Component.translatable("text.scev.keyboard.stale")
                            .withStyle(ChatFormatting.YELLOW), true);
            return InteractionResult.CONSUME;
        }

        sp.openMenu(new MenuProvider() {
            @Override
            public Component getDisplayName() {
                return Component.translatable("container.scev.machine");
            }

            @Override
            public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) {
                return new lekkit.scev.menu.MachineMenu(id, inv, cc.getMachineUUID(), cc);
            }
        }, buf -> buf.writeBlockPos(computerPos));
        return InteractionResult.CONSUME;
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
