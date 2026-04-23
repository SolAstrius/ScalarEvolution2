/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.machine.storage;

import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * A named read-only disk image template used to seed a fresh storage item's
 * persistent image file.
 *
 * <p>The unit of "pre-loaded content" — a bootable Linux rootfs, a data-only
 * ext4 volume, an empty-but-partitioned image — is a registered template
 * referenced by a {@link net.minecraft.resources.ResourceLocation}. A
 * {@code StorageItem} whose stack NBT carries the template id uses that
 * template as the "origin" for its per-UUID disk image: on first power-on
 * the template's bytes are copied into {@code ./scev/images/<uuid>.img};
 * thereafter the blob is persistent and writable by the guest.
 *
 * <p>This scaffolding exists so that {@link DiskTemplateRegistry} has a
 * concrete type to hold and so that future commits can ship a real
 * bootable Linux template (Buildroot rootfs as a standalone ext4 image)
 * without churning the item hierarchy. No built-in templates are
 * registered yet — see {@code docs/TODO.md}.
 *
 * <h2>Contract</h2>
 *
 * <ul>
 *   <li>{@link #assetName} — classpath resource under
 *       {@code /assets/scev/firmware/}. Resolved via
 *       {@link lekkit.scev.server.FirmwareAssets#ensureExtracted}.</li>
 *   <li>{@link #sizeMb} — declared size (MiB) of the created disk image.
 *       The template payload must be ≤ this size; excess capacity
 *       remains zero-initialized.</li>
 *   <li>{@link #displayName} — human-readable name for tooltips.</li>
 * </ul>
 *
 * <p>Implementations must be stateless; the registry caches instances.
 */
public interface ScevDiskTemplate {
    /**
     * Classpath resource name under {@code /assets/scev/firmware/}. The
     * file there is the raw byte content of the initial disk image —
     * typically a filesystem image (ext2/ext4, FAT, etc).
     */
    String assetName();

    /**
     * Declared disk size in MiB. Consumers ({@code StorageManager}) size
     * the image file accordingly and copy the template bytes in. Must be
     * ≥ the template payload's actual size.
     */
    long sizeMb();

    /** Human-readable label for tooltips and diagnostics. */
    Component displayName();

    /**
     * Convenience: short textual description of the template for logs.
     * Default delegates to {@link #displayName} stringified.
     */
    default @Nullable String description() {
        return displayName().getString();
    }

    /**
     * Does this disk contain a mountable root filesystem?
     *
     * <p>When {@code true} AND the attached firmware declares
     * {@link lekkit.scev.machine.firmware.ScevFirmware#wantsNvmeRoot()},
     * {@link lekkit.scev.machine.MachineSpecParser} appends
     * {@code root=<rootDevice()> rw rootwait} to the kernel cmdline so a
     * kernel that honors the cmdline (either directly or via an initramfs
     * pivot script) mounts this disk as {@code /}.
     *
     * <p>Default is {@code false} — plain data volumes (HDD templates,
     * swap-like blobs, fake-but-blank disk images) leave the cmdline
     * alone and stay optional from the guest's perspective.
     */
    default boolean hasRootFilesystem() { return false; }

    /**
     * Linux device path this template wants advertised as the root device
     * when {@link #hasRootFilesystem()} is {@code true}. Defaults to
     * {@code /dev/nvme0n1} — matches RVVM's first-NVMe enumeration and
     * covers the single-NVMe-slot case that level-1/2 motherboards ship
     * with. Templates that want a different mount (e.g. {@code /dev/sda1},
     * a partition inside an MBR layout) override here.
     */
    default String rootDevice() { return "/dev/nvme0n1"; }

    /**
     * Does this disk carry its own bootloader and on-disk kernel (extlinux
     * layout — {@code /boot/vmlinuz*}, {@code /extlinux/extlinux.conf})?
     *
     * <p>When {@code true}, a disk-scanning firmware (typically U-Boot /
     * {@link lekkit.scev.machine.firmware.OpenFirmware}) can boot directly
     * from the disk without the firmware itself shipping a kernel payload.
     * The Alpine template is the canonical example.
     *
     * <p>Default is {@code false}. Templates in this state still work fine
     * with firmwares that load their own kernel ({@link lekkit.scev.machine.firmware.LinuxFirmware})
     * — {@link #hasRootFilesystem()} tells the parser to inject a
     * {@code root=} cmdline; this flag is orthogonal.
     */
    default boolean isBootable() { return false; }
}
