/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.items

import lekkit.scev.main.ScevDataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.Item.TooltipContext

/* ---------------- Teletype paper roll ---------------- */

/**
 * Wound continuous paper roll for a teletype. Carries a
 * [ScevDataComponents.PAPER_LINES_REMAINING] damage-style counter
 * that the teletype decrements per printed line. Loaded into the
 * teletype's paper slot.
 */
class PaperRollItem(props: Properties) : Item(props) {
    override fun isBarVisible(stack: ItemStack): Boolean =
        stack.has(ScevDataComponents.PAPER_LINES_REMAINING.get())
    override fun getBarWidth(stack: ItemStack): Int {
        val lines = stack.getOrDefault(ScevDataComponents.PAPER_LINES_REMAINING.get(),
            ScevDataComponents.PAPER_ROLL_INITIAL_LINES)
        return 1 + 12 * lines / ScevDataComponents.PAPER_ROLL_INITIAL_LINES
    }
    override fun getBarColor(stack: ItemStack): Int = 0xC8B080  // tan / kraft paper
    override fun appendHoverText(stack: ItemStack, ctx: TooltipContext, tooltip: MutableList<Component>, flag: TooltipFlag) {
        val lines = stack.getOrDefault(ScevDataComponents.PAPER_LINES_REMAINING.get(),
            ScevDataComponents.PAPER_ROLL_INITIAL_LINES)
        tooltip.add(Component.translatable("tooltip.scev.paper_roll.lines", lines,
            ScevDataComponents.PAPER_ROLL_INITIAL_LINES))
    }
}

/* ---------------- Ink + ribbon chain ---------------- */

/** Pigment — raw colorant before binding into ink. Today one item;
 *  obtained by shapeless from any item in `#scev:pigment_source`. */
class PigmentItem(props: Properties) : Item(props)

/**
 * Binder — the carrier substance (gum arabic / beeswax / linseed oil
 * historically) that turns dry pigment into useable ink. Combined
 * with [PigmentItem] in the InkMixer to produce [InkJarItem]. Made
 * shapelessly from any item in `#scev:binder` (honey, slimeball,
 * etc).
 */
class BinderItem(props: Properties) : Item(props) {
    override fun appendHoverText(stack: ItemStack, ctx: TooltipContext, tooltip: MutableList<Component>, flag: TooltipFlag) {
        tooltip.add(Component.translatable("tooltip.scev.binder.desc"))
    }
}

/** Bottled ink — pigment + binder, ready to soak into a ribbon. */
class InkJarItem(props: Properties) : Item(props)

/**
 * Spooled cloth ribbon, soaked in ink. Loaded into a teletype's
 * ribbon cassette. Carries a [ScevDataComponents.RIBBON_INK_REMAINING]
 * counter that the teletype decrements per printed character.
 */
class RibbonItem(props: Properties) : Item(props) {
    override fun isBarVisible(stack: ItemStack): Boolean =
        stack.has(ScevDataComponents.RIBBON_INK_REMAINING.get())
    override fun getBarWidth(stack: ItemStack): Int {
        val ink = stack.getOrDefault(ScevDataComponents.RIBBON_INK_REMAINING.get(),
            ScevDataComponents.RIBBON_INITIAL_INK)
        return 1 + 12 * ink / ScevDataComponents.RIBBON_INITIAL_INK
    }
    override fun getBarColor(stack: ItemStack): Int = 0x202020  // black ink
    override fun appendHoverText(stack: ItemStack, ctx: TooltipContext, tooltip: MutableList<Component>, flag: TooltipFlag) {
        val ink = stack.getOrDefault(ScevDataComponents.RIBBON_INK_REMAINING.get(),
            ScevDataComponents.RIBBON_INITIAL_INK)
        tooltip.add(Component.translatable("tooltip.scev.ribbon.ink", ink,
            ScevDataComponents.RIBBON_INITIAL_INK))
    }
}
