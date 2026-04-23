/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.machine.storage;

import static org.junit.jupiter.api.Assertions.*;

import lekkit.scev.machine.storage.AlpineDiskTemplate;
import lekkit.scev.machine.storage.BuildrootDiskTemplate;
import lekkit.scev.machine.storage.ScevDiskTemplate;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for the {@link ScevDiskTemplate} interface's default
 * methods (the disk-persistence abstraction) and the metadata declared by
 * the two shipped built-in templates.
 *
 * <p>The interface defaults exist so data-only templates can opt out of
 * the "NVMe-as-rootfs" cmdline machinery by doing nothing. Built-in
 * templates that DO want the cmdline machinery must declare themselves
 * here — those declarations are the entire runtime contract that
 * {@link lekkit.scev.machine.MachineSpecParser} reads at boot. Regressing
 * them would silently drop the {@code root=} injection and send the guest
 * kernel right back to its embedded initramfs.
 */
class ScevDiskTemplateTest {

    @BeforeAll
    static void bootstrap() { Bootstrap.bootStrap(); }

    /**
     * A minimal inline template used to exercise interface defaults
     * without pulling in the shipped classpath assets. Declares only the
     * required methods; every {@code default} should return its
     * non-rootfs baseline.
     */
    private static final class MinimalTemplate implements ScevDiskTemplate {
        @Override public String assetName() { return "minimal.bin"; }
        @Override public long sizeMb() { return 64; }
        @Override public Component displayName() { return Component.literal("Minimal"); }
    }

    @Test
    @DisplayName("Interface default: hasRootFilesystem() is false")
    void defaultNoRootfs() {
        assertFalse(new MinimalTemplate().hasRootFilesystem(),
                "Data-only templates must NOT be treated as a rootfs by default. "
                        + "Otherwise a plain HDD template would trigger the parser's "
                        + "root= cmdline injection and confuse the kernel.");
    }

    @Test
    @DisplayName("Interface default: rootDevice() is /dev/nvme0n1 (single-slot default)")
    void defaultRootDevice() {
        assertEquals("/dev/nvme0n1", new MinimalTemplate().rootDevice(),
                "Default matches RVVM's NVMe enumeration: the first (and for level-1/2 "
                        + "motherboards, only) NVMe slot becomes /dev/nvme0n1 in the guest.");
    }

    @Test
    @DisplayName("Interface default: isBootable() is false")
    void defaultNotBootable() {
        assertFalse(new MinimalTemplate().isBootable(),
                "No extlinux/on-disk-kernel by default — U-Boot would find nothing to boot.");
    }

    @Test
    @DisplayName("BuildrootDiskTemplate declares rootfs + /dev/nvme0n1 + not-bootable")
    void buildrootMetadata() {
        BuildrootDiskTemplate t = BuildrootDiskTemplate.INSTANCE;
        assertTrue(t.hasRootFilesystem(),
                "Buildroot template is intended as a rootfs for LinuxFirmware's direct "
                        + "kernel path. Even while the shipped asset is still a skeleton "
                        + "without executables, the metadata must declare the intent so "
                        + "the parser injects root= — otherwise the future initramfs "
                        + "pivot would have nothing to read.");
        assertEquals("/dev/nvme0n1", t.rootDevice(),
                "RVVM's first NVMe is /dev/nvme0n1 — keep the default.");
        assertFalse(t.isBootable(),
                "No extlinux.conf on this template; U-Boot would not find a boot entry. "
                        + "Pair with LinuxFirmware (which loads its own kernel), not OpenFirmware.");
    }

    @Test
    @DisplayName("AlpineDiskTemplate declares rootfs + /dev/nvme0n1p1 + bootable (MBR layout)")
    void alpineMetadata() {
        AlpineDiskTemplate t = AlpineDiskTemplate.INSTANCE;
        assertTrue(t.hasRootFilesystem(),
                "Alpine partition contains a mountable root filesystem.");
        // Alpine's build pipeline wraps the ext4 rootfs in an MBR partition
        // table (see scev-alpine/tools/build-nvme-sysinstall.sh — `sfdisk`
        // creates a single bootable partition at LBA 2048). So the root
        // device is p1, not the whole disk — pairing with LinuxFirmware
        // injects `root=/dev/nvme0n1p1` into the cmdline, which the
        // Buildroot initramfs /init parses back via /proc/cmdline and
        // mounts directly. Using /dev/nvme0n1 here would be a latent bug:
        // the whole-disk device has an MBR signature, not an ext4 super-
        // block, so mount would fail and /init would fall through to
        // initramfs userspace — meaning the player would see Buildroot
        // BusyBox instead of Alpine.
        assertEquals("/dev/nvme0n1p1", t.rootDevice(),
                "Alpine is MBR-partitioned with the rootfs on p1. rootDevice() must "
                        + "reflect the actual mount target, otherwise the parser injects a "
                        + "root= that points at the whole disk (MBR header, not a filesystem) "
                        + "and the kernel mount silently fails into the initramfs fallback.");
        assertTrue(t.isBootable(),
                "Alpine ships /boot/vmlinuz-lts + /boot/initramfs-lts + "
                        + "/extlinux/extlinux.conf. U-Boot's distro_bootcmd scans for this "
                        + "layout; the OpenFirmware + Alpine pairing relies on it.");
    }

    @Test
    @DisplayName("Templates diverge on root device by disk layout — whole-disk vs. MBR partition")
    void rootDevicesDivergeByDiskLayout() {
        // The two built-ins intentionally declare different root devices
        // because their on-disk layouts are different:
        //   * BuildrootDiskTemplate — genext2fs output, raw ext filesystem
        //     at the whole device -> /dev/nvme0n1.
        //   * AlpineDiskTemplate — MBR-wrapped single partition from
        //     scev-alpine's build-nvme-sysinstall.sh -> /dev/nvme0n1p1.
        // The Buildroot kernel enumerates MBR partitions via
        // CONFIG_MSDOS_PARTITION=y, so both devices are real block nodes
        // inside the guest — the initramfs /init reads root= off
        // /proc/cmdline and mounts whichever the parser injected.
        //
        // Test exists so a future template mixing up its layout (e.g.
        // switching Buildroot to an MBR wrapping without updating
        // rootDevice) fails loudly here instead of silently at guest boot.
        assertEquals("/dev/nvme0n1", BuildrootDiskTemplate.INSTANCE.rootDevice(),
                "BuildrootDiskTemplate ships a raw ext filesystem — root is the whole "
                        + "disk. If you wrap it in an MBR, update rootDevice() to match.");
        assertEquals("/dev/nvme0n1p1", AlpineDiskTemplate.INSTANCE.rootDevice(),
                "AlpineDiskTemplate ships an MBR-wrapped partition layout — root is p1. "
                        + "If scev-alpine switches to a raw rootfs, drop rootDevice() back to "
                        + "the interface default.");
        assertNotEquals(BuildrootDiskTemplate.INSTANCE.rootDevice(),
                AlpineDiskTemplate.INSTANCE.rootDevice(),
                "The two templates must declare different root devices — otherwise the "
                        + "test is asserting vacuously and a drift would slip through.");
    }
}
