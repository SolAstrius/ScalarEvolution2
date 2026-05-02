/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.blockentity

import java.util.UUID
import lekkit.scev.bus.PeripheralBusElement
import lekkit.scev.bus.PeripheralDeviceKind
import lekkit.scev.main.ScevRegistry
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState

/**
 * CRT monitor block. Participates in the peripheral bus as a DISPLAY; the
 * framebuffer-mirroring render path is still deferred — this BE only
 * registers CRT with the bus so a controller scan can find it.
 */
class CRTBlockEntity(pos: BlockPos, state: BlockState) :
    ScevBlockEntity(ScevRegistry.CRT_BE.get(), pos, state),
    PeripheralBusElement {

    private var bound: UUID? = null
    private var boundPos: BlockPos? = null

    override fun peripheralKinds(): Set<PeripheralDeviceKind> = setOf(PeripheralDeviceKind.DISPLAY)

    override fun boundMachineUuid(): UUID? = bound
    override fun setBoundMachineUuid(uuid: UUID?) { bound = uuid }
    override fun boundMachinePos(): BlockPos? = boundPos
    override fun setBoundMachinePos(pos: BlockPos?) { boundPos = pos }
}
