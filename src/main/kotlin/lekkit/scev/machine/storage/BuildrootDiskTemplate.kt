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
    const val SIZE_MB = 2048L
    const val FILESYSTEM_UUID = "deadbeef-cafe-babe-feed-facefacefeed"
    const val FILESYSTEM_LABEL = "SCEV_ROOTFS"

    override fun assetName(): String = ASSET_NAME
    override fun sizeMb(): Long = SIZE_MB
    override fun displayName(): Component = Component.literal("Buildroot Linux")
}
