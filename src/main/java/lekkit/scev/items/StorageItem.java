/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.items;

import java.util.List;
import java.util.UUID;
import lekkit.scev.main.ScevDataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.jetbrains.annotations.Nullable;

/**
 * Item that tracks a persistent disk image UUID plus origin template & size. Disk images live
 * under {@code ./scev/images/&lt;uuid&gt;.img}.
 */
public class StorageItem extends Item {
    private final String origin;
    private final long sizeMb;

    public StorageItem(Properties props, @Nullable String origin, long sizeMb) {
        super(props);
        this.origin = origin;
        this.sizeMb = sizeMb;
    }

    public StorageItem(Properties props) {
        this(props, null, 0);
    }

    public @Nullable String getOrigin() { return origin; }

    public long getSizeMb() { return sizeMb; }

    public UUID ensureUuid(ItemStack stack) {
        UUID existing = stack.get(ScevDataComponents.STORAGE_UUID.get());
        if (existing != null) return existing;
        UUID fresh = UUID.randomUUID();
        stack.set(ScevDataComponents.STORAGE_UUID.get(), fresh);
        return fresh;
    }

    public @Nullable UUID getUuid(ItemStack stack) {
        return stack.get(ScevDataComponents.STORAGE_UUID.get());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        long mb = getSizeMb();
        if (mb > 0) {
            ScevTooltips.kv(tooltip, "text.scev.capacity", formatSize(mb));
        }
        // Once STORAGE_UUID is set, the disk has been allocated + (for
        // preloaded variants) seeded from the template. Show a short id so
        // players can tell their disks apart in chests / workstations.
        UUID uuid = getUuid(stack);
        if (uuid != null) {
            String shortId = uuid.toString().substring(0, 8);
            ScevTooltips.kv(tooltip, "text.scev.disk_id", shortId, ScevTooltips.MUTED_VALUE_COLOR);
        }
        super.appendHoverText(stack, ctx, tooltip, flag);
    }

    /**
     * Format a size in MiB as "N MiB" for sub-GiB values, "N GiB" for
     * GiB-aligned values, and "N.N GiB" otherwise. Keeps small flash chips
     * readable ("8 MiB", not "0.0 GiB") while letting multi-GiB NVMe drives
     * present sensibly ("2 GiB", not "2048 MiB").
     */
    public static String formatSize(long mb) {
        if (mb < 1024) return mb + " MiB";
        if (mb % 1024 == 0) return (mb / 1024) + " GiB";
        return String.format("%.1f GiB", mb / 1024.0);
    }
}
