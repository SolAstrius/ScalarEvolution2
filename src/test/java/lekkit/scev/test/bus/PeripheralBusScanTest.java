/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.bus;

import static org.junit.jupiter.api.Assertions.*;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import lekkit.scev.bus.PeripheralBus;
import lekkit.scev.bus.PeripheralBusElement;
import lekkit.scev.bus.PeripheralDeviceKind;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PeripheralBus#scan}. Stubs the world with a
 * {@code Map<BlockPos, BlockEntity>} so we can hand-craft tiny topologies
 * and assert the scan handles each case correctly — straight-line walks,
 * cycles, cable branches, element cap, and multi-controller conflict.
 */
class PeripheralBusScanTest {

    @Test
    @DisplayName("Empty world: scan returns just the root element (the computer)")
    void emptyScan() {
        BlockPos root = new BlockPos(0, 0, 0);
        var level = lookup(Map.of());
        PeripheralBus bus = PeripheralBus.scan(level, root, UUID.randomUUID());
        assertEquals(Set.of(root), bus.elements());
        assertFalse(bus.truncated());
        assertFalse(bus.conflict());
    }

    @Test
    @DisplayName("Direct-adjacent keyboard: scan finds it and classifies KEYBOARD")
    void directAdjacentKeyboard() {
        BlockPos root = new BlockPos(0, 0, 0);
        BlockPos kb   = root.relative(Direction.NORTH);
        var keyboardBe = stubElement(EnumSet.of(PeripheralDeviceKind.KEYBOARD));
        var level = lookup(Map.of(kb, keyboardBe));

        PeripheralBus bus = PeripheralBus.scan(level, root, UUID.randomUUID());
        assertEquals(Set.of(root, kb), bus.elements());
        assertEquals(java.util.List.of(kb), bus.devices(PeripheralDeviceKind.KEYBOARD));
    }

    @Test
    @DisplayName("Cable routes through to a distant keyboard")
    void cableRoutesThrough() {
        BlockPos root = new BlockPos(0, 0, 0);
        BlockPos c1   = root.relative(Direction.NORTH);       // cable
        BlockPos c2   = c1.relative(Direction.NORTH);         // cable
        BlockPos kb   = c2.relative(Direction.NORTH);         // keyboard
        var level = lookup(Map.of(
                c1, stubElement(EnumSet.noneOf(PeripheralDeviceKind.class)),
                c2, stubElement(EnumSet.noneOf(PeripheralDeviceKind.class)),
                kb, stubElement(EnumSet.of(PeripheralDeviceKind.KEYBOARD))));

        PeripheralBus bus = PeripheralBus.scan(level, root, UUID.randomUUID());
        assertTrue(bus.elements().containsAll(Set.of(root, c1, c2, kb)));
        assertEquals(java.util.List.of(kb), bus.devices(PeripheralDeviceKind.KEYBOARD));
    }

    @Test
    @DisplayName("Cycle: cable loop doesn't cause infinite BFS or repeat visits")
    void cycleTerminates() {
        // 2x2 cable loop around the root.
        BlockPos root = new BlockPos(0, 0, 0);
        BlockPos a = root.relative(Direction.NORTH);
        BlockPos b = a.relative(Direction.EAST);
        BlockPos c = root.relative(Direction.EAST);
        var level = lookup(Map.of(
                a, stubElement(EnumSet.noneOf(PeripheralDeviceKind.class)),
                b, stubElement(EnumSet.noneOf(PeripheralDeviceKind.class)),
                c, stubElement(EnumSet.noneOf(PeripheralDeviceKind.class))));

        PeripheralBus bus = PeripheralBus.scan(level, root, UUID.randomUUID());
        assertEquals(4, bus.elements().size(), "root + 3 cables, no duplicates");
        assertFalse(bus.truncated());
    }

    @Test
    @DisplayName("Multi-controller conflict: other-owned elements are flagged and not traversed")
    void multiControllerConflict() {
        BlockPos root = new BlockPos(0, 0, 0);
        BlockPos kb = root.relative(Direction.NORTH);

        UUID otherOwner = UUID.randomUUID();
        UUID ourOwner = UUID.randomUUID();

        var element = stubElement(EnumSet.of(PeripheralDeviceKind.KEYBOARD));
        element.setBoundMachineUuid(otherOwner);

        var level = lookup(Map.of(kb, element));

        PeripheralBus bus = PeripheralBus.scan(level, root, ourOwner);
        assertTrue(bus.conflict(),
                "scan must flag conflict when a neighbour is already bound to another controller");
        assertFalse(bus.elements().contains(kb),
                "a conflicted element is not walked through — its devices don't count for us");
    }

    @Test
    @DisplayName("Non-conduit terminus: walk stops at a device that overrides isBusConduit=false")
    void nonConduitTerminus() {
        BlockPos root = new BlockPos(0, 0, 0);
        BlockPos term = root.relative(Direction.NORTH);
        BlockPos past = term.relative(Direction.NORTH);

        var terminus = new StubElement(EnumSet.of(PeripheralDeviceKind.KEYBOARD), /*conduit*/ false);
        var beyondKb = stubElement(EnumSet.of(PeripheralDeviceKind.KEYBOARD));

        var level = lookup(Map.of(term, terminus, past, beyondKb));

        PeripheralBus bus = PeripheralBus.scan(level, root, UUID.randomUUID());
        assertTrue(bus.elements().contains(term), "terminus itself is included");
        assertFalse(bus.elements().contains(past), "walk must stop at a non-conduit element");
    }

    /* ---------------- Stub helpers ---------------- */

    private static StubElement stubElement(Set<PeripheralDeviceKind> kinds) {
        return new StubElement(kinds, true);
    }

    /** Lightweight element stub — no BlockEntity scaffolding needed. */
    private static final class StubElement implements PeripheralBusElement {
        private final Set<PeripheralDeviceKind> kinds;
        private final boolean conduit;
        private @Nullable UUID boundMachineUuid;
        private @Nullable BlockPos boundMachinePos;

        StubElement(Set<PeripheralDeviceKind> kinds, boolean conduit) {
            this.kinds = kinds;
            this.conduit = conduit;
        }

        @Override public Set<PeripheralDeviceKind> peripheralKinds() { return kinds; }
        @Override public boolean isBusConduit() { return conduit; }
        @Override public @Nullable UUID boundMachineUuid() { return boundMachineUuid; }
        @Override public void setBoundMachineUuid(@Nullable UUID uuid) { this.boundMachineUuid = uuid; }
        @Override public @Nullable BlockPos boundMachinePos() { return boundMachinePos; }
        @Override public void setBoundMachinePos(@Nullable BlockPos pos) { this.boundMachinePos = pos; }
    }

    /**
     * Build a lookup function over a hand-crafted map of positions → BEs.
     * The scan takes a {@code Function<BlockPos, BlockEntity>} so tests
     * can feed tiny topologies without mocking the full {@code Level}
     * surface — and without a NeoForge Bootstrap, which drags in
     * registries we don't need here.
     */
    private static Function<BlockPos, @Nullable PeripheralBusElement> lookup(
            Map<BlockPos, PeripheralBusElement> entities) {
        Map<BlockPos, PeripheralBusElement> snap = new HashMap<>(entities);
        return snap::get;
    }
}
