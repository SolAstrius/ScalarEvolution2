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

/* ---------------- Pulping / sheet-forming chain ---------------- */

/** Wet pulp coming out of a pulper. Goes into the sheet former. */
class PulpSlurryItem(props: Properties) : Item(props) {
    override fun appendHoverText(stack: ItemStack, ctx: TooltipContext, tooltip: MutableList<Component>, flag: TooltipFlag) {
        tooltip.add(Component.translatable("tooltip.scev.pulp_slurry.desc"))
    }
}

/**
 * Freshly-formed wet paper sheet from the SheetFormer. Carries a
 * [ScevDataComponents.MOISTURE] component (default 100) that the
 * Dryer machine drains tick-by-tick. When fully dried, the dryer
 * outputs a [PaperSheetItem] (separate item — different recipe key
 * downstream).
 */
class WetPaperSheetItem(props: Properties) : Item(props) {
    override fun isBarVisible(stack: ItemStack): Boolean = true
    override fun getBarWidth(stack: ItemStack): Int {
        val moisture = stack.getOrDefault(ScevDataComponents.MOISTURE.get(), 100)
        // Standard MC bar width formula: 13 pixels max, scaled to value.
        return 1 + 12 * moisture / 100
    }
    override fun getBarColor(stack: ItemStack): Int {
        // Cyan→white as it dries (high moisture = blue, dry = pale).
        val moisture = stack.getOrDefault(ScevDataComponents.MOISTURE.get(), 100)
        val factor = moisture / 100f
        // Mix from #80C0FF (wet) to #F0F0F0 (dry).
        val r = (0x80 + (0xF0 - 0x80) * (1 - factor)).toInt()
        val g = (0xC0 + (0xF0 - 0xC0) * (1 - factor)).toInt()
        val b = (0xFF + (0xF0 - 0xFF) * (1 - factor)).toInt()
        return (r shl 16) or (g shl 8) or b
    }
    override fun appendHoverText(stack: ItemStack, ctx: TooltipContext, tooltip: MutableList<Component>, flag: TooltipFlag) {
        val moisture = stack.getOrDefault(ScevDataComponents.MOISTURE.get(), 100)
        tooltip.add(Component.translatable("tooltip.scev.wet_paper_sheet.moisture", moisture))
    }
}

/** Dry paper sheet, ready for winding into a roll or stacking
 *  flat. Output of the Dryer, input to the Winder. */
class PaperSheetItem(props: Properties) : Item(props)

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
