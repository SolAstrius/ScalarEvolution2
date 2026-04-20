/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.machine;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import lekkit.scev.machine.KernelStub;
import lekkit.scev.server.FirmwareAssets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Assert the shipped {@code Image} resource is a real RV64 Linux kernel,
 * not the historical 32-byte {@link KernelStub} placeholder.
 *
 * <p>This test replaced the earlier stub-exact-bytes check after the
 * Buildroot-built RV64 kernel was committed at
 * {@code src/main/resources/assets/scev/firmware/Image}. Before that,
 * the mod shipped a 32-byte hand-assembled RV64 program as the default
 * {@code Image} so the kernel-load pipeline was exercised end-to-end
 * even without a real kernel. That stub stays in the codebase as a
 * fallback / reference (see {@link KernelStub} + {@code docs/BUILDROOT.md}),
 * but isn't what ships anymore.
 *
 * <p><b>What this checks:</b>
 * <ul>
 *   <li><b>Size floor.</b> A real Buildroot kernel with fbcon + initramfs
 *       is at least 1 MiB. A stub-sized shipped {@code Image} is a signal
 *       that the fallback accidentally got committed back in.</li>
 *   <li><b>Linux RV64 boot header magic.</b> At offset 48 (0x30) every
 *       RV64 Linux {@code Image} has the 8-byte sequence
 *       {@code "RISCV\0\0\0"} — see
 *       {@code Documentation/riscv/boot-image-header.rst} in the Linux
 *       kernel tree. Catches: "we shipped {@code vmlinux} (ELF) by
 *       mistake", "we shipped {@code Image.gz} and it won't boot because
 *       RVVM's {@code bin_objcopy} doesn't decompress", and the general
 *       "something-not-a-kernel landed here".</li>
 * </ul>
 *
 * <p><b>What this intentionally does NOT check:</b> specific kernel
 * version, compile-time config, or boot-time behavior. Those are
 * verified end-to-end by the {@code linux_kernel_boots_and_draws_fbcon}
 * GameTest.
 */
class KernelStubTest {

    /** Minimum size for a real Buildroot-built RV64 kernel with initramfs. */
    private static final int MIN_REAL_KERNEL_SIZE = 1_000_000;

    /** RV64 Linux boot header magic — 8 bytes at offset 48 ("RISCV\0\0\0"). */
    private static final byte[] LINUX_RISCV_MAGIC = {'R', 'I', 'S', 'C', 'V', 0, 0, 0};
    private static final int LINUX_MAGIC_OFFSET = 48;

    @Test
    @DisplayName("Shipped Image is a real RV64 Linux kernel (>1 MiB, valid magic at offset 48)")
    void imageIsRealKernel() throws IOException {
        try (InputStream in = FirmwareAssets.class
                .getResourceAsStream(FirmwareAssets.CLASSPATH_PREFIX + KernelStub.ASSET_NAME)) {
            assertNotNull(in, "Image resource is missing from the mod jar "
                    + "(expected at " + FirmwareAssets.CLASSPATH_PREFIX + KernelStub.ASSET_NAME + ")");
            byte[] shipped = in.readAllBytes();

            assertTrue(shipped.length > MIN_REAL_KERNEL_SIZE,
                    "Shipped Image is suspiciously small (" + shipped.length + " bytes). "
                            + "Did the 32-byte KernelStub placeholder get committed back in? "
                            + "If you intend to regenerate the kernel, see docs/BUILDROOT.md.");

            assertTrue(shipped.length >= LINUX_MAGIC_OFFSET + LINUX_RISCV_MAGIC.length,
                    "Image too short to contain the Linux boot header (got "
                            + shipped.length + " bytes, need at least "
                            + (LINUX_MAGIC_OFFSET + LINUX_RISCV_MAGIC.length) + ")");

            for (int i = 0; i < LINUX_RISCV_MAGIC.length; i++) {
                byte got = shipped[LINUX_MAGIC_OFFSET + i];
                byte want = LINUX_RISCV_MAGIC[i];
                if (got != want) {
                    fail("Image byte " + (LINUX_MAGIC_OFFSET + i) + " is 0x"
                            + String.format("%02x", got & 0xFF)
                            + ", expected 0x" + String.format("%02x", want & 0xFF)
                            + " (Linux RV64 boot header magic 'RISCV\\0\\0\\0' at offset 0x30). "
                            + "Likely causes: shipped Image.gz (compressed) instead of Image (raw), "
                            + "or shipped vmlinux (ELF) instead of Image. "
                            + "See docs/BUILDROOT.md and the GOTCHAS.md note about "
                            + "'Don't ship Image.gz or an ELF as the kernel'.");
                }
            }
        }
    }
}
