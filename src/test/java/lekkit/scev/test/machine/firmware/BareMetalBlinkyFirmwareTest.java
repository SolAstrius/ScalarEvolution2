/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.machine.firmware;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import lekkit.scev.machine.firmware.BareMetalBlinkyFirmware;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Domain-specific invariants for {@link BareMetalBlinkyFirmware}. Generic
 * shape checks live in {@code FirmwareInvariantTest}.
 */
class BareMetalBlinkyFirmwareTest {

    @BeforeAll
    static void bootstrap() { Bootstrap.bootStrap(); }

    @Test
    @DisplayName("blinky.bin on the classpath is exactly 64 bytes (16 rv32im instructions × 4)")
    void assetIsSixtyFourBytes() throws Exception {
        // Load-bearing invariant of the hand-assembled binary: the entire
        // blinky is 16 rv32im instructions. Silent drift here would
        // indicate a corrupted jar or a rebuild that didn't match
        // assemble_blinky.py. No other firmware has a size constraint
        // tight enough to test, so this check only fits here.
        try (InputStream in = BareMetalBlinkyFirmwareTest.class
                .getResourceAsStream("/assets/scev/firmware/blinky.bin")) {
            assertNotNull(in, "blinky.bin missing from jar classpath");
            byte[] bytes = in.readAllBytes();
            assertEquals(64, bytes.length,
                    "expected 16 rv32im instructions × 4 bytes = 64; got " + bytes.length);
        }
    }
}
