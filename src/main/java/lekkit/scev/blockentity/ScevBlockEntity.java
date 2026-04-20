/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.blockentity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Base block entity with helpers for NBT sync and redstone signal state.
 */
public abstract class ScevBlockEntity extends BlockEntity {
    protected int redstoneSignals = 0;

    public ScevBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    /* ------------------------------------------------------------------ */
    /* NBT — subclasses override these to add custom data.                  */
    /* ------------------------------------------------------------------ */

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("redstoneSignals")) {
            redstoneSignals = tag.getInt("redstoneSignals");
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (redstoneSignals != 0) {
            tag.putInt("redstoneSignals", redstoneSignals);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Client sync.                                                         */
    /* ------------------------------------------------------------------ */

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    /* ------------------------------------------------------------------ */
    /* Redstone.                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * Packed 6-bit map: bit N = 1 means a redstone signal is being emitted out of
     * {@link Direction} ordinal N. Drives {@code getSignal} on the block side.
     */
    public int getOutRedstoneSignals() {
        return redstoneSignals;
    }

    /**
     * Update this block's outgoing redstone map and trigger a neighbour refresh
     * so adjacent wires/comparators see the new signal immediately.
     */
    public void setOutRedstoneSignals(int signals) {
        if (redstoneSignals != signals) {
            redstoneSignals = signals;
            setChanged();
            if (level != null && !level.isClientSide) {
                level.updateNeighborsAt(worldPosition, getBlockState().getBlock());
            }
        }
    }

    /** Read the current outgoing signal for a specific direction (0 or 15). */
    public int getOutRedstoneSignal(Direction dir) {
        return ((redstoneSignals >> dir.ordinal()) & 1) != 0 ? 15 : 0;
    }

    /**
     * Called from the block's neighbour-update path with a packed 6-bit map of
     * incoming signals (one bit per Direction ordinal). Subclasses override to
     * forward this into their {@link lekkit.scev.server.MachineState}.
     */
    public void onRedstoneInput(int signals) {
        // Override in subclass
    }

    /**
     * Server-side tick hook. Subclasses with running machines forward GPIO
     * state back out to redstone. Block entities register this as their ticker
     * via {@link net.minecraft.world.level.block.EntityBlock#getTicker}.
     */
    public void serverTick(Level level, BlockPos pos, BlockState state) {
        // Override in subclass.
    }
}
