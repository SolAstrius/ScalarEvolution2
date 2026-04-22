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
import lekkit.scev.server.MachineManager;
import lekkit.scev.server.MachineState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * VT100 is a standalone terminal: it shows the framebuffer of a linked
 * machine. Linking is simple auto-discovery — when queried for its display,
 * the VT100 scans a small volume around itself for running machines and
 * picks the closest one with an attached framebuffer.
 *
 * <p>A linked UUID is cached (and persisted in NBT) so consecutive frames
 * don't re-scan. The cache invalidates when the linked machine stops or
 * goes out of range.
 */
public class VT100BlockEntity extends ScevBlockEntity implements PeripheralBusElement {
    /** Cubic radius around the VT100 to search for machines (manhattan distance in blocks). */
    private static final int LINK_RADIUS = 6;

    private @Nullable UUID linkedMachineUuid;

    /**
     * Bus-bound machine UUID. Stamped by a {@link lekkit.scev.bus.PeripheralBusController}
     * when the VT100 is on a scanned bus. Takes precedence over the legacy
     * cube-scan in {@link #resolveLinkedMachine} — cable connection beats
     * proximity because it's an explicit player choice.
     */
    private @Nullable UUID boundMachineUuid;
    private @Nullable BlockPos boundMachinePos;

    public VT100BlockEntity(BlockPos pos, BlockState state) {
        super(ScevRegistry.VT100_BE.get(), pos, state);
    }

    /**
     * Resolve the machine whose framebuffer should be shown on this VT100.
     * Server-side only — the client queries this indirectly via the UUID cache
     * which gets synced through {@link #getUpdateTag}.
     */
    public @Nullable UUID resolveLinkedMachine() {
        // Bus-bound wins: a cable-connected VT100 follows the explicit
        // player wire, not whichever machine happens to be nearest.
        if (boundMachineUuid != null) {
            MachineState ms = MachineManager.getMachineState(boundMachineUuid);
            if (ms != null && ms.getDisplay() != null) {
                linkedMachineUuid = boundMachineUuid;
                return boundMachineUuid;
            }
        }

        if (linkedMachineUuid != null) {
            MachineState ms = MachineManager.getMachineState(linkedMachineUuid);
            if (ms != null && ms.getDisplay() != null) return linkedMachineUuid;
            linkedMachineUuid = null; // stale
        }
        if (level == null || level.isClientSide) return linkedMachineUuid;

        // Scan nearby computer-case BEs for the closest running machine with a display.
        BlockPos here = getBlockPos();
        double best = Double.POSITIVE_INFINITY;
        UUID bestUuid = null;
        for (int dx = -LINK_RADIUS; dx <= LINK_RADIUS; dx++) {
            for (int dy = -LINK_RADIUS; dy <= LINK_RADIUS; dy++) {
                for (int dz = -LINK_RADIUS; dz <= LINK_RADIUS; dz++) {
                    BlockPos p = here.offset(dx, dy, dz);
                    var be = level.getBlockEntity(p);
                    if (!(be instanceof ComputerCaseBlockEntity case_)) continue;
                    UUID uuid = case_.getMachineUUID();
                    MachineState ms = MachineManager.getMachineState(uuid);
                    if (ms == null || ms.getDisplay() == null) continue;
                    double d = here.distSqr(p);
                    if (d < best) {
                        best = d;
                        bestUuid = uuid;
                    }
                }
            }
        }
        if (bestUuid != null) {
            linkedMachineUuid = bestUuid;
            setChanged();
        }
        return linkedMachineUuid;
    }

    public @Nullable UUID getLinkedMachineUuid() {
        return linkedMachineUuid;
    }

    @Override
    public Set<PeripheralDeviceKind> peripheralKinds() {
        return Set.of(PeripheralDeviceKind.DISPLAY);
    }

    @Override
    public @Nullable UUID boundMachineUuid() { return boundMachineUuid; }

    @Override
    public void setBoundMachineUuid(@Nullable UUID uuid) { this.boundMachineUuid = uuid; }

    @Override
    public @Nullable BlockPos boundMachinePos() { return boundMachinePos; }

    @Override
    public void setBoundMachinePos(@Nullable BlockPos pos) { this.boundMachinePos = pos; }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.hasUUID("LinkedMachine")) {
            linkedMachineUuid = tag.getUUID("LinkedMachine");
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (linkedMachineUuid != null) tag.putUUID("LinkedMachine", linkedMachineUuid);
    }
}
