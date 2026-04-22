/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.blockentity;

import java.util.UUID;
import lekkit.scev.bus.PeripheralBusElement;
import lekkit.scev.main.ScevRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Cable block entity — the canonical pure conduit on the peripheral bus.
 *
 * <p>Advertises no device kinds; the {@link PeripheralBusElement} default
 * returns an empty set. The BFS walker treats it as a pass-through: a
 * scan traverses directly from one side of a cable to the other, letting
 * players route connections around obstacles.
 */
public class CableBlockEntity extends ScevBlockEntity implements PeripheralBusElement {
    private @Nullable UUID boundMachineUuid;
    private @Nullable BlockPos boundMachinePos;

    public CableBlockEntity(BlockPos pos, BlockState state) {
        super(ScevRegistry.CABLE_BE.get(), pos, state);
    }

    @Override public @Nullable UUID boundMachineUuid() { return boundMachineUuid; }
    @Override public void setBoundMachineUuid(@Nullable UUID uuid) { this.boundMachineUuid = uuid; }
    @Override public @Nullable BlockPos boundMachinePos() { return boundMachinePos; }
    @Override public void setBoundMachinePos(@Nullable BlockPos pos) { this.boundMachinePos = pos; }
}
