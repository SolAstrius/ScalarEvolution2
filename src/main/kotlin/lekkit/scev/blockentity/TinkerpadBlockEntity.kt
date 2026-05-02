/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.blockentity

import lekkit.scev.main.ScevRegistry
import lekkit.scev.server.IDisplayHandle
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState

/**
 * Tinkerpad — laptop form factor: owns its own machine, lid contains a
 * built-in screen. [forceBuiltInDisplay] returns true so the spec parser
 * always ships a display in the resulting [lekkit.scev.machine.MachineSpec],
 * regardless of whether a VGA PCI card is present.
 */
class TinkerpadBlockEntity(pos: BlockPos, state: BlockState) :
    ComputerCaseBlockEntity(ScevRegistry.TINKERPAD_BE.get(), pos, state, 3, 2),
    IDisplayHandle {

    override fun forceBuiltInDisplay(): Boolean = true
}
