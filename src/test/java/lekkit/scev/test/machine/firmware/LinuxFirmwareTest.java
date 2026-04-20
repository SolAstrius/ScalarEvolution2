/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.machine.firmware;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import lekkit.scev.machine.firmware.LinuxFirmware;
import lekkit.scev.machine.firmware.ScevFirmware;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins down the {@link LinuxFirmware} manifest. Changes here will cause
 * visible production behavior changes (which file boots, which cmdline,
 * what RAM floor) — force anyone editing the class to deliberately update
 * the test.
 */
class LinuxFirmwareTest {

    @BeforeAll
    static void bootstrap() { Bootstrap.bootStrap(); }

    @Test
    @DisplayName("INSTANCE is a reusable singleton")
    void singleton() {
        assertSame(LinuxFirmware.INSTANCE, LinuxFirmware.INSTANCE);
    }

    @Test
    @DisplayName("Two payloads in order: BOOTROM fw_jump.bin, KERNEL Image")
    void payloads() {
        List<ScevFirmware.Payload> p = LinuxFirmware.INSTANCE.payloads();
        assertEquals(2, p.size(), "Linux firmware must declare exactly a bootrom + kernel pair — "
                + "matches the OpenSBI+Linux boot path at 0x80000000 / 0x80200000");

        assertEquals(ScevFirmware.Payload.Kind.BOOTROM, p.get(0).kind(),
                "First payload must be BOOTROM (loaded at 0x80000000)");
        assertEquals("fw_jump.bin", p.get(0).asset(),
                "BOOTROM asset is OpenSBI's fw_jump.bin (M-mode firmware)");

        assertEquals(ScevFirmware.Payload.Kind.KERNEL, p.get(1).kind(),
                "Second payload must be KERNEL (loaded at 0x80200000)");
        assertEquals("Image", p.get(1).asset(),
                "KERNEL asset is the RV64 Linux Image");

        // The constants must match the payload list — the Javadoc and tests
        // both reference them as the single source of truth for the asset
        // names. Catches the "someone edited the list but not the constant"
        // drift.
        assertEquals(LinuxFirmware.BOOTROM_ASSET, p.get(0).asset());
        assertEquals(LinuxFirmware.KERNEL_ASSET, p.get(1).asset());
    }

    @Test
    @DisplayName("minRamMb is 256 (kernel + initramfs Linux floor)")
    void ramFloor() {
        assertEquals(256, LinuxFirmware.INSTANCE.minRamMb(),
                "256 MiB is the comfortable headroom above the 128 MiB kernel OOM "
                        + "threshold. Below that, pty_init panics on /dev/pts sysfs "
                        + "allocation. If this drops, verify the "
                        + "linux_kernel_boots_and_draws_fbcon GameTest still reaches "
                        + "'still running after 20s' with the reduced floor.");
        assertEquals(LinuxFirmware.MIN_RAM_MB, LinuxFirmware.INSTANCE.minRamMb(),
                "Method and constant must agree (single source of truth)");
    }

    @Test
    @DisplayName("cmdlineAppend routes console to fbcon + serial + early SBI")
    void cmdline() {
        String cmdline = LinuxFirmware.INSTANCE.cmdlineAppend();
        assertNotNull(cmdline);
        assertTrue(cmdline.contains("console=tty0"),
                "fbcon kernel console route — Linux's fbcon attaches to tty0, "
                        + "so kernel log renders on the framebuffer");
        assertTrue(cmdline.contains("console=ttyS0,115200"),
                "Serial fallback — kernel log also sent to NS16550A UART; "
                        + "visible in the server stdout during GameTests");
        assertTrue(cmdline.contains("earlycon=sbi"),
                "Early SBI console — kernel prints via sbi_putchar before "
                        + "simple-framebuffer is initialized");
        assertEquals(LinuxFirmware.CMDLINE, cmdline,
                "Method and constant must agree");
    }

    @Test
    @DisplayName("tty0 appears AFTER ttyS0 so getty spawns on fbcon (login prompt visible on screen)")
    void tty0IsLastConsole() {
        String cmdline = LinuxFirmware.CMDLINE;
        int ttyS0Idx = cmdline.indexOf("console=ttyS0");
        int tty0Idx = cmdline.indexOf("console=tty0");
        assertTrue(ttyS0Idx >= 0 && tty0Idx >= 0,
                "Both console= entries must be present. Got: " + cmdline);
        assertTrue(tty0Idx > ttyS0Idx,
                "console=tty0 must come AFTER console=ttyS0 in the cmdline. Linux uses the last "
                        + "console= as /dev/console, which is where BusyBox's default inittab "
                        + "(`getty -L console 0 vt100`) spawns the login prompt. Put tty0 last so "
                        + "'buildroot login:' shows up on the Tinkerpad screen, not just in the "
                        + "server stdout. Got cmdline: " + cmdline);
    }

    @Test
    @DisplayName("displayName is a non-empty component")
    void name() {
        assertNotNull(LinuxFirmware.INSTANCE.displayName());
        assertFalse(LinuxFirmware.INSTANCE.displayName().getString().isEmpty());
    }
}
