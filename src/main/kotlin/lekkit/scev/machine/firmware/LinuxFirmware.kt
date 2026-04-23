/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.machine.firmware

import lekkit.scev.machine.firmware.ScevFirmware.Payload
import net.minecraft.network.chat.Component

/**
 * Default "flash chip installed" firmware: OpenSBI 1.6 (`fw_jump.bin`)
 * at 0x80000000 + a RV64 Linux kernel (`Image`) at 0x80200000. OpenSBI
 * `mret`s into S-mode at the kernel entry point after SBI / PMP / trap
 * setup; Linux detects RVVM's `simple-framebuffer` DTB node and brings
 * fbcon up on the workstation screen.
 *
 * 256 MiB floor: below ~128 MiB the shipped kernel + 26 MiB Buildroot
 * initramfs OOM on `pty_init`; 256 is double the observed-working minimum
 * for headroom (see docs/FIRMWARE.md).
 */
object LinuxFirmware : ScevFirmware {
    const val BOOTROM_ASSET = "fw_jump.bin"
    const val KERNEL_ASSET = "Image"

    /**
     * Kernel cmdline appended at boot.
     *
     * Console ordering is load-bearing. Linux uses the **last** `console=`
     * entry as `/dev/console`, which is where BusyBox's default
     * `getty -L console ...` inittab spawns the login prompt. `ttyS0` first
     * keeps kernel messages flowing to the serial port; `tty0` **last** puts
     * the login prompt on the framebuffer — the actual Tinkerpad screen the
     * player is looking at. The earlier ordering sent `buildroot login:`
     * only to the serial side, so the player saw boot stop partway through
     * userspace with no prompt on-screen.
     *
     * `earlycon=sbi` routes the very first kernel messages via SBI before
     * `simple-framebuffer` has attached, so they appear on-screen instead
     * of disappearing into a black frame.
     */
    const val CMDLINE = "console=ttyS0,115200 console=tty0 earlycon=sbi"

    const val MIN_RAM_MB = 256L

    private val PAYLOADS = listOf(
        Payload(Payload.Kind.BOOTROM, BOOTROM_ASSET),
        Payload(Payload.Kind.KERNEL, KERNEL_ASSET),
    )

    override fun payloads(): List<Payload> = PAYLOADS
    override fun minRamMb(): Long = MIN_RAM_MB
    override fun cmdlineAppend(): String = CMDLINE
    override fun displayName(): Component = Component.literal("Linux")

    /**
     * Linux loads via `rvvm_load_kernel` directly; the kernel reads `root=`
     * off the cmdline at boot. When a rootfs-declaring NVMe is attached the
     * parser injects `root=<template.rootDevice()> rw rootwait` so pid 1
     * lives on the disk — `/dev/nvme0n1` for the raw Buildroot template,
     * `/dev/nvme0n1p1` for the MBR-wrapped Alpine template. The initramfs
     * `/init` pivot script reads that back off `/proc/cmdline` and mounts
     * whichever device the parser chose.
     *
     * Without this override, injection never fires and every guest write
     * hits the embedded initramfs tmpfs — the exact asymmetry that the
     * abstraction exists to prevent.
     */
    override fun wantsNvmeRoot(): Boolean = true
}
