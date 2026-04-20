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
}
