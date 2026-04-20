/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.machine.storage;

import net.minecraft.network.chat.Component;

/**
 * First-party preloaded-disk template: a minimal ext2 Linux rootfs
 * ({@code linux_rootfs.ext2}, 16 MiB, deterministic UUID
 * {@code deadbeef-cafe-babe-feed-facefacefeed}, label {@code SCEV_ROOTFS}).
 *
 * <p>Carries a Unix directory skeleton ({@code /etc}, {@code /bin},
 * {@code /sbin}, {@code /proc}, {@code /sys}, {@code /dev}, {@code /root},
 * {@code /tmp}, {@code /mnt}, {@code /boot}) plus:
 *
 * <ul>
 *   <li>{@code /README} — usage notes.</li>
 *   <li>{@code /etc/hostname} — {@code "scev-linux"}.</li>
 *   <li>{@code /etc/fstab} — proc/sysfs/devtmpfs/tmpfs mounts.</li>
 *   <li>{@code /etc/os-release} — distro metadata.</li>
 * </ul>
 *
 * <p>Once a workstation has a {@link lekkit.scev.items.PreloadedNvmeItem}
 * installed, the guest Linux kernel can mount it with:
 *
 * <pre>
 *     mount -t ext2 /dev/nvme0n1 /mnt
 * </pre>
 *
 * <p>The filesystem is validated with {@code e2fsck -f -n} at build time
 * and is fully standards-compliant — a real ext2, not a synthetic byte
 * pattern.
 *
 * <p><b>Expanding the rootfs with a real BusyBox userland</b> is a
 * forward-compatible change: replace the {@code linux_rootfs.ext2} asset
 * with a larger Buildroot-produced filesystem (bumping {@link #SIZE_MB}
 * if it grows). No code change needed.
 */
public final class BuildrootDiskTemplate implements ScevDiskTemplate {
    public static final BuildrootDiskTemplate INSTANCE = new BuildrootDiskTemplate();

    /** Classpath asset name under {@code /assets/scev/firmware/}. */
    public static final String ASSET_NAME = "linux_rootfs.ext2";

    /**
     * Declared disk size (MiB). Must be ≥ the actual asset size (16 MiB
     * today). The extra capacity is zero-initialized — ext2 is happy with
     * a sparse tail, though a future feature (auto-resize on first boot)
     * would expand the filesystem to fill the declared capacity.
     */
    public static final long SIZE_MB = 2048;

    /** Deterministic ext2 filesystem UUID set at asset build time. */
    public static final String FILESYSTEM_UUID = "deadbeef-cafe-babe-feed-facefacefeed";

    /** Deterministic ext2 volume label set at asset build time. */
    public static final String FILESYSTEM_LABEL = "SCEV_ROOTFS";

    private BuildrootDiskTemplate() {}

    @Override public String assetName() { return ASSET_NAME; }
    @Override public long sizeMb() { return SIZE_MB; }
    @Override public Component displayName() { return Component.literal("Buildroot Linux"); }
}
