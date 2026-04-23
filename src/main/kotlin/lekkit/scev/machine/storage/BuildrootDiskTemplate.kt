/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.machine.storage

import net.minecraft.network.chat.Component

/**
 * First-party preloaded-disk template: a minimal ext2 Linux rootfs
 * (`linux_rootfs.ext2`, 16 MiB, deterministic label [FILESYSTEM_LABEL]
 * and UUID [FILESYSTEM_UUID]).
 *
 * Carries a Unix directory skeleton (`/etc`, `/bin`, `/sbin`, `/proc`,
 * `/sys`, `/dev`, `/root`, `/tmp`, `/mnt`, `/boot`) plus `/README`,
 * `/etc/hostname` (`"scev-linux"`), `/etc/fstab`, and `/etc/os-release`.
 *
 * Once installed on a [lekkit.scev.items.PreloadedNvmeItem], the guest
 * kernel can mount it with:
 *
 * ```
 * mount -t ext2 /dev/nvme0n1 /mnt
 * ```
 *
 * The filesystem is validated with `e2fsck -f -n` at build time. The
 * declared [SIZE_MB] is 2048 — must be ≥ the actual 16 MiB asset; the
 * extra capacity is zero-initialized, which ext2 handles as a sparse tail.
 * Replacing `linux_rootfs.ext2` with a larger Buildroot-produced filesystem
 * needs only [SIZE_MB] bumped to match; no other code change.
 */
object BuildrootDiskTemplate : ScevDiskTemplate {
    const val ASSET_NAME = "linux_rootfs.ext2"

    /**
     * Declared capacity in MiB. The shipped `linux_rootfs.ext2` is much
     * smaller (64 MiB BusyBox rootfs from `tools/buildroot/build-kernel.sh`,
     * or a 16 MiB skeleton on older builds). [lekkit.scev.server.StorageManager.initImage]
     * sparse-extends the per-UUID image to this value after the template
     * copy, so from the guest's perspective the NVMe block device is 1 GiB
     * — matching what the item tooltip advertises and what [lekkit.scev.items.NvmeItem.SIZE_MB]
     * declares for blank NVMes. The `/init` pivot script in
     * `tools/buildroot/rootfs_overlay/init` calls `resize2fs` on first boot
     * to grow the filesystem to fill that advertised capacity.
     */
    const val SIZE_MB = 1024L

    const val FILESYSTEM_UUID = "deadbeef-cafe-babe-feed-facefacefeed"
    const val FILESYSTEM_LABEL = "SCEV_ROOTFS"

    override fun assetName(): String = ASSET_NAME
    override fun sizeMb(): Long = SIZE_MB
    override fun displayName(): Component = Component.literal("Buildroot Linux")

    /**
     * Intended pairing is [lekkit.scev.machine.firmware.LinuxFirmware] plus
     * the initramfs pivot script in `tools/buildroot/rootfs_overlay/init`:
     * kernel loads from flash, initramfs switch_roots to this disk,
     * pid 1 lives on ext4. The declaration is the hook that makes the
     * parser emit `root=<rootDevice()> rw rootwait`.
     *
     * The asset is a **raw** ext filesystem (genext2fs output, no MBR
     * wrapping), mountable at the whole-disk device — so this template
     * keeps the interface default of `/dev/nvme0n1` for [rootDevice].
     * Contrast [AlpineDiskTemplate], whose build wraps ext4 in an MBR
     * partition table and therefore overrides rootDevice to p1.
     */
    override fun hasRootFilesystem(): Boolean = true

    /**
     * No extlinux.conf / on-disk kernel — U-Boot would find nothing to boot.
     * Pair with [lekkit.scev.machine.firmware.LinuxFirmware], not
     * [lekkit.scev.machine.firmware.OpenFirmware].
     */
    override fun isBootable(): Boolean = false
}
