/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.machine;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import lekkit.scev.machine.MachineSpec;
import lekkit.scev.server.MachineManager;
import lekkit.scev.server.MachineState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Drives the full {@link MachineState} / {@link MachineManager} lifecycle
 * through a {@link FakeMachineBackend}. This is the E2E test that replaces
 * "run RVVM for real and see what happens" — it exercises every code path
 * in the manager without requiring librvvm.
 */
class MachineLifecycleTest {

    @BeforeEach
    void swapInFake() {
        MachineManager.setBackendFactory(FakeMachineBackend::new);
    }

    @AfterEach
    void teardown() {
        // Clean up any state left over (e.g. if a test created but didn't destroy).
        MachineManager.finishAllMachines();
        MachineManager.setBackendFactory(null); // reset to default
    }

    @Test
    @DisplayName("createMachineState returns a live MachineState registered in the manager")
    void createAndLookup() {
        UUID uuid = UUID.randomUUID();
        MachineSpec spec = MachineSpec.builder(uuid).memMb(64).defaultDisplay().build();
        MachineState state = MachineManager.createMachineState(spec);
        assertNotNull(state);
        assertEquals(uuid, state.getUUID());
        assertSame(state, MachineManager.getMachineState(uuid));
        assertNotNull(state.getDisplay());
        assertEquals(640, state.getDisplay().width());
    }

    @Test
    @DisplayName("Re-creating the same UUID returns null (refuses duplicate)")
    void duplicateCreateRefused() {
        UUID uuid = UUID.randomUUID();
        MachineSpec spec = MachineSpec.builder(uuid).memMb(64).build();
        assertNotNull(MachineManager.createMachineState(spec));
        assertNull(MachineManager.createMachineState(spec), "duplicate UUID should be rejected");
    }

    @Test
    @DisplayName("Power on / pause / resume / reset / close lifecycle")
    void fullLifecycle() {
        UUID uuid = UUID.randomUUID();
        MachineSpec spec = MachineSpec.builder(uuid).memMb(64).hasGpio(true).defaultDisplay().build();
        MachineState state = MachineManager.createMachineState(spec);
        FakeMachineBackend fake = (FakeMachineBackend) state.getBackend();

        assertTrue(state.start());
        assertTrue(state.isPowered(), "after start, state must report powered");
        assertTrue(fake.isRunning());

        state.pause();
        // pause halts the emulation thread but does NOT "power off". The
        // backend is still "running" (logically powered); only close() flips it.
        assertTrue(fake.isRunning(), "pause must not flip isRunning — that would prevent resume");
        assertTrue(fake.lifecycleOps.contains("pause"));

        state.unpause();
        // unpause calls tryResume, which re-starts because paused=false && running=true.
        long starts = fake.lifecycleOps.stream().filter("start"::equals).count();
        assertTrue(starts >= 2, "unpause should have issued a second start; ops=" + fake.lifecycleOps);

        assertTrue(state.reset());
        assertTrue(fake.lifecycleOps.contains("reset"));

        state.destroy();
        assertFalse(state.isValid());
        assertTrue(fake.lifecycleOps.contains("close"));
    }

    @Test
    @DisplayName("destroyMachineState removes from registry and closes the backend")
    void destroyRemoves() {
        UUID uuid = UUID.randomUUID();
        MachineSpec spec = MachineSpec.builder(uuid).memMb(64).build();
        MachineState state = MachineManager.createMachineState(spec);
        FakeMachineBackend fake = (FakeMachineBackend) state.getBackend();

        MachineManager.destroyMachineState(uuid);
        assertNull(MachineManager.getMachineState(uuid));
        assertTrue(fake.lifecycleOps.contains("close"));
    }

    @Test
    @DisplayName("finishAllMachines pauses then destroys everything")
    void finishAll() {
        MachineState a = MachineManager.createMachineState(MachineSpec.builder(UUID.randomUUID()).memMb(64).build());
        MachineState b = MachineManager.createMachineState(MachineSpec.builder(UUID.randomUUID()).memMb(64).build());
        a.start();
        b.start();

        MachineManager.finishAllMachines();
        assertNull(MachineManager.getMachineState(a.getUUID()));
        assertNull(MachineManager.getMachineState(b.getUUID()));
    }

    @Test
    @DisplayName("pauseAllMachines / unpauseAllMachines dispatch to every state")
    void pauseAllUnpauseAll() {
        MachineState a = MachineManager.createMachineState(MachineSpec.builder(UUID.randomUUID()).memMb(64).build());
        MachineState b = MachineManager.createMachineState(MachineSpec.builder(UUID.randomUUID()).memMb(64).build());
        FakeMachineBackend fakeA = (FakeMachineBackend) a.getBackend();
        FakeMachineBackend fakeB = (FakeMachineBackend) b.getBackend();
        a.start();
        b.start();

        MachineManager.pauseAllMachines();
        assertTrue(fakeA.lifecycleOps.contains("pause"));
        assertTrue(fakeB.lifecycleOps.contains("pause"));

        MachineManager.unpauseAllMachines();
        long aStarts = fakeA.lifecycleOps.stream().filter("start"::equals).count();
        long bStarts = fakeB.lifecycleOps.stream().filter("start"::equals).count();
        assertTrue(aStarts >= 2);
        assertTrue(bStarts >= 2);
    }

    @Test
    @DisplayName("Backend without display -> state.getDisplay() is null")
    void noDisplayReturnsNull() {
        MachineSpec spec = MachineSpec.builder(UUID.randomUUID()).memMb(64).build();
        MachineState state = MachineManager.createMachineState(spec);
        assertNull(state.getDisplay());
    }
}
