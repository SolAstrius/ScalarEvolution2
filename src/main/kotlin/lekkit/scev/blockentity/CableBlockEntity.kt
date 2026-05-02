/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.blockentity

import java.util.UUID
import lekkit.scev.bus.PeripheralBusElement
import lekkit.scev.main.ScevRegistry
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState

/**
 * Cable block entity — the canonical pure conduit on the peripheral bus.
 *
 * Advertises no device kinds; the [PeripheralBusElement] default returns an
 * empty set. The BFS walker treats it as a pass-through: a scan traverses
 * directly from one side of a cable to the other, letting players route
 * connections around obstacles.
 */
class CableBlockEntity(pos: BlockPos, state: BlockState) :
    ScevBlockEntity(ScevRegistry.CABLE_BE.get(), pos, state),
    PeripheralBusElement {

    private var bound: UUID? = null
    private var boundPos: BlockPos? = null

    override fun boundMachineUuid(): UUID? = bound
    override fun setBoundMachineUuid(uuid: UUID?) { bound = uuid }
    override fun boundMachinePos(): BlockPos? = boundPos
    override fun setBoundMachinePos(pos: BlockPos?) { boundPos = pos }
}
