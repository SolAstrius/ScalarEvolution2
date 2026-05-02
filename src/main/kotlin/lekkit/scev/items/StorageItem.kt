/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.items

import java.util.UUID
import lekkit.scev.main.ScevDataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag

/**
 * Item that tracks a persistent disk image UUID plus origin template &
 * size. Disk images live under `./scev/images/<uuid>.img`.
 */
open class StorageItem @JvmOverloads constructor(
    props: Properties,
    private val baseOrigin: String? = null,
    private val baseSizeMb: Long = 0L,
) : Item(props) {

    open fun getOrigin(): String? = baseOrigin

    open fun getSizeMb(): Long = baseSizeMb

    fun ensureUuid(stack: ItemStack): UUID {
        val existing = stack.get(ScevDataComponents.STORAGE_UUID.get())
        if (existing != null) return existing
        val fresh = UUID.randomUUID()
        stack.set(ScevDataComponents.STORAGE_UUID.get(), fresh)
        return fresh
    }

    fun getUuid(stack: ItemStack): UUID? = stack.get(ScevDataComponents.STORAGE_UUID.get())

    override fun appendHoverText(stack: ItemStack, ctx: TooltipContext, tooltip: MutableList<Component>, flag: TooltipFlag) {
        val mb = getSizeMb()
        if (mb > 0) {
            ScevTooltips.kv(tooltip, "text.scev.capacity", formatSize(mb))
        }
        // Once STORAGE_UUID is set, the disk has been allocated + (for
        // preloaded variants) seeded from the template. Show a short id so
        // players can tell their disks apart in chests / workstations.
        val uuid = getUuid(stack)
        if (uuid != null) {
            val shortId = uuid.toString().substring(0, 8)
            ScevTooltips.kv(tooltip, "text.scev.disk_id", shortId, ScevTooltips.MUTED_VALUE_COLOR)
        }
        super.appendHoverText(stack, ctx, tooltip, flag)
    }

    companion object {
        /**
         * Format a size in MiB as "N MiB" for sub-GiB values, "N GiB" for
         * GiB-aligned values, and "N.N GiB" otherwise. Keeps small flash
         * chips readable ("8 MiB", not "0.0 GiB") while letting multi-GiB
         * NVMe drives present sensibly ("2 GiB", not "2048 MiB").
         */
        @JvmStatic
        fun formatSize(mb: Long): String = when {
            mb < 1024 -> "$mb MiB"
            mb % 1024 == 0L -> "${mb / 1024} GiB"
            else -> "%.1f GiB".format(mb / 1024.0)
        }
    }
}
