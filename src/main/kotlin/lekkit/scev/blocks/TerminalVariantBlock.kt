/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.blocks

import lekkit.scev.blockentity.TerminalBlockEntity
import lekkit.scev.blockentity.TerminalKind
import lekkit.scev.main.ScevRegistry
import lekkit.scev.menu.TerminalMenu
import lekkit.scev.menu.openScevMenu
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult

/**
 * Shared base for every concrete terminal variant block (Vt100Block,
 * Vt220Block, Vt340Block, …). Subclasses pick the
 * [TerminalKind] in their constructor; everything else — opening the
 * menu, server-tick wiring, BlockEntity spawn — is identical across
 * eras.
 *
 * The actual visual + asset differences (model, texture, recipe) live
 * outside this class: each variant gets its own registry slot in
 * [ScevRegistry], its own block-state model from
 * [ScevBlockStateProvider], and its own texture under
 * `assets/scev/textures/block/<name>.png`.
 *
 * All variants share one [BlockEntityType] (`TERMINAL_BE`) — the
 * underlying [TerminalBlockEntity] is era-agnostic; only the kind it
 * was constructed with differs.
 */
abstract class TerminalVariantBlock(props: Properties, val kind: TerminalKind) :
    DirectionalBlock(props) {

    final override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        TerminalBlockEntity(pos, state, kind)

    /** Drives [TerminalBlockEntity.serverTick] every server tick — needed
     *  because the BE is what (re)attaches its KernelConsoleSink to
     *  the bound machine when the bus stamps a UUID on it. Without
     *  this ticker the sink is never registered and ttyS0 bytes
     *  drain only to the SLF4J logger; the player sees the disconnected
     *  BootDemo regardless of bus topology. */
    final override fun <T : BlockEntity> getTicker(
        level: Level, state: BlockState, type: BlockEntityType<T>,
    ): BlockEntityTicker<T>? = scevTicker(level, type, ScevRegistry.TERMINAL_BE.get())

    final override fun useWithoutItem(
        state: BlockState, level: Level, pos: BlockPos, player: Player, hit: BlockHitResult,
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val sp = player as? ServerPlayer ?: return InteractionResult.PASS
        // Snapshot the bound machine UUID + kind at open time. Once
        // the menu is open the binding is "locked" until the screen
        // is closed — bus rescans don't refresh the open menu. Keeps
        // the wire / dispatch story trivial.
        val be = level.getBlockEntity(pos) as? TerminalBlockEntity
        val boundUuid = be?.boundMachineUuid()
        // Kind comes from the BE if present (so a /setblock with
        // saved NBT honors what's in the world) and falls back to
        // the block class's own kind for fresh placements.
        val kindToOpen = be?.kind ?: kind
        sp.openScevMenu(
            titleKey = "container.scev.terminal",
            writeExtra = { buf ->
                buf.writeBlockPos(pos)
                if (boundUuid != null) {
                    buf.writeBoolean(true)
                    buf.writeLong(boundUuid.mostSignificantBits)
                    buf.writeLong(boundUuid.leastSignificantBits)
                } else {
                    buf.writeBoolean(false)
                }
                buf.writeUtf(kindToOpen.name, 32)
            },
            factory = { id, inv -> TerminalMenu(id, inv, pos, boundUuid, kindToOpen) },
        )
        return InteractionResult.CONSUME
    }
}
