/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.items

import lekkit.scev.machine.storage.DiskTemplateRegistry
import lekkit.scev.main.ScevDataComponents
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack

/**
 * NVMe SSD shipped with a pre-installed disk image — the "disk with OS on
 * it" half of the flash-chip-vs-disk split.
 *
 * Extends [NvmeItem] so the existing motherboard NVMe slots accept it
 * without any slot-predicate changes. Carries a default
 * [lekkit.scev.machine.storage.ScevDiskTemplate] registry id; when the
 * parser ([lekkit.scev.machine.MachineSpecParser]) sees this item in an
 * NVMe slot it emits a `DiskSpec` with `templateId` set, and the backend
 * seeds the per-UUID image from that template on first power-on.
 *
 * Default template: [DiskTemplateRegistry.BUILDROOT] — the shipped 16 MiB
 * ext2 rootfs with a minimal Unix directory skeleton, mountable from the
 * guest via `mount -t ext2 /dev/nvme0n1 /mnt`. Future features can
 * register additional templates and construct variant items bound to
 * them without touching this class.
 *
 * ## Item-side behavior
 *
 * - [getOrigin] still returns the template's asset name
 *   (`linux_rootfs.ext2`) for back-compat with anything that reads the
 *   origin as a raw string (legacy `StorageManager.copyImage` direct
 *   path, JSON debug).
 * - [getDefaultTemplateId] exposes the registry id so the parser can
 *   emit `DiskSpec(templateId=...)`.
 *
 * Persistent storage UUID (per-stack `STORAGE_UUID` data component)
 * behaves identically to [NvmeItem]: first installation allocates a
 * UUID, subsequent moves of the same stack reuse the same per-UUID
 * image. What's different is the *initial content* of that image —
 * `StorageManager.copyImage` reads the template's `assetName()` as the
 * origin, copies those bytes into `./scev/images/<uuid>.img`, then hands
 * the image to RVVM's NVMe block device.
 */
class PreloadedNvmeItem(
    props: Properties,
    private val defaultTemplateId: ResourceLocation,
) : NvmeItem(props) {

    /**
     * Template registry id this item type defaults to (ctor-provided).
     * Prefer [getTemplateId] when a stack is available — that picks up
     * per-stack overrides.
     */
    fun getDefaultTemplateId(): ResourceLocation = defaultTemplateId

    /**
     * Per-stack template id: data component wins, falls back to the ctor
     * default. Lets a single registered item surface multiple template
     * variants in the creative tab (one stack per registered template,
     * [ScevDataComponents.DISK_TEMPLATE] differentiating them).
     */
    fun getTemplateId(stack: ItemStack): ResourceLocation =
        stack.get(ScevDataComponents.DISK_TEMPLATE.get()) ?: defaultTemplateId

    /**
     * Origin asset name — derived from the registered template so the
     * legacy "origin is a direct classpath asset" code paths still work
     * when a template's registration is stripped. Returns `null` if the
     * template isn't registered (treat as blank NVMe).
     */
    override fun getOrigin(): String? =
        DiskTemplateRegistry.get(defaultTemplateId)?.assetName() ?: super.getOrigin()

    /**
     * Disk size — derived from the template's declared size. A template
     * that isn't yet registered falls back to [NvmeItem]'s default
     * capacity so the item stays functional during early mod init.
     */
    override fun getSizeMb(): Long =
        DiskTemplateRegistry.get(defaultTemplateId)?.sizeMb() ?: super.getSizeMb()

    /**
     * Display name.
     *
     * Fresh stack (no `STORAGE_UUID` yet → template not applied):
     * `"NVMe Drive (Alpine Linux)"` — the template suffix advertises
     * what the disk will be seeded with on first install.
     *
     * Materialized stack (`STORAGE_UUID` set → bytes on disk):
     * `"NVMe Drive"` — the disk is no longer "preloaded", it's whatever
     * the guest has written to it since, with an identity of its own.
     * The UUID is surfaced in the tooltip by [StorageItem].
     */
    override fun getName(stack: ItemStack): Component {
        val base = Component.translatable(this.getDescriptionId(stack))
        if (getUuid(stack) != null) {
            // Already allocated + seeded; template is historical.
            return base
        }
        val template = DiskTemplateRegistry.get(getTemplateId(stack)) ?: return base
        return Component.empty()
            .append(base)
            .append(Component.literal(" ("))
            .append(template.displayName())
            .append(Component.literal(")"))
    }
}
