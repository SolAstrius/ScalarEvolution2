/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.bus

import java.util.UUID
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level

/**
 * Per-computer scan loop. Owned by any machine block entity that
 * wants a peripheral bus (ComputerCase, McuBoard). Holds the last
 * scan result, triggers fresh scans on invalidation or on a slow
 * periodic timer, and stamps every discovered element with the
 * owner's machine UUID so peripherals can find their computer
 * without a reverse walk.
 *
 * ## Scan triggers
 *
 * - **Explicit** — [invalidate] on neighbour change, chunk load, or
 *   wrench rotation. Rescan on the next controller tick.
 * - **Periodic safety net** — [PERIODIC_RESCAN_TICKS] (20 ticks =
 *   1 s). Catches distant changes a direct neighbour callback
 *   wouldn't see, e.g. a cable being placed 10 blocks away. Cheap
 *   because BFS on a capped graph is O(N).
 *
 * After BFS collects the element set, the controller iterates once
 * more and calls [PeripheralBusElement.setBoundMachineUuid] on each.
 * Elements that used to belong to this bus but don't anymore get
 * their binding cleared — but only if the previous owner is us.
 * Another controller's elements stay bound to that controller (the
 * conflict flag on the bus signals the overlap).
 */
class PeripheralBusController(
    private val level: Level,
    private val rootPos: BlockPos,
    private val machineUuid: UUID,
) {
    private var bus: PeripheralBus = PeripheralBus.EMPTY

    /**
     * Previous scan's element set — used on the next scan to clear
     * bindings on elements that dropped off the bus.
     */
    private var lastElements: Set<BlockPos> = emptySet()

    private var dirty = true
    private var ticksSinceScan = 0

    /** Current cached bus. Refreshed only by [tick]. */
    fun getBus(): PeripheralBus = bus

    /** Request a rescan on the next controller tick. */
    fun invalidate() { dirty = true }

    /**
     * Drive the scan loop. Call once per server tick from the owning
     * BE's `serverTick`. Cheap when the bus is clean — just
     * increments a counter.
     */
    fun tick() {
        if (level.isClientSide) return
        ticksSinceScan++

        if (!dirty && ticksSinceScan < PERIODIC_RESCAN_TICKS) return

        dirty = false
        ticksSinceScan = 0
        rescan()
    }

    /** Force an immediate rescan, bypassing the periodic timer. */
    fun rescan() {
        val fresh = PeripheralBus.scan(level, rootPos, machineUuid)

        // Clear bindings on elements we owned that aren't on the bus anymore.
        // Only clear if the element still claims us as owner — another
        // controller may have legitimately taken over in the meantime.
        for (pos in lastElements) {
            if (fresh.elements().contains(pos)) continue
            val el = level.getBlockEntity(pos) as? PeripheralBusElement ?: continue
            if (machineUuid == el.boundMachineUuid()) {
                el.setBoundMachineUuid(null)
                el.setBoundMachinePos(null)
            }
        }

        // Stamp fresh ownership on the current element set. Idempotent.
        for (pos in fresh.elements()) {
            if (pos == rootPos) continue        // computer itself isn't a peripheral
            val el = level.getBlockEntity(pos) as? PeripheralBusElement ?: continue
            el.setBoundMachineUuid(machineUuid)
            el.setBoundMachinePos(rootPos)
        }

        bus = fresh
        lastElements = fresh.elements()
    }

    /**
     * Clear all bindings we own and drop the cached bus. Called on BE
     * removal so peripherals don't keep pointing at a dead computer.
     */
    fun dispose() {
        for (pos in lastElements) {
            val el = level.getBlockEntity(pos) as? PeripheralBusElement ?: continue
            if (machineUuid == el.boundMachineUuid()) {
                el.setBoundMachineUuid(null)
                el.setBoundMachinePos(null)
            }
        }
        lastElements = emptySet()
        bus = PeripheralBus.EMPTY
    }

    companion object {
        /** Ticks between periodic rescans when nothing has explicitly invalidated us. */
        const val PERIODIC_RESCAN_TICKS: Int = 20
    }
}
