/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.items;

import java.util.UUID;
import lekkit.scev.main.ScevDataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
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
}
