/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.machine;

/**
 * A tiny hand-assembled RV64 program that proves the kernel-loading pipeline
 * works end-to-end. It ships as the default {@code Image} under
 * {@code assets/scev/firmware/} so a fresh install boots all the way through
 * OpenSBI into "kernel code" without requiring a multi-hundred-megabyte
 * Buildroot artifact in the mod jar.
 *
 * <h2>Why a stub instead of a real kernel</h2>
 * A real Buildroot RV64 Linux kernel with fbcon is ~5 MiB, takes ~30 minutes
 * of toolchain+build time to produce, and tracks upstream versions. That's
 * out of reach for a mod-jar resource. Instead:
 *
 * <ul>
 *   <li>The mod ships this 32-byte stub as {@code Image}. It lands at the
 *       RVVM kernel-offset ({@code mem_base + 0x200000} = 0x80200000 for
 *       RV64) and does two observable things so E2E tests can assert the
 *       whole pipeline ran:
 *       <ol>
 *         <li>Writes a magic byte to {@link #MAGIC_ADDR} (load_addr + 0x100 =
 *             0x80200100 — deliberately close to the stub so we don't
 *             collide with OpenSBI's workspace further up in RAM).</li>
 *         <li>Writes {@link #UART_CHAR} ('Y') to the NS16550A UART at
 *             0x10000000. This is visible in the server log because RVVM's
 *             chardev-term binding pipes UART output to host stdout.</li>
 *       </ol>
 *   </li>
 *   <li>Users who want the full interactive Linux experience drop their
 *       own {@code Image} into {@code ./scev/assets/Image} — the
 *       user-wins rule in {@link lekkit.scev.server.FirmwareAssets}
 *       preserves it. See {@code docs/BUILDROOT.md}.</li>
 * </ul>
 *
 * <h2>Execution flow</h2>
 * <ol>
 *   <li>{@code fw_jump.bin} (OpenSBI only, shipped by the mod) loads at
 *       0x80000000 and is executed at reset.</li>
 *   <li>OpenSBI initializes SBI + traps + PMP, then {@code mret}s to
 *       S-mode at 0x80200000 (its default "next address").</li>
 *   <li>Our stub at 0x80200000 runs in S-mode with the DTB pointer in
 *       {@code a1} (ignored), writes {@link #MAGIC_VALUE} to
 *       {@link #MAGIC_ADDR}, writes 'Y' to the UART, then loops forever.</li>
 * </ol>
 *
 * <h2>Program disassembly (assuming kernel base 0x80200000)</h2>
 * <pre>
 *   0x80200000: auipc t0, 0x0       ; t0 = pc = 0x80200000
 *   0x80200004: addi  t0, t0, 0x100 ; t0 = 0x80200100 (MAGIC_ADDR)
 *   0x80200008: addi  t1, zero, 0x5A ; t1 = 0x5A (MAGIC_VALUE)
 *   0x8020000C: sw    t1, 0(t0)     ; *MAGIC_ADDR = 0x0000005A
 *   0x80200010: lui   t2, 0x10000   ; t2 = 0x10000000 (NS16550A UART)
 *   0x80200014: addi  t3, zero, 0x59 ; t3 = 0x59 ('Y' — UART_CHAR)
 *   0x80200018: sb    t3, 0(t2)     ; UART TX <- 'Y'
 *   0x8020001C: jal   zero, 0       ; j .   (infinite loop)
 * </pre>
 *
 * <p>PC-relative addressing (auipc) makes the stub position-independent,
 * so the bytes work no matter where they end up in RAM (though in practice
 * {@code rvvm_load_kernel} always puts them at {@link #LOAD_ADDR}).
 *
 * <h2>Why write the magic value close to the stub?</h2>
 * OpenSBI's fw_jump.bin uses RAM around {@code load_addr + 0x100000} and
 * beyond as its workspace (DTB at 0x80100000, runtime state around
 * 0x80300000). Writing far from those regions avoids a race where OpenSBI
 * overwrites our magic value between the stub writing it and the GameTest
 * reading it. 0x80200100 is inside the "kernel" region (which OpenSBI
 * knows not to touch) but just past the stub itself.
 *
 * <h2>Contract</h2>
 * If this class's bytes change, update {@code KernelStubTest},
 * {@code linux_kernel_stub_executes} GameTest, AND regenerate the
 * {@code Image} binary under {@code src/main/resources/assets/scev/firmware/}
 * — all three pin the encoding byte-for-byte so a silent regression can't
 * smuggle in an unexecutable {@code Image}.
 */
