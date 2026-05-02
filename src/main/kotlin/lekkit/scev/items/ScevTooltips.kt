/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.items

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component

/**
 * Shared helpers for formatting Scalar Evolution item tooltips. Keeps the
 * "label: value" rows rendered the same across every item family — the
 * label is translated, the value is coloured, separator spacing is shared.
 *
 * Before this helper landed, every `appendHoverText` hand-assembled the
 * same `Component.translatable(label).append(": ").append(value
 * .withStyle(YELLOW))` chain and small drifts crept in (different colours,
 * missing spaces). Centralising the formatting makes it easy to re-theme
 * every row at once and keeps new tooltips visually matched to old ones.
 */
object ScevTooltips {

    /** Value colour used for numeric / identifier values across all SCEv tooltips. */
    @JvmField val VALUE_COLOR: ChatFormatting = ChatFormatting.YELLOW

    /** Value colour used for secondary / muted values (disk ids, hashes). */
    @JvmField val MUTED_VALUE_COLOR: ChatFormatting = ChatFormatting.DARK_GRAY

    /** Colour used for free-form descriptive lines ("Shift for more" style). */
    @JvmField val DESC_COLOR: ChatFormatting = ChatFormatting.GRAY

    /** Append a `<label>: <value>` row using [VALUE_COLOR]. */
    @JvmStatic
    fun kv(tooltip: MutableList<Component>, labelKey: String, value: String) {
        kv(tooltip, labelKey, Component.literal(value), VALUE_COLOR)
    }

    /** Append a `<label>: <value>` row with an explicit value colour. */
    @JvmStatic
    fun kv(tooltip: MutableList<Component>, labelKey: String, value: String, color: ChatFormatting) {
        kv(tooltip, labelKey, Component.literal(value), color)
    }

    /** Append a `<label>: <value>` row whose value is an existing [Component]. */
    @JvmStatic
    fun kv(tooltip: MutableList<Component>, labelKey: String, value: Component) {
        kv(tooltip, labelKey, value, VALUE_COLOR)
    }

    @JvmStatic
    fun kv(tooltip: MutableList<Component>, labelKey: String, value: Component, color: ChatFormatting) {
        val row = Component.translatable(labelKey)
            .append(Component.literal(": "))
            .append(value.copy().withStyle(color))
        tooltip.add(row)
    }

    /**
     * Append a single grey descriptive line — short one-liners that explain
     * what an item does ("Network adapter card", "Hot on contact").
     */
    @JvmStatic
    fun desc(tooltip: MutableList<Component>, key: String) {
        tooltip.add(Component.translatable(key).withStyle(DESC_COLOR))
    }

    /**
     * Append a grey descriptive line built from a translation key with
     * substitution args (e.g. durability remaining).
     */
    @JvmStatic
    fun desc(tooltip: MutableList<Component>, key: String, vararg args: Any?) {
        tooltip.add(Component.translatable(key, *args).withStyle(DESC_COLOR))
    }
}
