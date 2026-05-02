/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.blockentity

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState

/**
 * Base block entity with helpers for NBT sync and redstone signal state.
 */
abstract class ScevBlockEntity(type: BlockEntityType<*>, pos: BlockPos, state: BlockState) :
    BlockEntity(type, pos, state) {

    /**
     * Packed 6-bit map: bit N = 1 means a redstone signal is being emitted
     * out of [Direction] ordinal N. Drives `getSignal` on the block side.
     */
    @JvmField protected var redstoneSignals: Int = 0

    /* ----- NBT — subclasses override these to add custom data. ----- */

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        if (tag.contains("redstoneSignals")) redstoneSignals = tag.getInt("redstoneSignals")
    }

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        if (redstoneSignals != 0) tag.putInt("redstoneSignals", redstoneSignals)
    }

    /* ----- Client sync. ----- */

    override fun getUpdatePacket(): Packet<ClientGamePacketListener>? =
        ClientboundBlockEntityDataPacket.create(this)

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag =
        saveWithoutMetadata(registries)

    /* ----- Redstone. ----- */

    @get:JvmName("getOutRedstoneSignals")
    @set:JvmName("setOutRedstoneSignals")
    var outRedstoneSignals: Int
        get() = redstoneSignals
        set(signals) {
            if (redstoneSignals != signals) {
                redstoneSignals = signals
                setChanged()
                if (level != null && !level!!.isClientSide) {
                    level!!.updateNeighborsAt(blockPos, blockState.block)
                }
            }
        }

    /** Read the current outgoing signal for a specific direction (0 or 15). */
    fun getOutRedstoneSignal(dir: Direction): Int =
        if (((redstoneSignals shr dir.ordinal) and 1) != 0) 15 else 0

    /**
     * Called from the block's neighbour-update path with a packed 6-bit map
     * of incoming signals (one bit per Direction ordinal). Subclasses override
     * to forward this into their [lekkit.scev.server.MachineState].
     */
    open fun onRedstoneInput(signals: Int) {} // override in subclass

    /**
     * Called from the block's `neighborChanged` path so machine BEs can
     * invalidate their peripheral-bus scan. Default no-op — blocks that own
     * a bus controller override this.
     */
    open fun onNeighborBlockChanged(fromPos: BlockPos) {} // override in subclass

    /**
     * Server-side tick hook. Subclasses with running machines forward GPIO
     * state back out to redstone. BEs register this as their ticker via
     * `EntityBlock.getTicker`.
     */
    open fun serverTick(level: Level, pos: BlockPos, state: BlockState) {} // override in subclass
}
