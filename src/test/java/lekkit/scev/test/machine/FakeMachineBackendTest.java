/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.machine;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import lekkit.rvvm.HIDKeyboard;
import lekkit.scev.machine.MachineSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Self-test for {@link FakeMachineBackend}. Confirms the fake's semantics
 * match the backend contract so the tests that USE the fake can trust it.
 *
 * <p>This is a minimum dignity check — it doesn't substitute for the real
 * RVVM backend tests, but it does lock down the fake's behaviour so
 * downstream E2E tests have a reliable substrate.
 */
class FakeMachineBackendTest {

    @Test
    @DisplayName("Initialize succeeds exactly once; subsequent calls return false")
    void initializeOnce() {
        FakeMachineBackend b = new FakeMachineBackend();
        MachineSpec spec = MachineSpec.builder(UUID.randomUUID()).memMb(64).build();
        assertTrue(b.initialize(spec));
        assertFalse(b.initialize(spec), "second initialize must return false");
        assertTrue(b.isValid());
    }

    @Test
    @DisplayName("Closed backend rejects all operations")
    void closedRejectsAll() {
        FakeMachineBackend b = new FakeMachineBackend();
        b.initialize(MachineSpec.builder(UUID.randomUUID()).memMb(64).build());
        b.close();
        assertFalse(b.isValid());
        assertFalse(b.start());
        assertFalse(b.pause());
        assertFalse(b.reset());
        assertNull(b.framebuffer());
        assertNull(b.keyboard());
        assertNull(b.mouse());
        assertNull(b.gpio());
    }

    @Test
    @DisplayName("Spec display -> framebuffer present; no display -> framebuffer null")
    void displayOnlyIfSpecRequests() {
        FakeMachineBackend withDisplay = new FakeMachineBackend();
        withDisplay.initialize(MachineSpec.builder(UUID.randomUUID()).memMb(64).defaultDisplay().build());
        assertNotNull(withDisplay.framebuffer());
        assertEquals(640, withDisplay.framebuffer().width());
        assertEquals(480, withDisplay.framebuffer().height());

        FakeMachineBackend noDisplay = new FakeMachineBackend();
        noDisplay.initialize(MachineSpec.builder(UUID.randomUUID()).memMb(64).build());
        assertNull(noDisplay.framebuffer());
    }

    @Test
    @DisplayName("Spec GPIO -> GpioDevice present; no GPIO -> null")
    void gpioOnlyIfSpecRequests() {
        FakeMachineBackend with = new FakeMachineBackend();
        with.initialize(MachineSpec.builder(UUID.randomUUID()).memMb(64).hasGpio(true).build());
        assertNotNull(with.gpio());

        FakeMachineBackend without = new FakeMachineBackend();
        without.initialize(MachineSpec.builder(UUID.randomUUID()).memMb(64).build());
        assertNull(without.gpio());
    }

    @Test
    @DisplayName("Keyboard / mouse always present (HID is mandatory)")
    void hidAlwaysPresent() {
        FakeMachineBackend b = new FakeMachineBackend();
        b.initialize(MachineSpec.builder(UUID.randomUUID()).memMb(64).build());
        assertNotNull(b.keyboard());
        assertNotNull(b.mouse());
    }

    @Test
    @DisplayName("Lifecycle ops are recorded in order")
    void lifecycleOpsRecorded() {
        FakeMachineBackend b = new FakeMachineBackend();
        b.initialize(MachineSpec.builder(UUID.randomUUID()).memMb(64).build());
        b.start();
        b.pause();
        b.start();
        b.reset();
        b.close();
        assertEquals(
                java.util.List.of("initialize", "start", "pause", "start", "reset", "close"),
                b.lifecycleOps);
    }

    @Test
    @DisplayName("Keyboard.press / release record the scancode")
    void keyboardRecordsOps() {
        FakeMachineBackend b = new FakeMachineBackend();
        b.initialize(MachineSpec.builder(UUID.randomUUID()).memMb(64).build());
        b.keyboard().press(HIDKeyboard.HID_KEY_A);
        b.keyboard().release(HIDKeyboard.HID_KEY_A);
        assertEquals(java.util.List.of("press:4", "release:4"), b.keyboardRaw().ops);
    }

    @Test
    @DisplayName("GPIO readPins returns masked low 6 bits")
    void gpioMaskedLow6Bits() {
        FakeMachineBackend b = new FakeMachineBackend();
        b.initialize(MachineSpec.builder(UUID.randomUUID()).memMb(64).hasGpio(true).build());
        b.gpioRaw().readValue = 0xFFFF_FFFF;
        assertEquals(0x3F, b.gpio().readPins());
    }
}
