/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.machine

import java.nio.file.Files
import java.nio.file.Path

/**
 * A tiny hand-assembled RV64 program that proves the CPU is executing
 * code. We ship this instead of relying on real firmware (which is a
 * much bigger engineering effort — OpenSBI + kernel + rootfs).
 *
 * Without this, the CPU starts at reset PC 0x80000000 which is
 * zero-initialised RAM, decodes 0x00000000 (the canonical illegal
 * instruction), traps forever, and the user sees a frozen
 * [BootSplash] "POWER ON" screen with no further activity.
 *
 * The program writes a magic byte ([MAGIC_VALUE]) to a known RAM
 * address ([MAGIC_ADDR]) and then loops forever. E2E tests read
 * [MAGIC_ADDR] through [MachineBackend.readMemory] and assert the
 * value is there — proof that the CPU ran.
 */
object DemoBootrom {
    /** The magic byte the demo writes. */
    const val MAGIC_VALUE: Byte = 0x42

    /** RAM address the demo writes [MAGIC_VALUE] to. */
    const val MAGIC_ADDR: Long = 0x80010000L

    /** Reset vector on RVVM by default. This is where the CPU starts. */
    const val RESET_ADDR: Long = 0x80000000L

    /**
     * Hand-assembled RV64 machine code. 16 bytes, four instructions.
     *
     * Bytes are little-endian 32-bit instructions.
     */
    @JvmField
    val BYTES: ByteArray = byteArrayOf(
        // auipc t1, 0x10   ->  t1 = pc + 0x10000 = 0x80010000
        // Encoding: imm[31:12]=0x10, rd=6 (t1), opcode=0x17 -> 0x00010317
        0x17, 0x03, 0x01, 0x00,

        // addi t0, zero, 0x42   ->  t0 = 0x42
        // Encoding: imm[11:0]=0x42, rs1=0, funct3=0, rd=5 (t0), opcode=0x13 -> 0x04200293
        0x93.toByte(), 0x02, 0x20, 0x04,

        // sw t0, 0(t1)   ->  *(t1 + 0) = t0
        // Encoding: imm=0, rs2=5 (t0), rs1=6 (t1), funct3=2 (SW), opcode=0x23 -> 0x00532023
        0x23, 0x20, 0x53, 0x00,

        // jal zero, 0    ->  j . (infinite loop at current PC)
        // Encoding: imm=0, rd=0, opcode=0x6F -> 0x0000006F
        0x6F, 0x00, 0x00, 0x00,
    )

    /**
     * Install the demo bootrom into [backend]'s RAM at the reset
     * vector via [MachineBackend.readMemory] DMA. Fast (no file I/O)
     * but doesn't invalidate the RVVM JIT cache — prefer
     * [writeToTempFile] + a proper bootrom-loading API when available.
     *
     * Used by the fake backend in tests. Does nothing if the backend
     * doesn't support DMA (returns null from readMemory) or the RAM at
     * the reset vector already has non-zero bytes (someone else owns
     * it).
     *
     * @return true if the demo was installed, false otherwise.
     */
    @JvmStatic
    fun installIfRamEmpty(backend: MachineBackend): Boolean {
        val ram = backend.readMemory(RESET_ADDR, BYTES.size.toLong()) ?: return false
        ram.rewind()
        for (i in BYTES.indices) {
            if (ram.get().toInt() != 0) return false
        }
        ram.rewind()
        ram.put(BYTES)
        return true
    }

    /**
     * Write the 16-byte bootrom to a fresh temp file and return its
     * path. Callers use this to feed a "real" bootrom-loader API
     * (RVVM's `rvvm_load_bootrom` takes a path, not a buffer).
     *
     * The returned file is marked `deleteOnExit` so the JVM cleans up
     * on shutdown — no orphan files accumulate across sessions.
     */
    @JvmStatic
    @Throws(java.io.IOException::class)
    fun writeToTempFile(): Path {
        val p = Files.createTempFile("scev-demo-bootrom-", ".bin")
        p.toFile().deleteOnExit()
        Files.write(p, BYTES)
        return p
    }
}
