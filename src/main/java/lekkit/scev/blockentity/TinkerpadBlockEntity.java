/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.blockentity;

import lekkit.scev.main.ScevRegistry;
import lekkit.scev.server.IDisplayHandle;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Tinkerpad is a laptop form factor: it owns its own machine and its lid
 * contains a built-in screen. {@link #forceBuiltInDisplay()} returns true so
 * the spec parser always ships a display in the resulting {@link
 * lekkit.scev.machine.MachineSpec}, regardless of whether a VGA PCI card is
 * present — a laptop without a display would be pointless.
 */
public class TinkerpadBlockEntity extends ComputerCaseBlockEntity implements IDisplayHandle {
    public TinkerpadBlockEntity(BlockPos pos, BlockState state) {
        super(ScevRegistry.TINKERPAD_BE.get(), pos, state, 3, 2);
    }

    @Override
    protected boolean forceBuiltInDisplay() { return true; }
}
