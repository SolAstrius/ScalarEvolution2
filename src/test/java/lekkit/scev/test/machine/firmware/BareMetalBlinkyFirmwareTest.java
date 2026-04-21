/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.machine.firmware;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.util.List;
import lekkit.scev.machine.firmware.BareMetalBlinkyFirmware;
import lekkit.scev.machine.firmware.ScevFirmware;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins down the bare-metal blinky firmware — single BOOTROM, no kernel,
 * no cmdline contribution, 1 MiB RAM floor. Plus a sanity check that
 * the bundled {@code blinky.bin} actually lives in the jar classpath at
 * the declared asset path.
 */
class BareMetalBlinkyFirmwareTest {

    @BeforeAll
    static void bootstrap() { Bootstrap.bootStrap(); }

    @Test
    @DisplayName("Single BOOTROM payload: blinky.bin")
    void singlePayload() {
        List<ScevFirmware.Payload> p = BareMetalBlinkyFirmware.INSTANCE.payloads();
        assertEquals(1, p.size());
        assertEquals(ScevFirmware.Payload.Kind.BOOTROM, p.get(0).kind());
        assertEquals("blinky.bin", p.get(0).asset());
    }

    @Test
    @DisplayName("No kernel cmdline contribution (no kernel to route)")
    void noCmdline() {
        assertNull(BareMetalBlinkyFirmware.INSTANCE.cmdlineAppend());
    }

    @Test
    @DisplayName("Minimal RAM floor (1 MiB) — program is 64 bytes and touches no data")
    void lowRamFloor() {
        assertEquals(1, BareMetalBlinkyFirmware.INSTANCE.minRamMb(),
                "Blinky itself fits in 64 bytes. 1 MiB is the honest smallest "
                        + "workable floor once sub-MiB machine sizing lands; "
                        + "below that RVVM still aligns to a page and auto-generates "
                        + "an ~1 KiB FDT at the top of RAM.");
    }

    @Test
    @DisplayName("displayName is non-empty")
    void name() {
        assertNotNull(BareMetalBlinkyFirmware.INSTANCE.displayName());
        assertFalse(BareMetalBlinkyFirmware.INSTANCE.displayName().getString().isEmpty());
    }

    @Test
    @DisplayName("blinky.bin is bundled on the classpath at /assets/scev/firmware/")
    void bundledAssetExists() throws Exception {
        try (InputStream in = BareMetalBlinkyFirmwareTest.class
                .getResourceAsStream("/assets/scev/firmware/blinky.bin")) {
            assertNotNull(in, "blinky.bin missing from classpath — jar build must include it");
            byte[] bytes = in.readAllBytes();
            // Exact 64 is a load-bearing invariant of the assembler; changing it
            // requires deliberately re-running assemble_blinky.py and bumping
            // this assertion. A silent drift would indicate a corrupted or
            // truncated jar.
            assertEquals(64, bytes.length,
                    "expected 16 rv32im instructions × 4 bytes = 64; got " + bytes.length);
        }
    }
}
