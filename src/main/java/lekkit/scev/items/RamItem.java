/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.items;

import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

public class RamItem extends Item {
    private final int level;

    public RamItem(Properties props, int level) {
        super(props);
        this.level = level;
    }

    public int getMegabytes() {
        return switch (level) {
            case 0 -> 8;
            case 1 -> 16;
            case 2 -> 32;
            case 3 -> 64;
            case 4 -> 128;
            default -> 0;
        };
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        ScevTooltips.kv(tooltip, "text.scev.capacity", getMegabytes() + " MiB");
        super.appendHoverText(stack, ctx, tooltip, flag);
    }
}
