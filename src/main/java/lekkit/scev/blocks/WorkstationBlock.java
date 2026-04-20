/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.blocks;

import lekkit.scev.blockentity.ComputerCaseBlockEntity;
import lekkit.scev.blockentity.PowermarkBlockEntity;
import lekkit.scev.blockentity.TinkerpadBlockEntity;
import lekkit.scev.blockentity.WorkstationBlockEntity;
import lekkit.scev.main.ScevRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

public class WorkstationBlock extends DirectionalBlock {
    public WorkstationBlock(Properties props) {
        super(props);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        // Which BE to build is decided by the Block's registry name — caller supplies the
        // concrete DeferredBlock (see ScevRegistry). We choose at creation time by matching
        // the owning block instance.
        var resource = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(this);
        if (resource == null) {
            return new WorkstationBlockEntity(pos, state);
        }
        return switch (resource.getPath()) {
            case "powermark" -> new PowermarkBlockEntity(pos, state);
            case "tinkerpad" -> new TinkerpadBlockEntity(pos, state);
            default -> new WorkstationBlockEntity(pos, state);
        };
    }

    /**
     * Return a ticker matching the BE type the concrete block spawns.
     * Each workstation variant (workstation / powermark / tinkerpad) uses the
     * same {@link ComputerCaseBlockEntity#serverTick} polling loop — we just
     * need to get the BE type right so NeoForge dispatches ticks correctly.
     */
    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        var resource = BuiltInRegistries.BLOCK.getKey(this);
        if (resource == null) return null;
        BlockEntityType<?> expected = switch (resource.getPath()) {
            case "powermark" -> ScevRegistry.POWERMARK_BE.get();
            case "tinkerpad" -> ScevRegistry.TINKERPAD_BE.get();
            default -> ScevRegistry.WORKSTATION_BE.get();
        };
        if (type != expected) return null;
        return (lvl, pos, st, be) -> {
            if (be instanceof ComputerCaseBlockEntity cc) cc.serverTick(lvl, pos, st);
        };
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof ComputerCaseBlockEntity cc) || !(player instanceof ServerPlayer sp)) {
            return InteractionResult.PASS;
        }

        // Shift-right-click on a powered case: open the framebuffer view
        // (MachineMenu / MachineScreen). Otherwise open the component edit
        // menu (ComputerCaseMenu).
        boolean wantsView = sp.isShiftKeyDown() && cc.isPowered();
        if (wantsView) {
            sp.openMenu(new MenuProvider() {
                @Override
                public net.minecraft.network.chat.Component getDisplayName() {
                    return net.minecraft.network.chat.Component.translatable("container.scev.machine");
                }

                @Override
                public net.minecraft.world.inventory.AbstractContainerMenu createMenu(
                        int id, net.minecraft.world.entity.player.Inventory inv,
                        net.minecraft.world.entity.player.Player p) {
                    return new lekkit.scev.menu.MachineMenu(id, inv, cc.getMachineUUID(), cc);
                }
            }, buf -> buf.writeBlockPos(pos));
        } else {
            sp.openMenu(new MenuProvider() {
                @Override
                public net.minecraft.network.chat.Component getDisplayName() {
                    return net.minecraft.network.chat.Component.translatable("container.scev.computer_case");
                }

                @Override
                public net.minecraft.world.inventory.AbstractContainerMenu createMenu(
                        int id, net.minecraft.world.entity.player.Inventory inv,
                        net.minecraft.world.entity.player.Player p) {
                    return new lekkit.scev.menu.ComputerCaseMenu(id, inv, cc);
                }
            }, buf -> buf.writeBlockPos(pos));
        }
        return InteractionResult.CONSUME;
    }
}
