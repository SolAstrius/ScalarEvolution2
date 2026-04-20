/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.items;

import java.util.List;
import net.minecraft.ChatFormatting;
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
        tooltip.add(Component.translatable("text.scev.cores")
                .append(Component.literal(": "))
                .append(Component.literal(Integer.toString(hartCount)).withStyle(ChatFormatting.YELLOW)));
        super.appendHoverText(stack, ctx, tooltip, flag);
    }
}
