/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.items

import lekkit.scev.main.ScevDataComponents
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.item.Item
import net.minecraft.world.item.Item.TooltipContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag

/**
 * Single-stack item carrying a [Printout] in its
 * [ScevDataComponents.PRINTOUT_CONTENT] data component. Visual
 * rendering (in-hand, item-frame, GUI icon) is delegated to
 * `lekkit.scev.client.render.item.PrintoutItemRenderer`, which
 * resolves the bitmap into a cached `DynamicTexture` and draws a
 * page-shaped quad via the BEWLR path.
 *
 * Stack rules: non-stackable (each printout's content is unique; a
 * stack-of-N would be ambiguous with multi-page printouts). Tooltip
 * surfaces title (if set) and a one-line dimensions/page summary so
 * the player can tell printouts apart in chests without opening
 * each one.
 */
class PrintoutItem(props: Properties) : Item(props) {

    override fun appendHoverText(
        stack: ItemStack,
        ctx: TooltipContext,
        tooltip: MutableList<Component>,
        flag: TooltipFlag,
    ) {
        val p = stack.get(ScevDataComponents.PRINTOUT_CONTENT.get()) ?: run {
            tooltip.add(Component.translatable("tooltip.scev.printout.blank")
                .withStyle(ChatFormatting.GRAY))
            return
        }
        if (p.title.isNotEmpty()) {
            tooltip.add(Component.literal(p.title).withStyle(ChatFormatting.WHITE))
        }
        tooltip.add(Component.translatable(
            "tooltip.scev.printout.dimensions",
            p.width, p.height, p.pageCount
        ).withStyle(ChatFormatting.GRAY))
    }
}
