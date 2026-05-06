/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.blocks

import lekkit.scev.blockentity.ComputerCaseBlockEntity
import lekkit.scev.blockentity.KeyboardBlockEntity
import lekkit.scev.main.ScevRegistry
import lekkit.scev.menu.MachineMenu
import lekkit.scev.menu.openScevMenu
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * Flat pizza-box keyboard block (optionally with mouse side too).
 */
class KeyboardBlock(props: Properties, private val hasMouse: Boolean) : DirectionalBlock(props) {

    fun hasMouse(): Boolean = hasMouse

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        KeyboardBlockEntity(
            if (hasMouse) ScevRegistry.KEYBOARD_MOUSE_BE.get() else ScevRegistry.KEYBOARD_BE.get(),
            pos, state, hasMouse)

    /**
     * Right-click the keyboard → open the bound machine's framebuffer
     * view. Relies on the peripheral-bus controller having stamped this
     * keyboard with the computer's UUID + position during its last scan.
     */
    override fun useWithoutItem(
        state: BlockState, level: Level, pos: BlockPos, player: Player, hit: BlockHitResult,
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val kb = level.getBlockEntity(pos) as? KeyboardBlockEntity ?: return InteractionResult.PASS
        val sp = player as? ServerPlayer ?: return InteractionResult.PASS

        val boundUuid = kb.boundMachineUuid()
        val boundPos = kb.boundMachinePos()
        if (boundUuid == null || boundPos == null) {
            // Unbound keyboard — no computer adjacent and no cable route.
            // Tell the player rather than failing silently.
            sp.displayClientMessage(
                Component.translatable("text.scev.keyboard.unbound").withStyle(ChatFormatting.YELLOW),
                true)
            return InteractionResult.CONSUME
        }

        // Resolve the actual ComputerCaseBlockEntity at the recorded
        // position. If the computer was broken between scan and click, the
        // lookup returns null and we fall through with a friendly message.
        val cc = level.getBlockEntity(boundPos) as? ComputerCaseBlockEntity
        if (cc == null) {
            sp.displayClientMessage(
                Component.translatable("text.scev.keyboard.stale").withStyle(ChatFormatting.YELLOW),
                true)
            return InteractionResult.CONSUME
        }

        sp.openScevMenu("container.scev.machine",
            { buf -> buf.writeByte(MachineMenu.SOURCE_BLOCK.toInt()); buf.writeBlockPos(boundPos) }
        ) { id, inv -> MachineMenu(id, inv, cc.getMachineUUID(), cc) }
        return InteractionResult.CONSUME
    }

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, ctx: CollisionContext): VoxelShape =
        when (state.getValue(FACING)) {
            Direction.NORTH -> NS
            Direction.EAST -> EW
            Direction.SOUTH -> SN
            Direction.WEST -> WE
            else -> NS
        }

    companion object {
        private val NS: VoxelShape = Shapes.box(0.0, 0.0, 0.0, 1.0, 0.0625, 0.5625)
        private val EW: VoxelShape = Shapes.box(0.0, 0.0, 0.0, 0.5625, 0.0625, 1.0)
        private val SN: VoxelShape = Shapes.box(0.0, 0.0, 0.4375, 1.0, 0.0625, 1.0)
        private val WE: VoxelShape = Shapes.box(0.4375, 0.0, 0.0, 1.0, 0.0625, 1.0)
    }
}
