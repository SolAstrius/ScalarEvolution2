/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.blocks

import lekkit.scev.blockentity.ComputerCaseBlockEntity
import lekkit.scev.blockentity.PowermarkBlockEntity
import lekkit.scev.blockentity.TinkerpadBlockEntity
import lekkit.scev.blockentity.WorkstationBlockEntity
import lekkit.scev.main.ScevRegistry
import lekkit.scev.menu.ComputerCaseMenu
import lekkit.scev.menu.MachineMenu
import lekkit.scev.menu.openScevMenu
import lekkit.scev.server.MachineManager
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult

class WorkstationBlock(props: Properties) : DirectionalBlock(props) {

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity {
        // Which BE to build is decided by the Block's registry name — caller supplies the
        // concrete DeferredBlock (see ScevRegistry). We choose at creation time by matching
        // the owning block instance.
        val resource = BuiltInRegistries.BLOCK.getKey(this) ?: return WorkstationBlockEntity(pos, state)
        return when (resource.path) {
            "powermark" -> PowermarkBlockEntity(pos, state)
            "tinkerpad" -> TinkerpadBlockEntity(pos, state)
            else -> WorkstationBlockEntity(pos, state)
        }
    }

    /**
     * Return a ticker matching the BE type the concrete block spawns.
     * Each workstation variant (workstation / powermark / tinkerpad)
     * uses the same [ComputerCaseBlockEntity.serverTick] polling loop —
     * we just need to get the BE type right so NeoForge dispatches
     * ticks correctly.
     */
    override fun <T : BlockEntity> getTicker(
        level: Level, state: BlockState, type: BlockEntityType<T>,
    ): BlockEntityTicker<T>? {
        if (level.isClientSide) return null
        val resource = BuiltInRegistries.BLOCK.getKey(this) ?: return null
        val expected: BlockEntityType<*> = when (resource.path) {
            "powermark" -> ScevRegistry.POWERMARK_BE.get()
            "tinkerpad" -> ScevRegistry.TINKERPAD_BE.get()
            else -> ScevRegistry.WORKSTATION_BE.get()
        }
        if (type != expected) return null
        return BlockEntityTicker { lvl, pos, st, be ->
            if (be is ComputerCaseBlockEntity) be.serverTick(lvl, pos, st)
        }
    }

    override fun useWithoutItem(
        state: BlockState, level: Level, pos: BlockPos, player: Player, hit: BlockHitResult,
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val cc = level.getBlockEntity(pos) as? ComputerCaseBlockEntity ?: return InteractionResult.PASS
        val sp = player as? ServerPlayer ?: return InteractionResult.PASS

        // Shift-right-click on a powered case: open the framebuffer view
        // (MachineMenu / MachineScreen). Otherwise open the component
        // edit menu (ComputerCaseMenu).
        val wantsView = sp.isShiftKeyDown && cc.isPowered()
        if (wantsView) {
            sp.openScevMenu("container.scev.machine",
                { buf -> buf.writeByte(MachineMenu.SOURCE_BLOCK.toInt()); buf.writeBlockPos(pos) }
            ) { id, inv -> MachineMenu(id, inv, cc.getMachineUUID(), cc) }
        } else {
            sp.openScevMenu("container.scev.computer_case", pos) { id, inv ->
                ComputerCaseMenu(id, inv, cc)
            }
        }
        return InteractionResult.CONSUME
    }

    /**
     * Break-block: tear down the owning [MachineManager] entry so the
     * emulator thread + native RVVM machine are freed. Without this,
     * breaking a computer case left the VM running forever (audio kept
     * streaming, server kept emulating) and GC-driven teardown of
     * adjacent Java-side state — peripheral bus, display manager, sound
     * manager — raced against the still-live RVVM threadpool. Observed
     * failure mode: SIGSEGV inside `pci_func_send_intx_irq` when a
     * worker fired an IRQ at a `pci_func_t` whose owner was being
     * dismantled.
     *
     * Mirrors [McuBoardBlock.onRemove] — same pattern, same "replaced
     * vs. state-changed" guard. The `state.is(newState.getBlock())`
     * check prevents a state-only change (e.g. a facing update) from
     * tearing the machine down, which would be surprising and costly.
     */
    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, moved: Boolean) {
        if (!state.`is`(newState.block)) {
            (level.getBlockEntity(pos) as? ComputerCaseBlockEntity)?.let { cc ->
                MachineManager.destroyMachineState(cc.getMachineUUID())
            }
        }
        super.onRemove(state, level, pos, newState, moved)
    }
}
