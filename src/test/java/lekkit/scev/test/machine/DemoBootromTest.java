/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.machine;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.util.UUID;
import lekkit.scev.machine.DemoBootrom;
import lekkit.scev.machine.MachineSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Locks the demo-bootrom contract in place. The bytes here ARE the CPU's
 * first instructions when there's no real firmware — get them wrong and
 * the user is back to a frozen "POWER ON" screen.
 */
class DemoBootromTest {

    @Test
    @DisplayName("BYTES is exactly 16 bytes (4 RV64 instructions)")
    void sizeIsFourInstructions() {
        assertEquals(16, DemoBootrom.BYTES.length, "bootrom must be 4 x 4-byte RV64 instructions");
    }

    @Test
    @DisplayName("Instruction 1: auipc t1, 0x10 -> encoding 0x00010317")
    void instruction1AuipcT1() {
        int encoded = readInstruction(0);
        assertEquals(0x00010317, encoded,
                "auipc t1, 0x10 encoding drift: got 0x" + Integer.toHexString(encoded));
    }

    @Test
    @DisplayName("Instruction 2: addi t0, zero, 0x42 -> encoding 0x04200293")
    void instruction2AddiT0() {
        assertEquals(0x04200293, readInstruction(4));
    }

    @Test
    @DisplayName("Instruction 3: sw t0, 0(t1) -> encoding 0x00532023")
    void instruction3SwT0T1() {
        assertEquals(0x00532023, readInstruction(8));
    }

    @Test
    @DisplayName("Instruction 4: jal zero, 0 -> encoding 0x0000006F (infinite loop)")
    void instruction4JalZero() {
        assertEquals(0x0000006F, readInstruction(12));
    }

    @Test
    @DisplayName("Addresses match the contract: RESET=0x80000000, MAGIC=0x80010000")
    void addressesStable() {
        assertEquals(0x80000000L, DemoBootrom.RESET_ADDR);
        assertEquals(0x80010000L, DemoBootrom.MAGIC_ADDR);
        assertEquals(0x10000L, DemoBootrom.MAGIC_ADDR - DemoBootrom.RESET_ADDR,
                "auipc t1, 0x10 computes 0x10 << 12 = 0x10000 from PC; MAGIC_ADDR must match");
    }

    @Test
    @DisplayName("Install: writes bootrom into empty RAM via backend.readMemory")
    void installsWhenRamEmpty() {
        FakeMachineBackend b = new FakeMachineBackend();
        b.initialize(MachineSpec.builder(UUID.randomUUID()).memMb(64).build());
        assertTrue(DemoBootrom.installIfRamEmpty(b));

        ByteBuffer ram = b.readMemory(DemoBootrom.RESET_ADDR, 16);
        assertNotNull(ram);
        byte[] got = new byte[16];
        ram.rewind();
        ram.get(got);
        assertArrayEquals(DemoBootrom.BYTES, got);
    }

    @Test
    @DisplayName("Install: skips if RAM already has content (real firmware wins)")
    void skipsWhenRamNonEmpty() {
        FakeMachineBackend b = new FakeMachineBackend();
        b.initialize(MachineSpec.builder(UUID.randomUUID()).memMb(64).build());
        // Pre-populate RAM with "real firmware" — any non-zero byte.
        ByteBuffer ram = b.readMemory(DemoBootrom.RESET_ADDR, 16);
        ram.put(0, (byte) 0xAB);

        assertFalse(DemoBootrom.installIfRamEmpty(b),
                "installIfRamEmpty must return false when RAM already has content");
        // RAM should be untouched (still 0xAB at offset 0).
        ram = b.readMemory(DemoBootrom.RESET_ADDR, 16);
        assertEquals((byte) 0xAB, ram.get(0), "existing firmware must not be overwritten");
    }

    @Test
    @DisplayName("Install: no-op on a closed backend (readMemory returns null)")
    void noOpOnClosedBackend() {
        FakeMachineBackend b = new FakeMachineBackend();
        b.initialize(MachineSpec.builder(UUID.randomUUID()).memMb(64).build());
        b.close();
        assertFalse(DemoBootrom.installIfRamEmpty(b));
    }

    /** Read a 32-bit little-endian instruction from BYTES at the given offset. */
    private static int readInstruction(int offset) {
        return (DemoBootrom.BYTES[offset]      & 0xFF)
            | ((DemoBootrom.BYTES[offset + 1] & 0xFF) <<  8)
            | ((DemoBootrom.BYTES[offset + 2] & 0xFF) << 16)
            | ((DemoBootrom.BYTES[offset + 3] & 0xFF) << 24);
    }
}
