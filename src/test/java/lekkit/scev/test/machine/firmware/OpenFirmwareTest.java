/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.machine.firmware;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import lekkit.scev.machine.firmware.OpenFirmware;
import lekkit.scev.machine.firmware.ScevFirmware;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins down {@link OpenFirmware} — the OpenSBI + U-Boot "power-user
 * firmware" option. Single BOOTROM payload loading {@code fw_payload.bin}.
 */
class OpenFirmwareTest {

    @BeforeAll
    static void bootstrap() { Bootstrap.bootStrap(); }

    @Test
    @DisplayName("Single BOOTROM payload: fw_payload.bin")
    void singlePayload() {
        List<ScevFirmware.Payload> p = OpenFirmware.INSTANCE.payloads();
        assertEquals(1, p.size());
        assertEquals(ScevFirmware.Payload.Kind.BOOTROM, p.get(0).kind());
        assertEquals("fw_payload.bin", p.get(0).asset(),
                "fw_payload.bin is the combined OpenSBI + U-Boot image; "
                        + "this is what lets a user drop to the U-Boot shell "
                        + "and run custom boot commands.");
    }

    @Test
    @DisplayName("No kernel cmdline (firmware doesn't know the kernel)")
    void noCmdline() {
        // U-Boot assembles its own bootargs via uEnv.txt / extlinux.conf /
        // distro_bootcmd at runtime; we don't pre-set one from Java.
        assertNull(OpenFirmware.INSTANCE.cmdlineAppend());
    }

    @Test
    @DisplayName("Low RAM floor (default 64 MiB)")
    void lowRamFloor() {
        assertEquals(64, OpenFirmware.INSTANCE.minRamMb(),
                "U-Boot by itself is a handful of MB; no reason for a Linux-sized floor.");
    }
}
