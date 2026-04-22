/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.bus;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Per-computer scan loop. Owned by any machine block entity that wants a
 * peripheral bus (ComputerCase, McuBoard). Holds the last scan result,
 * triggers fresh scans on invalidation or on a slow periodic timer,
 * and stamps every discovered element with the owner's machine UUID so
 * peripherals can find their computer without a reverse walk.
 *
 * <h2>Scan triggers</h2>
 *
 * <ul>
 *   <li><b>Explicit</b> — {@link #invalidate} on neighbour change, chunk
 *       load, or wrench rotation. Rescan on the next controller tick.</li>
 *   <li><b>Periodic safety net</b> — {@link #PERIODIC_RESCAN_TICKS} (20
 *       ticks = 1 s). Catches distant changes a direct neighbour callback
 *       wouldn't see, e.g. a cable being placed 10 blocks away. Cheap
 *       because BFS on a capped graph is O(N).</li>
 * </ul>
 *
 * <h2>Stamp pass</h2>
 *
 * <p>After BFS collects the element set, the controller iterates once
 * more and calls {@link PeripheralBusElement#setBoundMachineUuid} on each.
 * Elements that used to belong to this bus but don't anymore get their
 * binding cleared — but only if the previous owner is us. Another
 * controller's elements stay bound to that controller (the conflict flag
 * on the bus signals the overlap).
 */
public final class PeripheralBusController {
    /** Ticks between periodic rescans when nothing has explicitly invalidated us. */
    public static final int PERIODIC_RESCAN_TICKS = 20;

    private final Level level;
    private final BlockPos rootPos;
    private final UUID machineUuid;

    private PeripheralBus bus = PeripheralBus.EMPTY;
    /**
     * Previous scan's element set — used on the next scan to clear bindings
     * on elements that dropped off the bus. Null until the first scan.
     */
    private java.util.Set<BlockPos> lastElements = java.util.Set.of();

    private boolean dirty = true;
    private int ticksSinceScan = 0;

    public PeripheralBusController(Level level, BlockPos rootPos, UUID machineUuid) {
        this.level = level;
        this.rootPos = rootPos;
        this.machineUuid = machineUuid;
    }

    /** Current cached bus. Refreshed only by {@link #tick}. */
    public PeripheralBus getBus() { return bus; }

    /** Request a rescan on the next controller tick. */
    public void invalidate() { dirty = true; }

    /**
     * Drive the scan loop. Call once per server tick from the owning BE's
     * {@code serverTick}. Cheap when the bus is clean — just increments
     * a counter.
     */
    public void tick() {
        if (level.isClientSide) return;
        ticksSinceScan++;

        if (!dirty && ticksSinceScan < PERIODIC_RESCAN_TICKS) return;

        dirty = false;
        ticksSinceScan = 0;
        rescan();
    }

    /** Force an immediate rescan, bypassing the periodic timer. */
    public void rescan() {
        PeripheralBus fresh = PeripheralBus.scan(level, rootPos, machineUuid);

        // Clear bindings on elements we owned that aren't on the bus anymore.
        // Only clear if the element still claims us as owner — another
        // controller may have legitimately taken over in the meantime.
        for (BlockPos pos : lastElements) {
            if (fresh.elements().contains(pos)) continue;
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof PeripheralBusElement el && machineUuid.equals(el.boundMachineUuid())) {
                el.setBoundMachineUuid(null);
                el.setBoundMachinePos(null);
            }
        }

        // Stamp fresh ownership on the current element set. Idempotent.
        for (BlockPos pos : fresh.elements()) {
            if (pos.equals(rootPos)) continue;  // computer itself isn't a peripheral
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof PeripheralBusElement el) {
                el.setBoundMachineUuid(machineUuid);
                el.setBoundMachinePos(rootPos);
            }
        }

        bus = fresh;
        lastElements = fresh.elements();
    }

    /**
     * Clear all bindings we own and drop the cached bus. Called on BE
     * removal so peripherals don't keep pointing at a dead computer.
     */
    public void dispose() {
        for (BlockPos pos : lastElements) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof PeripheralBusElement el && machineUuid.equals(el.boundMachineUuid())) {
                el.setBoundMachineUuid(null);
                el.setBoundMachinePos(null);
            }
        }
        lastElements = java.util.Set.of();
        bus = PeripheralBus.EMPTY;
    }
}
