/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.items;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Shared helpers for formatting Scalar Evolution item tooltips. Keeps the
 * "label: value" rows rendered the same across every item family — the
 * label is translated, the value is coloured, separator spacing is shared.
 *
 * <p>Before this helper landed, every {@code appendHoverText} hand-assembled
 * the same {@code Component.translatable(label).append(": ").append(value
 * .withStyle(YELLOW))} chain and small drifts crept in (different colours,
 * missing spaces). Centralising the formatting makes it easy to re-theme
 * every row at once and keeps new tooltips visually matched to old ones.
 */
public final class ScevTooltips {
    private ScevTooltips() {}

    /** Value colour used for numeric / identifier values across all SCEv tooltips. */
    public static final ChatFormatting VALUE_COLOR = ChatFormatting.YELLOW;

    /** Value colour used for secondary / muted values (disk ids, hashes). */
    public static final ChatFormatting MUTED_VALUE_COLOR = ChatFormatting.DARK_GRAY;

    /** Colour used for free-form descriptive lines ("Shift for more" style). */
    public static final ChatFormatting DESC_COLOR = ChatFormatting.GRAY;

    /** Append a {@code "<label>: <value>"} row using {@link #VALUE_COLOR}. */
    public static void kv(List<Component> tooltip, String labelKey, String value) {
        kv(tooltip, labelKey, Component.literal(value), VALUE_COLOR);
    }

    /** Append a {@code "<label>: <value>"} row with an explicit value colour. */
    public static void kv(List<Component> tooltip, String labelKey, String value, ChatFormatting color) {
        kv(tooltip, labelKey, Component.literal(value), color);
    }

    /** Append a {@code "<label>: <value>"} row whose value is an existing {@link Component}. */
    public static void kv(List<Component> tooltip, String labelKey, Component value) {
        kv(tooltip, labelKey, value, VALUE_COLOR);
    }

    public static void kv(List<Component> tooltip, String labelKey, Component value, ChatFormatting color) {
        MutableComponent row = Component.translatable(labelKey)
                .append(Component.literal(": "))
                .append(value.copy().withStyle(color));
        tooltip.add(row);
    }

    /**
     * Append a single grey descriptive line — short one-liners that
     * explain what an item does ("Network adapter card", "Hot on contact").
     */
    public static void desc(List<Component> tooltip, String key) {
        tooltip.add(Component.translatable(key).withStyle(DESC_COLOR));
    }

    /**
     * Append a grey descriptive line built from a translation key with
     * substitution args (e.g. durability remaining).
     */
    public static void desc(List<Component> tooltip, String key, Object... args) {
        tooltip.add(Component.translatable(key, args).withStyle(DESC_COLOR));
    }
}
