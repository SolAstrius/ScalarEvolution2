/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.blockentity;

import java.util.Set;
import java.util.UUID;
import lekkit.scev.bus.PeripheralBusElement;
import lekkit.scev.bus.PeripheralDeviceKind;
import lekkit.scev.main.ScevRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * CRT monitor block. Participates in the peripheral bus as a DISPLAY;
 * the framebuffer-mirroring render path is still deferred — this PR
 * only registers CRT with the bus so a controller scan can find it. A
 * follow-up gives it a renderer analogous to {@code VT100Renderer} that
 * actually paints the bound machine's framebuffer on the block's face.
 */
public class CRTBlockEntity extends ScevBlockEntity implements PeripheralBusElement {
    private @Nullable UUID boundMachineUuid;
    private @Nullable BlockPos boundMachinePos;

    public CRTBlockEntity(BlockPos pos, BlockState state) {
        super(ScevRegistry.CRT_BE.get(), pos, state);
    }

    @Override
    public Set<PeripheralDeviceKind> peripheralKinds() {
        return Set.of(PeripheralDeviceKind.DISPLAY);
    }

    @Override public @Nullable UUID boundMachineUuid() { return boundMachineUuid; }

    @Override public void setBoundMachineUuid(@Nullable UUID uuid) { this.boundMachineUuid = uuid; }

    @Override public @Nullable BlockPos boundMachinePos() { return boundMachinePos; }
    @Override public void setBoundMachinePos(@Nullable BlockPos pos) { this.boundMachinePos = pos; }
}
