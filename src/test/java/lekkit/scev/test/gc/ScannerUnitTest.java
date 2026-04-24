/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.gc;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import lekkit.scev.machine.MachineSpec;
import lekkit.scev.server.MachineManager;
import lekkit.scev.server.MachineState;
import lekkit.scev.server.gc.ScanContext;
import lekkit.scev.server.gc.scanners.BlockEntityScanner;
import lekkit.scev.server.gc.scanners.EntityScanner;
import lekkit.scev.server.gc.scanners.PlayerInventoryScanner;
import lekkit.scev.server.gc.scanners.RunningMachineScanner;
import lekkit.scev.test.machine.FakeMachineBackend;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the built-in scanners. Two categories:
 *
 * <ul>
 *   <li><b>Null-server safety.</b> Player/BlockEntity/Entity scanners need a
 *       {@link net.minecraft.server.MinecraftServer} to walk world state.
 *       In the event a scanner runs with a {@code null} server (test harness
 *       pre-dedicated-server, or edge case), they must no-op gracefully
 *       rather than crash. This is the invariant that lets unit-testing
 *       other GC paths work at all.</li>
 *   <li><b>RunningMachineScanner integration.</b> Exercises the full
 *       {@link MachineManager#getActiveUuids()} path via
 *       {@link FakeMachineBackend}. Verifies that currently-running
 *       machines always appear in the live set.</li>
 * </ul>
 *
 * <p>Full end-to-end tests for the three world-scanning scanners live in
 * GameTests where a real {@code ServerLevel} is available.
 */
class ScannerUnitTest {

    @BeforeEach
    void swapInFakeBackend() {
        MachineManager.setBackendFactory(FakeMachineBackend::new);
    }

    @AfterEach
    void tearDown() {
        MachineManager.finishAllMachines();
        MachineManager.setBackendFactory(null);
    }

    @Test
    @DisplayName("PlayerInventoryScanner: null server → no-op, no exception")
    void playerNullServerNoOp() {
        ScanContext ctx = new ScanContext(null);
        new PlayerInventoryScanner().scan(ctx);
        assertTrue(ctx.liveUuids().isEmpty());
    }

    @Test
    @DisplayName("BlockEntityScanner: null server → no-op")
    void blockEntityNullServerNoOp() {
        ScanContext ctx = new ScanContext(null);
        new BlockEntityScanner().scan(ctx);
        assertTrue(ctx.liveUuids().isEmpty());
    }

    @Test
    @DisplayName("EntityScanner: null server → no-op")
    void entityNullServerNoOp() {
        ScanContext ctx = new ScanContext(null);
        new EntityScanner().scan(ctx);
        assertTrue(ctx.liveUuids().isEmpty());
    }

    @Test
    @DisplayName("RunningMachineScanner reports every live machine's UUID")
    void runningMachinesReported() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        MachineState sa = MachineManager.createMachineState(
                MachineSpec.builder(a).memMb(64).build());
        MachineState sb = MachineManager.createMachineState(
                MachineSpec.builder(b).memMb(64).build());
        assertNotNull(sa);
        assertNotNull(sb);

        ScanContext ctx = new ScanContext(null);
        new RunningMachineScanner().scan(ctx);

        assertTrue(ctx.liveUuids().contains(a),
                "active machine A must appear in live set — otherwise GC would "
                        + "delete its disk image while it's running");
        assertTrue(ctx.liveUuids().contains(b));
        assertEquals(2, ctx.liveUuids().size());
    }

    @Test
    @DisplayName("RunningMachineScanner: no machines → empty set")
    void noMachinesEmpty() {
        ScanContext ctx = new ScanContext(null);
        new RunningMachineScanner().scan(ctx);
        assertTrue(ctx.liveUuids().isEmpty());
    }

    @Test
    @DisplayName("RunningMachineScanner reflects live changes: removed machine drops out")
    void destroyedMachineNoLongerReported() {
        UUID a = UUID.randomUUID();
        MachineManager.createMachineState(MachineSpec.builder(a).memMb(64).build());

        ScanContext before = new ScanContext(null);
        new RunningMachineScanner().scan(before);
        assertTrue(before.liveUuids().contains(a));

        MachineManager.destroyMachineState(a);

        ScanContext after = new ScanContext(null);
        new RunningMachineScanner().scan(after);
        assertFalse(after.liveUuids().contains(a),
                "destroyed machines must drop out of the live set so their "
                        + "image becomes GC-eligible");
    }
}
