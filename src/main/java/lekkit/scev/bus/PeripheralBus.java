/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.bus;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

/**
 * The scan result — what peripherals a computer's bus discovered this tick.
 *
 * <p>Produced by {@link #scan}, consumed by the
 * {@link PeripheralBusController}. Immutable; call {@link #scan} again to
 * recompute. All positions are absolute world coords.
 *
 * <h2>BFS traversal</h2>
 *
 * <p>Starts at the computer's position, enqueues each of the 6 axis
 * neighbours, and recurses through any {@link PeripheralBusElement} it
 * finds. Cables (empty {@code peripheralKinds}) act as conduits; devices
 * contribute kinds but may still be conduits themselves (the default) so
 * multiple keyboards can be chained off a cable without splitting the bus.
 *
 * <h2>Safety</h2>
 *
 * <p>{@link #MAX_ELEMENTS} caps the number of BEs visited to keep the
 * scan cheap and prevent pathological setups (giant cable nets) from
 * freezing the server tick. Visited set prevents cycles. Per-scan
 * {@code HashSet} allocation is fine — scans are triggered on state
 * changes, not every tick.
 */
public final class PeripheralBus {
    /** Hard ceiling on bus size. Matches OC2's per-controller cap. */
    public static final int MAX_ELEMENTS = 128;

    public static final PeripheralBus EMPTY = new PeripheralBus(
            Set.of(), Map.of(), false, false);

    /** Every position on the bus, including the root (the computer itself). */
    private final Set<BlockPos> elements;

    /** Device positions grouped by kind. Lists for cases with multiple of a kind. */
    private final Map<PeripheralDeviceKind, List<BlockPos>> byKind;

    /** True if the scan hit {@link #MAX_ELEMENTS} before exhausting the graph. */
    private final boolean truncated;

    /**
     * True if another controller was already bound to any element the scan
     * touched — signals "two computers on one bus" and is what the UI
     * surfaces as a conflict error.
     */
    private final boolean conflict;

    private PeripheralBus(Set<BlockPos> elements,
                          Map<PeripheralDeviceKind, List<BlockPos>> byKind,
                          boolean truncated, boolean conflict) {
        this.elements = Collections.unmodifiableSet(elements);
        this.byKind = Collections.unmodifiableMap(byKind);
        this.truncated = truncated;
        this.conflict = conflict;
    }

    public Set<BlockPos> elements() { return elements; }

    public List<BlockPos> devices(PeripheralDeviceKind kind) {
        return byKind.getOrDefault(kind, List.of());
    }

    public boolean hasDevice(PeripheralDeviceKind kind) {
        return !devices(kind).isEmpty();
    }

    public boolean truncated() { return truncated; }
    public boolean conflict()  { return conflict; }
    public int size()          { return elements.size(); }

    /**
     * Walk the bus starting from {@code root} using a {@code Level} to
     * look up block entities. Production callers use this overload —
     * the inner lookup ducks through {@code BlockEntity → PeripheralBusElement}.
     */
    public static PeripheralBus scan(Level level, BlockPos root, @Nullable UUID owner) {
        return scan(pos -> {
            BlockEntity be = level.getBlockEntity(pos);
            return be instanceof PeripheralBusElement el ? el : null;
        }, root, owner);
    }

    /**
     * Core BFS — takes a lookup function that returns a
     * {@link PeripheralBusElement} directly (or null if no element at
     * that position). Decoupling from {@code BlockEntity} lets tests
     * plug in tiny hand-rolled element stubs without dragging in the
     * NeoForge bootstrap or BE construction.
     *
     * @param elementLookup  returns the element at a given position, or {@code null}.
     * @param root           the starting block position (usually the computer itself).
     * @param owner          this scan's owning machine UUID — if a bus element is
     *                       already bound to a DIFFERENT uuid, we flag a conflict
     *                       and abort the traversal through that element's neighbours.
     *                       Pass null for test scans that don't care about binding.
     */
    public static PeripheralBus scan(Function<BlockPos, @Nullable PeripheralBusElement> elementLookup,
                                      BlockPos root, @Nullable UUID owner) {
        Set<BlockPos> visited = new HashSet<>();
        Map<PeripheralDeviceKind, List<BlockPos>> byKind = new EnumMap<>(PeripheralDeviceKind.class);
        Deque<BlockPos> queue = new ArrayDeque<>();

        queue.add(root);
        visited.add(root);
        boolean truncated = false;
        boolean conflict = false;

        while (!queue.isEmpty()) {
            if (visited.size() > MAX_ELEMENTS) {
                truncated = true;
                break;
            }

            BlockPos here = queue.poll();
            for (Direction d : Direction.values()) {
                BlockPos next = here.relative(d);
                if (visited.contains(next)) continue;

                PeripheralBusElement element = elementLookup.apply(next);
                if (element == null) continue;

                // Conflict detection: another controller already owns this
                // element. Count it, but don't walk through it — protects
                // against a cable accidentally bridging two computers.
                UUID prior = element.boundMachineUuid();
                if (owner != null && prior != null && !prior.equals(owner)) {
                    conflict = true;
                    continue;
                }

                visited.add(next);

                // Collect device kinds from non-root elements. The root
                // itself is the computer — it's on the bus for bookkeeping
                // (so peripherals know their owner's position) but doesn't
                // advertise peripheral kinds.
                for (PeripheralDeviceKind kind : element.peripheralKinds()) {
                    byKind.computeIfAbsent(kind, k -> new java.util.ArrayList<>()).add(next);
                }

                // Only traverse through conduits. A device that overrides
                // isBusConduit -> false is a terminus.
                if (element.isBusConduit()) {
                    queue.add(next);
                }
            }
        }

        // Freeze lists — the builder map held ArrayLists for append efficiency
        // during the walk; the returned bus is immutable.
        EnumMap<PeripheralDeviceKind, List<BlockPos>> frozen = new EnumMap<>(PeripheralDeviceKind.class);
        for (var e : byKind.entrySet()) {
            frozen.put(e.getKey(), List.copyOf(e.getValue()));
        }

        return new PeripheralBus(visited, frozen, truncated, conflict);
    }
}
