/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.machine

/**
 * A tiny hand-assembled RV64 program that proves the kernel-loading
 * pipeline works end-to-end. It ships as the default `Image` under
 * `assets/scev/firmware/` so a fresh install boots all the way through
 * OpenSBI into "kernel code" without requiring a multi-hundred-megabyte
 * Buildroot artifact in the mod jar.
 *
 * Two observable side effects let E2E tests assert the whole pipeline
 * ran:
 * 1. Writes [MAGIC_VALUE] to [MAGIC_ADDR] (load_addr + 0x100 =
 *    0x80200100 — deliberately close to the stub so we don't collide
 *    with OpenSBI's workspace further up in RAM).
 * 2. Writes [UART_CHAR] ('Y') to the NS16550A UART at 0x10000000. RVVM's
 *    chardev-term binding pipes UART output to host stdout, so the
 *    server log records it.
 *
 * Users who want the full interactive Linux experience drop their own
 * `Image` into `./scev/assets/Image` — the user-wins rule in
 * [lekkit.scev.server.FirmwareAssets] preserves it. See
 * `docs/BUILDROOT.md`.
 */
object KernelStub {
    /** Default kernel load offset for RV64 (RVVM's `rvvm_load_kernel` puts us at mem_base + 0x200000). */
    const val LOAD_ADDR: Long = 0x80200000L

    /** RAM address the stub writes [MAGIC_VALUE] to. 256 bytes past the stub itself. */
    const val MAGIC_ADDR: Long = LOAD_ADDR + 0x100L

    /** Magic byte the stub writes. Deliberately different from [DemoBootrom.MAGIC_VALUE] (0x42). */
    const val MAGIC_VALUE: Byte = 0x5A

    /** ASCII 'Y' — the stub pushes this out the NS16550A UART so the server log records it. */
    const val UART_CHAR: Byte = 0x59

    /**
     * Hand-assembled RV64 machine code. 32 bytes, eight instructions.
     * Little-endian 32-bit instructions.
     *
     * These bytes are the binary contents of
     * `assets/scev/firmware/Image` when the mod ships with the stub. A
     * real Buildroot kernel dropped into `./scev/assets/Image`
     * overrides them silently (via the user-wins rule in
     * `FirmwareAssets`).
     */
    @JvmField
    val BYTES: ByteArray = byteArrayOf(
        // auipc t0, 0x0  ->  t0 = pc = 0x80200000 (at run time)
        // 0x00000297
        0x97.toByte(), 0x02, 0x00, 0x00,

        // addi t0, t0, 0x100  ->  t0 += 0x100 -> 0x80200100 (MAGIC_ADDR)
        // 0x10028293
        0x93.toByte(), 0x82.toByte(), 0x02, 0x10,

        // addi t1, zero, 0x5A  ->  t1 = 0x5A (MAGIC_VALUE)
        // 0x05A00313
        0x13, 0x03, 0xA0.toByte(), 0x05,

        // sw t1, 0(t0)  ->  *MAGIC_ADDR = 0x5A
        // 0x00632023
        0x23, 0x20, 0x63, 0x00,

        // lui t2, 0x10000  ->  t2 = 0x10000000 (NS16550A UART base)
        // 0x100003B7
        0xB7.toByte(), 0x03, 0x00, 0x10,

        // addi t3, zero, 0x59  ->  t3 = 0x59 (UART_CHAR = 'Y')
        // 0x05900E13
        0x13, 0x0E, 0x90.toByte(), 0x05,

        // sb t3, 0(t2)  ->  UART TX <- 'Y'
        // 0x01C38023
        0x23, 0x80.toByte(), 0xC3.toByte(), 0x01,

        // jal zero, 0  ->  j .
        // 0x0000006F
        0x6F, 0x00, 0x00, 0x00,
    )

    /** Canonical asset name under `/assets/scev/firmware/`. */
    const val ASSET_NAME: String = "Image"
}
