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
 * Domain-specific invariants for {@link LinuxFirmware}. Generic shape
 * checks (payload non-empty, displayName non-empty, asset bundled, etc.)
 * live in {@code FirmwareInvariantTest}; this file asserts the properties
 * that matter uniquely to the Linux boot path.
 */
class LinuxFirmwareTest {

    @BeforeAll
    static void bootstrap() { Bootstrap.bootStrap(); }

    @Test
    @DisplayName("Two payloads in order: BOOTROM fw_jump.bin, KERNEL Image")
    void payloadShape() {
        // Every other firmware in the registry is bootrom-only; Linux is
        // the one that pairs an M-mode firmware with an S-mode kernel and
        // needs the two-stage load.
        List<ScevFirmware.Payload> p = LinuxFirmware.INSTANCE.payloads();
        assertEquals(2, p.size(),
                "Linux firmware must declare bootrom + kernel — the OpenSBI→Linux "
                        + "handoff at 0x80000000 / 0x80200000");
        assertEquals(ScevFirmware.Payload.Kind.BOOTROM, p.get(0).kind());
        assertEquals("fw_jump.bin", p.get(0).asset());
        assertEquals(ScevFirmware.Payload.Kind.KERNEL, p.get(1).kind());
        assertEquals("Image", p.get(1).asset());
    }

    @Test
    @DisplayName("cmdline routes console to fbcon + serial + early SBI")
    void cmdlineRouting() {
        String cmdline = LinuxFirmware.INSTANCE.cmdlineAppend();
        assertNotNull(cmdline);
        assertTrue(cmdline.contains("console=tty0"),
                "fbcon must be in the cmdline or nothing renders on the Tinkerpad screen");
        assertTrue(cmdline.contains("console=ttyS0,115200"),
                "ttyS0 kept in cmdline for server-side debugging");
        assertTrue(cmdline.contains("earlycon=sbi"),
                "earlycon=sbi covers kernel messages before simple-framebuffer attaches");
    }

    @Test
    @DisplayName("tty0 appears AFTER ttyS0 so getty spawns on fbcon")
    void tty0IsLastConsole() {
        // Linux uses the last `console=` as /dev/console, which is where
        // BusyBox's default inittab (`getty -L console 0 vt100`) spawns the
        // login prompt. If tty0 moves before ttyS0, `buildroot login:`
        // appears only on the serial side — the player sees boot stop with
        // no on-screen prompt. Protect against that regression explicitly.
        String cmdline = LinuxFirmware.CMDLINE;
        int ttyS0Idx = cmdline.indexOf("console=ttyS0");
        int tty0Idx = cmdline.indexOf("console=tty0");
        assertTrue(tty0Idx > ttyS0Idx,
                "console=tty0 must come AFTER console=ttyS0 so getty spawns on the framebuffer. "
                        + "Got cmdline: " + cmdline);
    }

    @Test
    @DisplayName("256 MiB RAM floor (double the 128 MiB pty_init OOM threshold)")
    void ramFloor() {
        // Below ~128 MiB the kernel panics on pty_init /dev/pts allocation
        // with the shipped Buildroot + 26 MiB initramfs. 256 is double the
        // observed-working minimum. If this drops, re-run the
        // linux_kernel_boots_and_draws_fbcon GameTest at the reduced floor.
        assertEquals(256, LinuxFirmware.INSTANCE.minRamMb());
    }

    @Test
    @DisplayName("wantsNvmeRoot is true — parser injects root= when a rootfs disk is attached")
    void wantsNvmeRoot() {
        assertTrue(LinuxFirmware.INSTANCE.wantsNvmeRoot(),
                "LinuxFirmware loads the kernel directly via rvvm_load_kernel; the kernel "
                        + "reads root= off the cmdline at boot. Turning this off would suppress "
                        + "the parser's per-template root=<device> injection and force the guest "
                        + "to stay in its embedded initramfs even when a rootfs NVMe is installed "
                        + "— exactly the 'reboot starts fresh' regression the disk-persistence "
                        + "refactor set out to fix.");
    }
}