public final class KernelStub {
    /** Default kernel load offset for RV64 (RVVM's {@code rvvm_load_kernel} puts us at mem_base + 0x200000). */
    public static final long LOAD_ADDR = 0x80200000L;

    /** RAM address the stub writes {@link #MAGIC_VALUE} to. 256 bytes past the stub itself. */
    public static final long MAGIC_ADDR = LOAD_ADDR + 0x100L;

    /** Magic byte the stub writes. Deliberately different from {@link DemoBootrom#MAGIC_VALUE} (0x42). */
    public static final byte MAGIC_VALUE = 0x5A;

    /** ASCII 'Y' — the stub pushes this out the NS16550A UART so the server log records it. */
    public static final byte UART_CHAR = 0x59;

    /**
     * Hand-assembled RV64 machine code. 32 bytes, eight instructions.
     * Little-endian 32-bit instructions.
     *
     * <p>These bytes are the binary contents of {@code assets/scev/firmware/Image}
     * when the mod ships with the stub. A real Buildroot kernel dropped
     * into {@code ./scev/assets/Image} overrides them silently (via the
     * user-wins rule in {@code FirmwareAssets}).
     */
    public static final byte[] BYTES = new byte[] {
            // auipc t0, 0x0  ->  t0 = pc = 0x80200000 (at run time)
            // Encoding: imm[31:12]=0x00000, rd=5 (t0), opcode=0x17 -> 0x00000297
            (byte) 0x97, (byte) 0x02, (byte) 0x00, (byte) 0x00,

            // addi t0, t0, 0x100  ->  t0 += 0x100 -> 0x80200100 (MAGIC_ADDR)
            // Encoding: imm=0x100, rs1=5, funct3=0, rd=5, opcode=0x13 -> 0x10028293
            (byte) 0x93, (byte) 0x82, (byte) 0x02, (byte) 0x10,

            // addi t1, zero, 0x5A  ->  t1 = 0x5A (MAGIC_VALUE)
            // Encoding: imm=0x05A, rs1=0, funct3=0, rd=6, opcode=0x13 -> 0x05A00313
            (byte) 0x13, (byte) 0x03, (byte) 0xA0, (byte) 0x05,

            // sw t1, 0(t0)  ->  *MAGIC_ADDR = 0x5A
            // Encoding: imm=0, rs2=6, rs1=5, funct3=2 (sw), opcode=0x23 -> 0x00632023
            (byte) 0x23, (byte) 0x20, (byte) 0x63, (byte) 0x00,

            // lui t2, 0x10000  ->  t2 = 0x10000000 (NS16550A UART base)
            // Encoding: imm[31:12]=0x10000, rd=7, opcode=0x37 -> 0x100003B7
            (byte) 0xB7, (byte) 0x03, (byte) 0x00, (byte) 0x10,

            // addi t3, zero, 0x59  ->  t3 = 0x59 (UART_CHAR = 'Y')
            // Encoding: imm=0x059, rs1=0, funct3=0, rd=28, opcode=0x13 -> 0x05900E13
            (byte) 0x13, (byte) 0x0E, (byte) 0x90, (byte) 0x05,

            // sb t3, 0(t2)  ->  UART TX <- 'Y'
            // Encoding: imm=0, rs2=28, rs1=7, funct3=0 (sb), opcode=0x23 -> 0x01C38023
            (byte) 0x23, (byte) 0x80, (byte) 0xC3, (byte) 0x01,

            // jal zero, 0  ->  j .
            // Encoding: imm=0, rd=0, opcode=0x6F -> 0x0000006F
            (byte) 0x6F, (byte) 0x00, (byte) 0x00, (byte) 0x00,
    };

    /** Canonical asset name under {@code /assets/scev/firmware/}. */
    public static final String ASSET_NAME = "Image";

    private KernelStub() {}
}
