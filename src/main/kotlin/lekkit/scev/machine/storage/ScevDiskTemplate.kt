/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.machine.storage

import net.minecraft.network.chat.Component

/**
 * A named read-only disk image template used to seed a fresh storage
 * item's persistent image file.
 *
 * The unit of "pre-loaded content" — a bootable Linux rootfs, a
 * data-only ext4 volume, an empty-but-partitioned image — is a
 * registered template referenced by a
 * [net.minecraft.resources.ResourceLocation]. A `StorageItem` whose
 * stack NBT carries the template id uses that template as the "origin"
 * for its per-UUID disk image: on first power-on the template's bytes
 * are copied into `./scev/images/<uuid>.img`; thereafter the blob is
 * persistent and writable by the guest.
 *
 * Implementations must be stateless; the registry caches instances.
 */
interface ScevDiskTemplate {
    /**
     * Classpath resource name under `/assets/scev/firmware/`. The file
     * there is the raw byte content of the initial disk image —
     * typically a filesystem image (ext2/ext4, FAT, etc).
     */
    fun assetName(): String

    /**
     * Declared disk size in MiB. Consumers (`StorageManager`) size the
     * image file accordingly and copy the template bytes in. Must be ≥
     * the template payload's actual size.
     */
    fun sizeMb(): Long

    /** Human-readable label for tooltips and diagnostics. */
    fun displayName(): Component

    /**
     * Convenience: short textual description of the template for logs.
     * Default delegates to [displayName] stringified.
     */
    fun description(): String? = displayName().string

    /**
     * Does this disk contain a mountable root filesystem?
     *
     * When `true` AND the attached firmware declares
     * [lekkit.scev.machine.firmware.ScevFirmware.wantsNvmeRoot],
     * [lekkit.scev.machine.MachineSpecParser] appends
     * `root=<rootDevice()> rw rootwait` to the kernel cmdline so a
     * kernel that honors the cmdline (either directly or via an
     * initramfs pivot script) mounts this disk as `/`.
     *
     * Default is `false` — plain data volumes (HDD templates,
     * swap-like blobs, fake-but-blank disk images) leave the cmdline
     * alone and stay optional from the guest's perspective.
     */
    fun hasRootFilesystem(): Boolean = false

    /**
     * Linux device path this template wants advertised as the root
     * device when [hasRootFilesystem] is `true`. Defaults to
     * `/dev/nvme0n1` — matches RVVM's first-NVMe enumeration and
     * covers the single-NVMe-slot case that level-1/2 motherboards
     * ship with. Templates that want a different mount (e.g.
     * `/dev/sda1`, a partition inside an MBR layout) override here.
     */
    fun rootDevice(): String = "/dev/nvme0n1"

    /**
     * Does this disk carry its own bootloader and on-disk kernel
     * (extlinux layout — `/boot/vmlinuz*`, `/extlinux/extlinux.conf`)?
     *
     * When `true`, a disk-scanning firmware (typically U-Boot /
     * [lekkit.scev.machine.firmware.OpenFirmware]) can boot directly
     * from the disk without the firmware itself shipping a kernel
     * payload. The Alpine template is the canonical example.
     *
     * Default is `false`. Templates in this state still work fine
     * with firmwares that load their own kernel
     * ([lekkit.scev.machine.firmware.LinuxFirmware]) —
     * [hasRootFilesystem] tells the parser to inject a `root=`
     * cmdline; this flag is orthogonal.
     */
    fun isBootable(): Boolean = false
}
