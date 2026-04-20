/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.machine.firmware;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import lekkit.scev.machine.firmware.OpenSbiFirmware;
import lekkit.scev.machine.firmware.ScevFirmware;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins down {@link OpenSbiFirmware}. The "bring your own kernel" building
 * block: BOOTROM only, no kernel payload, no RAM bump.
 */
class OpenSbiFirmwareTest {

    @BeforeAll
    static void bootstrap() { Bootstrap.bootStrap(); }

    @Test
    @DisplayName("Single BOOTROM payload: fw_jump.bin")
    void singlePayload() {
        List<ScevFirmware.Payload> p = OpenSbiFirmware.INSTANCE.payloads();
        assertEquals(1, p.size());
        assertEquals(ScevFirmware.Payload.Kind.BOOTROM, p.get(0).kind());
        assertEquals("fw_jump.bin", p.get(0).asset());
    }

    @Test
    @DisplayName("No kernel cmdline contribution (bootrom-only)")
    void noCmdline() {
        // Without a KERNEL payload there's no reason to pre-set fbcon /
        // serial routing — the kernel (wherever it comes from) provides
        // its own cmdline, or the user attaches a MachineSpec.KernelSpec
        // with custom cmdline.
        assertNull(OpenSbiFirmware.INSTANCE.cmdlineAppend());
    }

    @Test
    @DisplayName("Low RAM floor (default 64 MiB) — no initramfs to unpack")
    void lowRamFloor() {
        assertEquals(64, OpenSbiFirmware.INSTANCE.minRamMb(),
                "OpenSBI alone is ~100 KB of code; no kernel means no initramfs "
                        + "to unpack means no 128 MiB Linux floor needed.");
    }

    @Test
    @DisplayName("displayName is non-empty")
    void name() {
        assertNotNull(OpenSbiFirmware.INSTANCE.displayName());
        assertFalse(OpenSbiFirmware.INSTANCE.displayName().getString().isEmpty());
    }
}
