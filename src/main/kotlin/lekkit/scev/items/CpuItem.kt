/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.items

import net.minecraft.network.chat.Component
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag

class CpuItem(props: Properties, val level: Int, val hartCount: Int) : Item(props) {

    override fun appendHoverText(stack: ItemStack, ctx: TooltipContext, tooltip: MutableList<Component>, flag: TooltipFlag) {
        ScevTooltips.kv(tooltip, "text.scev.tier", level.toString())
        ScevTooltips.kv(tooltip, "text.scev.cores", hartCount.toString())
        super.appendHoverText(stack, ctx, tooltip, flag)
    }
}
