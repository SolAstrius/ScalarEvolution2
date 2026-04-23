/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.machine.storage

import net.minecraft.network.chat.Component

/**
 * Live Alpine Linux disk image produced by the companion
 * [scev-alpine](https://github.com/SolAstrius/scev-alpine) pipeline. An
 * extlinux-layout ext4 volume containing:
 *
 *  - `/boot/vmlinuz-lts` — scev-patched Alpine kernel with `I2C_OCORES`,
 *    `I2C_HID_OF`, `SND_HDA_INTEL`, `SND_HDA_CODEC_CMEDIA`,
 *    `GPIO_SIFIVE`, and 9P filesystem support re-enabled over Alpine's
 *    stock config.
 *  - `/boot/initramfs-lts` — Alpine's mkinitfs-built initrd.
 *  - `/boot/modloop-lts` — matching kernel modules squashfs.
 *  - `/extlinux/extlinux.conf` — U-Boot bootflow config.
 *  - `/apks/riscv64/` — bundled apk repo for offline
 *    `setup-alpine` / `apk add` on first boot.
 *
 * Pairs with [FirmwareRegistry.OPEN_FIRMWARE] (OpenSBI + U-Boot) in the
 * flash chip. Boot path:
 *
 * ```
 * OpenSBI → U-Boot → nvme scan → /extlinux/extlinux.conf
 *        → vmlinuz-lts + initramfs-lts → Alpine live system in RAM
 * ```
 *
 * First boot drops the player at an Alpine login prompt (`root`, no
 * password). They can `setup-alpine` to install to a blank second NVMe,
 * or run live.
 *
 * The shipped image is ~65 MiB; [SIZE_MB] is 1024 so StorageManager's
 * sparse-file create would pad it if the template is missing, but the raw
 * file copy preserves the template's actual byte count. Label and UUID
 * are deterministic per build so extlinux.conf's `root=UUID=...` stays
 * stable.
 */
object AlpineDiskTemplate : ScevDiskTemplate {
    const val ASSET_NAME = "alpine_rootfs.img"
    const val SIZE_MB = 1024L
    const val FILESYSTEM_UUID = "deadbeef-cafe-beef-feed-a1befacefeed"
    const val FILESYSTEM_LABEL = "SCEV_ALPINE"

    override fun assetName(): String = ASSET_NAME
    override fun sizeMb(): Long = SIZE_MB
    override fun displayName(): Component = Component.literal("Alpine Linux")

    /**
     * The scev-alpine sys-install image wraps the rootfs in an MBR partition
     * table (single bootable ext4 partition at LBA 2048); see
     * `tools/build-nvme-sysinstall.sh` in scev-alpine. Combined with [rootDevice]
     * returning `/dev/nvme0n1p1`, pairing this template with
     * [lekkit.scev.machine.firmware.LinuxFirmware] makes the parser inject
     * `root=/dev/nvme0n1p1 rw rootwait`; the Buildroot initramfs `/init` reads
     * that back off `/proc/cmdline` and mounts p1 directly.
     *
     * With [isBootable], the OpenFirmware (U-Boot) path is equivalent — U-Boot
     * reads `/extlinux/extlinux.conf` on p1 and passes its own `root=/dev/nvme0n1p1`.
     * Both paths converge on the same device; the parser injection is redundant
     * with U-Boot (Linux uses the last `root=`) and authoritative without it.
     */
    override fun hasRootFilesystem(): Boolean = true

    /**
     * MBR partition 1 holds the ext4 rootfs. Unlike [BuildrootDiskTemplate]
     * (raw ext4 at the whole device) the Alpine build wraps its filesystem in
     * an MBR so U-Boot's `sysboot` finds the bootable partition marker — see
     * scev-alpine's `build-nvme-sysinstall.sh` (`sfdisk ... type=83, bootable`).
     *
     * The Buildroot kernel enumerates this via `CONFIG_MSDOS_PARTITION=y`, so
     * `/dev/nvme0n1p1` is a real block device once the disk is attached. The
     * initramfs `/init` script reads `root=` off `/proc/cmdline` and mounts
     * whichever device the parser injected — keeping the partition-aware
     * behavior in userspace instead of encoding per-template branches in the
     * kernel cmdline assembly.
     */
    override fun rootDevice(): String = "/dev/nvme0n1p1"

    /**
     * Ships `/boot/vmlinuz-lts`, `/boot/initramfs-lts`, and
     * `/extlinux/extlinux.conf` so U-Boot's distro_bootcmd can scan and boot
     * directly. Used when the flash chip is [lekkit.scev.machine.firmware.OpenFirmware]
     * (OpenSBI + U-Boot) — the firmware delegates boot entirely to the disk.
     */
    override fun isBootable(): Boolean = true
}
