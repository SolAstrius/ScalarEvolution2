/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.blockentity

import java.util.EnumSet
import java.util.UUID
import lekkit.scev.bus.PeripheralBusElement
import lekkit.scev.bus.PeripheralDeviceKind
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState

/**
 * Keyboard block. Advertises [PeripheralDeviceKind.KEYBOARD] (plus
 * [PeripheralDeviceKind.MOUSE] when the variant has a trackpad).
 * Participates in the peripheral bus as a conduit — a cable can pass
 * through a keyboard to a second keyboard beyond it.
 */
class KeyboardBlockEntity(
    type: BlockEntityType<*>,
    pos: BlockPos,
    state: BlockState,
    private val hasMouse: Boolean,
) : ScevBlockEntity(type, pos, state), PeripheralBusElement {

    private val kinds: Set<PeripheralDeviceKind> = if (hasMouse)
        EnumSet.of(PeripheralDeviceKind.KEYBOARD, PeripheralDeviceKind.MOUSE)
        else EnumSet.of(PeripheralDeviceKind.KEYBOARD)

    private var bound: UUID? = null
    private var boundPos: BlockPos? = null

    fun hasMouse(): Boolean = hasMouse

    override fun peripheralKinds(): Set<PeripheralDeviceKind> = kinds

    override fun boundMachineUuid(): UUID? = bound
    override fun setBoundMachineUuid(uuid: UUID?) { bound = uuid }
    override fun boundMachinePos(): BlockPos? = boundPos
    override fun setBoundMachinePos(pos: BlockPos?) { boundPos = pos }
}
