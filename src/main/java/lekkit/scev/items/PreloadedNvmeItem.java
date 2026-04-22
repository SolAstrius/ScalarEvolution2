/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.items;

import lekkit.scev.machine.storage.DiskTemplateRegistry;
import lekkit.scev.machine.storage.ScevDiskTemplate;
import lekkit.scev.main.ScevDataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * NVMe SSD shipped with a pre-installed disk image — the "disk with OS on
 * it" half of the flash-chip-vs-disk split.
 *
 * <p>Extends {@link NvmeItem} so the existing motherboard NVMe slots
 * accept it without any slot-predicate changes. Carries a default
 * {@link ScevDiskTemplate} registry id; when the parser
 * ({@link lekkit.scev.machine.MachineSpecParser}) sees this item in an
 * NVMe slot it emits a {@code DiskSpec} with {@code templateId} set, and
 * the backend seeds the per-UUID image from that template on first
 * power-on.
 *
 * <p>Default template: {@link DiskTemplateRegistry#BUILDROOT} — the shipped
 * 16 MiB ext2 rootfs with a minimal Unix directory skeleton, mountable
 * from the guest via {@code mount -t ext2 /dev/nvme0n1 /mnt}. Future
 * features can register additional templates and construct variant items
 * bound to them without touching this class.
 *
 * <h2>Item-side behavior</h2>
 *
 * <ul>
 *   <li>{@link #getOrigin()} still returns the template's asset name
 *       ({@code linux_rootfs.ext2}) for back-compat with anything that
 *       reads the origin as a raw string (legacy
 *       {@code StorageManager.copyImage} direct path, JSON debug).</li>
 *   <li>{@link #getDefaultTemplateId()} exposes the registry id so the
 *       parser can emit {@code DiskSpec(templateId=...)}.</li>
 * </ul>
 *
 * <p>Persistent storage UUID (per-stack {@code STORAGE_UUID} data
 * component) behaves identically to {@link NvmeItem}: first installation
 * allocates a UUID, subsequent moves of the same stack reuse the same
 * per-UUID image. What's different is the <i>initial content</i> of that
 * image — {@code StorageManager.copyImage} reads the template's
 * {@code assetName()} as the origin, copies those bytes into
 * {@code ./scev/images/<uuid>.img}, then hands the image to RVVM's NVMe
 * block device.
 */
public class PreloadedNvmeItem extends NvmeItem {
    private final ResourceLocation defaultTemplateId;

    /**
     * @param props             Item properties (stacksTo(1) inherited from
     *                          {@link NvmeItem} / {@link StorageItem}).
     * @param defaultTemplateId Registry id of the default disk template.
     *                          Must resolve in {@link DiskTemplateRegistry}
     *                          by the time the item is actually installed
     *                          in a running machine — resolution happens in
     *                          {@link lekkit.scev.machine.MachineSpecParser}
     *                          / {@code RvvmMachineBackend}, not here.
     */
    public PreloadedNvmeItem(Properties props, ResourceLocation defaultTemplateId) {
        // Delegate the size / origin-asset-name to the NvmeItem parent. We
        // override getSizeMb + getOrigin below to read from the template
        // registry instead, so the item's declared capacity always matches
        // the template's declared capacity.
        super(props);
        this.defaultTemplateId = defaultTemplateId;
    }

    /**
     * Template registry id this item type defaults to (ctor-provided).
     * Prefer {@link #getTemplateId(ItemStack)} when a stack is available —
     * that picks up per-stack overrides.
     */
    public ResourceLocation getDefaultTemplateId() {
        return defaultTemplateId;
    }

    /**
     * Per-stack template id: data component wins, falls back to the ctor
     * default. Lets a single registered item surface multiple template
     * variants in the creative tab (one stack per registered template,
     * {@link ScevDataComponents#DISK_TEMPLATE} differentiating them).
     */
    public ResourceLocation getTemplateId(ItemStack stack) {
        ResourceLocation override = stack.get(ScevDataComponents.DISK_TEMPLATE.get());
        return override != null ? override : defaultTemplateId;
    }

    /**
     * Origin asset name — derived from the registered template so the legacy
     * "origin is a direct classpath asset" code paths still work when a
     * template's registration is stripped. Returns {@code null} if the
     * template isn't registered (treat as blank NVMe).
     */
    @Override
    public @Nullable String getOrigin() {
        ScevDiskTemplate template = DiskTemplateRegistry.get(defaultTemplateId);
        return template != null ? template.assetName() : super.getOrigin();
    }

    /**
     * Disk size — derived from the template's declared size. A template
     * that isn't yet registered falls back to {@link NvmeItem}'s default
     * capacity so the item stays functional during early mod init.
     */
    @Override
    public long getSizeMb() {
        ScevDiskTemplate template = DiskTemplateRegistry.get(defaultTemplateId);
        return template != null ? template.sizeMb() : super.getSizeMb();
    }

    /**
     * Display name — derived from the template so "NVMe Drive" gets the
     * right distro suffix ({@code "(Alpine Linux)"}, {@code "(Buildroot
     * Linux)"}, etc.) without needing a new lang key per template.
     *
     * <p>Lang key is still {@code item.scev.nvme_preloaded} for the base
     * string; the template's {@code displayName()} is appended in
     * parentheses. Falls back to the lang default if the template isn't
     * registered (shouldn't happen post-init).
     */
    /**
     * Display name.
     *
     * <p>Fresh stack (no {@code STORAGE_UUID} yet → template not applied):
     * {@code "NVMe Drive (Alpine Linux)"} — the template suffix advertises
     * what the disk will be seeded with on first install.
     *
     * <p>Materialized stack ({@code STORAGE_UUID} set → bytes on disk):
     * {@code "NVMe Drive"} — the disk is no longer "preloaded", it's
     * whatever the guest has written to it since, with an identity of its
     * own. The UUID is surfaced in the tooltip by {@link StorageItem}.
     */
    @Override
    public Component getName(ItemStack stack) {
        Component base = Component.translatable(this.getDescriptionId(stack));
        if (getUuid(stack) != null) {
            // Already allocated + seeded; template is historical.
            return base;
        }
        ScevDiskTemplate template = DiskTemplateRegistry.get(getTemplateId(stack));
        if (template == null) return base;
        return Component.empty()
                .append(base)
                .append(Component.literal(" ("))
                .append(template.displayName())
                .append(Component.literal(")"));
    }
}
