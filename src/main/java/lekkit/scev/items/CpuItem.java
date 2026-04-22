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

public class CpuItem extends Item {
    private final int level;
    private final int hartCount;

    public CpuItem(Properties props, int level, int hartCount) {
        super(props);
        this.level = level;
        this.hartCount = hartCount;
    }

    public int getLevel() { return level; }

    public int getHartCount() { return hartCount; }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        ScevTooltips.kv(tooltip, "text.scev.tier", Integer.toString(level));
        ScevTooltips.kv(tooltip, "text.scev.cores", Integer.toString(hartCount));
        super.appendHoverText(stack, ctx, tooltip, flag);
    }
}
