/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.blockentity;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import lekkit.scev.bus.PeripheralBusElement;
import lekkit.scev.bus.PeripheralDeviceKind;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Keyboard block. Advertises {@link PeripheralDeviceKind#KEYBOARD}
 * (plus {@link PeripheralDeviceKind#MOUSE} when the variant has a
 * trackpad). Participates in the peripheral bus as a conduit — a cable
 * can pass through a keyboard to a second keyboard beyond it.
 */
public class KeyboardBlockEntity extends ScevBlockEntity implements PeripheralBusElement {
    private final boolean hasMouse;
    private final Set<PeripheralDeviceKind> kinds;
    private @Nullable UUID boundMachineUuid;
    private @Nullable BlockPos boundMachinePos;

    public KeyboardBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state,
                               boolean hasMouse) {
        super(type, pos, state);
        this.hasMouse = hasMouse;
        this.kinds = hasMouse
                ? EnumSet.of(PeripheralDeviceKind.KEYBOARD, PeripheralDeviceKind.MOUSE)
                : EnumSet.of(PeripheralDeviceKind.KEYBOARD);
    }

    public boolean hasMouse() { return hasMouse; }

    @Override public Set<PeripheralDeviceKind> peripheralKinds() { return kinds; }

    @Override public @Nullable UUID boundMachineUuid() { return boundMachineUuid; }

    @Override public void setBoundMachineUuid(@Nullable UUID uuid) {
        this.boundMachineUuid = uuid;
    }

    @Override public @Nullable BlockPos boundMachinePos() { return boundMachinePos; }

    @Override public void setBoundMachinePos(@Nullable BlockPos pos) {
        this.boundMachinePos = pos;
    }
}
