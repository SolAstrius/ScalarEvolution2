/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.bus

import java.util.ArrayDeque
import java.util.EnumMap
import java.util.UUID
import java.util.function.Function
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.Level

/**
 * The scan result — what peripherals a computer's bus discovered this
 * tick.
 *
 * Produced by [scan], consumed by the [PeripheralBusController].
 * Immutable; call [scan] again to recompute. All positions are
 * absolute world coords.
 *
 * BFS starts at the computer's position, enqueues each of the 6 axis
 * neighbours, and recurses through any [PeripheralBusElement] it
 * finds. Cables (empty `peripheralKinds`) act as conduits; devices
 * contribute kinds but may still be conduits themselves (the default)
 * so multiple keyboards can be chained off a cable without splitting
 * the bus.
 *
 * [MAX_ELEMENTS] caps the number of BEs visited to keep the scan
 * cheap and prevent pathological setups (giant cable nets) from
 * freezing the server tick. Visited set prevents cycles. Per-scan
 * `HashSet` allocation is fine — scans are triggered on state
 * changes, not every tick.
 */
class PeripheralBus private constructor(
    elements: Set<BlockPos>,
    byKind: Map<PeripheralDeviceKind, List<BlockPos>>,
    private val truncated: Boolean,
    private val conflict: Boolean,
) {
    /** Every position on the bus, including the root (the computer itself). */
    private val elements: Set<BlockPos> = java.util.Collections.unmodifiableSet(elements)

    /** Device positions grouped by kind. Lists for cases with multiple of a kind. */
    private val byKind: Map<PeripheralDeviceKind, List<BlockPos>> = java.util.Collections.unmodifiableMap(byKind)

    fun elements(): Set<BlockPos> = elements

    fun devices(kind: PeripheralDeviceKind): List<BlockPos> =
        byKind.getOrDefault(kind, emptyList())

    fun hasDevice(kind: PeripheralDeviceKind): Boolean = devices(kind).isNotEmpty()

    fun truncated(): Boolean = truncated
    fun conflict(): Boolean = conflict
    fun size(): Int = elements.size

    companion object {
        /** Hard ceiling on bus size. Matches OC2's per-controller cap. */
        const val MAX_ELEMENTS: Int = 128

        @JvmField
        val EMPTY: PeripheralBus = PeripheralBus(emptySet(), emptyMap(), false, false)

        /**
         * Walk the bus starting from [root] using a [Level] to look up
         * block entities. Production callers use this overload — the
         * inner lookup ducks through `BlockEntity → PeripheralBusElement`.
         */
        @JvmStatic
        fun scan(level: Level, root: BlockPos, owner: UUID?): PeripheralBus =
            scan(Function { pos ->
                level.getBlockEntity(pos) as? PeripheralBusElement
            }, root, owner)

        /**
         * Core BFS — takes a lookup function that returns a
         * [PeripheralBusElement] directly (or null if no element at that
         * position). Decoupling from `BlockEntity` lets tests plug in
         * tiny hand-rolled element stubs without dragging in the
         * NeoForge bootstrap or BE construction.
         *
         * @param elementLookup returns the element at a given position,
         *                      or `null`.
         * @param root          the starting block position (usually the
         *                      computer itself).
         * @param owner         this scan's owning machine UUID — if a
         *                      bus element is already bound to a
         *                      DIFFERENT uuid, we flag a conflict and
         *                      abort the traversal through that
         *                      element's neighbours. Pass null for test
         *                      scans that don't care about binding.
         */
        @JvmStatic
        fun scan(
            elementLookup: Function<BlockPos, PeripheralBusElement?>,
            root: BlockPos,
            owner: UUID?,
        ): PeripheralBus {
            val visited = HashSet<BlockPos>()
            val byKind = EnumMap<PeripheralDeviceKind, MutableList<BlockPos>>(PeripheralDeviceKind::class.java)
            val queue = ArrayDeque<BlockPos>()

            queue.add(root)
            visited.add(root)
            var truncated = false
            var conflict = false

            while (queue.isNotEmpty()) {
                if (visited.size > MAX_ELEMENTS) {
                    truncated = true
                    break
                }

                val here = queue.poll()
                for (d in Direction.values()) {
                    val next = here.relative(d)
                    if (visited.contains(next)) continue

                    val element = elementLookup.apply(next) ?: continue

                    // Conflict detection: another controller already owns this
                    // element. Count it, but don't walk through it — protects
                    // against a cable accidentally bridging two computers.
                    val prior = element.boundMachineUuid()
                    if (owner != null && prior != null && prior != owner) {
                        conflict = true
                        continue
                    }

                    visited.add(next)

                    // Collect device kinds from non-root elements. The root
                    // itself is the computer — it's on the bus for bookkeeping
                    // (so peripherals know their owner's position) but doesn't
                    // advertise peripheral kinds.
                    for (kind in element.peripheralKinds()) {
                        byKind.getOrPut(kind) { ArrayList() }.add(next)
                    }

                    // Only traverse through conduits. A device that overrides
                    // isBusConduit -> false is a terminus.
                    if (element.isBusConduit()) {
                        queue.add(next)
                    }
                }
            }

            // Freeze lists — the builder map held ArrayLists for append efficiency
            // during the walk; the returned bus is immutable.
            val frozen = EnumMap<PeripheralDeviceKind, List<BlockPos>>(PeripheralDeviceKind::class.java)
            for ((kind, list) in byKind) {
                frozen[kind] = list.toList()
            }

            return PeripheralBus(visited, frozen, truncated, conflict)
        }
    }
}
