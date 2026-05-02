/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.blocks

import com.mojang.serialization.MapCodec
import lekkit.scev.blockentity.ProcessingMachineBlockEntity
import lekkit.scev.menu.openScevMenu
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Containers
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult

/**
 * Generic base for every processing-machine block (Pulper, SheetFormer,
 * Dryer, Winder, InkMixer, RibbonImpregnator). Concrete subclasses pick
 * the BE class + menu factory + container lang key; everything else —
 * the cube model, the right-click menu open, the break-block drop, the
 * tick wiring — is identical.
 *
 * Each subclass:
 *  - Constructs a [BE] in [newBlockEntity]
 *  - Returns the right [BlockEntityType] from [beType] so the ticker
 *    only fires for matching BE instances
 *  - Builds its menu via [openMenu] (typically a one-liner forwarding
 *    to a [ProcessingMachineMenu]-derived constructor)
 */
abstract class ProcessingMachineBlockBase<BE : ProcessingMachineBlockEntity>(
    props: Properties,
    private val containerLangKey: String,
) : BaseEntityBlock(props) {

    override fun codec(): MapCodec<out BaseEntityBlock> =
        simpleCodec { _ -> throw UnsupportedOperationException("codec-less block") }

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    /** BE constructor — subclass returns `XxxBlockEntity(pos, state)`. */
    abstract override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity

    /** The expected [BlockEntityType] for this block. Used by [getTicker]
     *  to filter out tickers for foreign BE types (which would otherwise
     *  fire for every BE in the world). */
    protected abstract fun beType(): BlockEntityType<BE>

    /** Build the menu for the player who right-clicked this block.
     *  Subclasses return their concrete `XxxMenu(id, inv, be)`. */
    protected abstract fun openMenu(id: Int, inv: Inventory, be: BE): AbstractContainerMenu

    @Suppress("UNCHECKED_CAST")
    override fun <T : BlockEntity> getTicker(
        level: Level, state: BlockState, type: BlockEntityType<T>,
    ): BlockEntityTicker<T>? {
        if (level.isClientSide) return null
        if (type != beType()) return null
        val ticker: BlockEntityTicker<BE> = BlockEntityTicker { lvl, pos, st, be ->
            be.serverTick(lvl, pos, st)
        }
        return ticker as BlockEntityTicker<T>
    }

    @Suppress("UNCHECKED_CAST")
    override fun useWithoutItem(
        state: BlockState, level: Level, pos: BlockPos, player: Player, hit: BlockHitResult,
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val sp = player as? ServerPlayer ?: return InteractionResult.PASS
        val be = level.getBlockEntity(pos) as? ProcessingMachineBlockEntity
            ?: return InteractionResult.PASS
        sp.openScevMenu(containerLangKey, pos) { id, inv ->
            // Cast to BE — at runtime each subclass only ever gets its
            // matching BE because newBlockEntity is the constructor
            // path for placement.
            @Suppress("UNCHECKED_CAST")
            openMenu(id, inv, be as BE)
        }
        return InteractionResult.CONSUME
    }

    /** Drop both IO + expansion slot contents on break. */
    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, moved: Boolean) {
        if (!state.`is`(newState.block)) {
            (level.getBlockEntity(pos) as? ProcessingMachineBlockEntity)?.let { be ->
                Containers.dropContents(level, pos, be)
                level.updateNeighbourForOutputSignal(pos, this)
            }
        }
        super.onRemove(state, level, pos, newState, moved)
    }
}
