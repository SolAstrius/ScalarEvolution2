/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.machine;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A tiny hand-assembled RV64 program that proves the CPU is executing code.
 * We ship this instead of relying on real firmware (which is a much bigger
 * engineering effort — OpenSBI + kernel + rootfs).
 *
 * <p>Without this, the CPU starts at reset PC 0x80000000 which is
 * zero-initialised RAM, decodes 0x00000000 (the canonical illegal
 * instruction), traps forever, and the user sees a frozen
 * {@link BootSplash} "POWER ON" screen with no further activity.
 *
 * <p>The program writes a magic byte ({@link #MAGIC_VALUE}) to a known RAM
 * address ({@link #MAGIC_ADDR}) and then loops forever. E2E tests read
 * {@link #MAGIC_ADDR} through {@link MachineBackend#readMemory} and assert
 * the value is there — proof that the CPU ran.
 *
 * <p>Program disassembly (addresses are at reset vector 0x80000000):
 * <pre>
 *   0x80000000: auipc t1, 0x10            ; t1 = pc + 0x10000 = 0x80010000
 *   0x80000004: addi  t0, zero, 0x42      ; t0 = 0x42
 *   0x80000008: sw    t0, 0(t1)           ; *t1 = 0x00000042
 *   0x8000000C: jal   zero, 0             ; j .  (infinite loop)
 * </pre>
 *
 * <p>AUIPC is PC-relative, so we don't have to worry about RV64's
 * sign-extended LUI producing upper-half addresses. The magic write lands
 * in RAM deterministically no matter where the bootrom happened to be
 * placed (as long as reset PC is at the RAM base 0x80000000).
 *
 * <p>If RVVM ever changes the default reset PC, we'd need to re-assemble.
 * Locked in place by {@code DemoBootromTest}.
 */
public final class DemoBootrom {
    /** The magic byte the demo writes. */
    public static final byte MAGIC_VALUE = 0x42;

    /** RAM address the demo writes {@link #MAGIC_VALUE} to. */
    public static final long MAGIC_ADDR = 0x80010000L;

    /** Reset vector on RVVM by default. This is where the CPU starts. */
    public static final long RESET_ADDR = 0x80000000L;

    /**
     * Hand-assembled RV64 machine code. 16 bytes, four instructions.
     *
     * <p>Bytes are little-endian 32-bit instructions.
     */
    public static final byte[] BYTES = new byte[] {
            // auipc t1, 0x10   ->  t1 = pc + 0x10000 = 0x80010000
            // Encoding: imm[31:12]=0x10, rd=6 (t1), opcode=0x17
            //           -> 0x00010317
            (byte) 0x17, (byte) 0x03, (byte) 0x01, (byte) 0x00,

            // addi t0, zero, 0x42   ->  t0 = 0x42
            // Encoding: imm[11:0]=0x42, rs1=0 (zero), funct3=0, rd=5 (t0), opcode=0x13
            //           -> 0x04200293
            (byte) 0x93, (byte) 0x02, (byte) 0x20, (byte) 0x04,

            // sw t0, 0(t1)   ->  *(t1 + 0) = t0
            // Encoding: imm=0 (split), rs2=5 (t0), rs1=6 (t1), funct3=2 (SW), opcode=0x23
            //           -> 0x00532023
            (byte) 0x23, (byte) 0x20, (byte) 0x53, (byte) 0x00,

            // jal zero, 0    ->  j . (infinite loop at current PC)
            // Encoding: imm=0 (all bits), rd=0 (zero), opcode=0x6F
            //           -> 0x0000006F
            (byte) 0x6F, (byte) 0x00, (byte) 0x00, (byte) 0x00,
    };

    private DemoBootrom() {}

    /**
     * Install the demo bootrom into {@code backend}'s RAM at the reset
     * vector via {@link MachineBackend#readMemory} DMA. Fast (no file I/O)
     * but doesn't invalidate the RVVM JIT cache — prefer
     * {@link #writeToTempFile()} + a proper bootrom-loading API when
     * available.
     *
     * <p>Used by the fake backend in tests. Does nothing if the backend
     * doesn't support DMA (returns null from readMemory) or the RAM at the
     * reset vector already has non-zero bytes (someone else owns it).
     *
     * @return true if the demo was installed, false otherwise.
     */
    public static boolean installIfRamEmpty(MachineBackend backend) {
        ByteBuffer ram = backend.readMemory(RESET_ADDR, BYTES.length);
        if (ram == null) return false;
        ram.rewind();
        for (int i = 0; i < BYTES.length; i++) {
            if (ram.get() != 0) return false;
        }
        ram.rewind();
        ram.put(BYTES);
        return true;
    }

    /**
     * Write the 16-byte bootrom to a fresh temp file and return its path.
     * Callers use this to feed a "real" bootrom-loader API (RVVM's
     * {@code rvvm_load_bootrom} takes a path, not a buffer).
     *
     * <p>The returned file is marked {@code deleteOnExit} so the JVM cleans
     * up on shutdown — no orphan files accumulate across sessions.
     */
    public static Path writeToTempFile() throws IOException {
        Path p = Files.createTempFile("scev-demo-bootrom-", ".bin");
        p.toFile().deleteOnExit();
        Files.write(p, BYTES);
        return p;
    }
}
