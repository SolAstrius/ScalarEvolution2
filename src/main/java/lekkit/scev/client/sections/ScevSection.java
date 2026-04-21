/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.sections;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import lekkit.scev.main.ScalarEvolution;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.ExtraCodecs;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * A visual section of the creative tab. Sections are defined via JSON at
 * {@code assets/<modid>/scev/sections/<name>.json} and loaded by
 * {@link ScevSectionManager} on datapack reload.
 *
 * <p>Each section has a {@link #priority()} (lower comes first in the tab), a
 * display {@link Title title} that renders on an in-tab banner, and a
 * {@link #sprite()} {@link ResourceLocation GUI sprite} that backgrounds the
 * banner.
 *
 * <p>The data-driven design (inspired by Simulated's {@code SimulatedSection})
 * lets sibling mods contribute their own sections by shipping JSON in their
 * own resource namespace. SCEv doesn't have to know about them at compile
 * time — all that's required is that the mod assigns its items to the
 * section's registry id via
 * {@link ScevSectionRegistry#assign(ResourceLocation, ResourceLocation)}.
 */
public record ScevSection(
        int priority,
        Title title,
        ResourceLocation sprite,
        boolean animateOnHover
) implements Comparable<ScevSection> {

    /** Used when a section omits its {@code sprite} field. */
    public static final ResourceLocation DEFAULT_BANNER =
            ResourceLocation.fromNamespaceAndPath(ScalarEvolution.MODID, "default_banner");

    public static final Codec<ScevSection> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ExtraCodecs.NON_NEGATIVE_INT.fieldOf("priority").orElse(0)
                    .forGetter(ScevSection::priority),
            Title.CODEC.fieldOf("title")
                    .forGetter(ScevSection::title),
            ResourceLocation.CODEC.fieldOf("sprite").orElse(DEFAULT_BANNER)
                    .forGetter(ScevSection::sprite),
            Codec.BOOL.fieldOf("only_animate_on_hover").orElse(false)
                    .forGetter(ScevSection::animateOnHover)
    ).apply(instance, ScevSection::new));

    @Override
    public int compareTo(@NotNull ScevSection other) {
        return Integer.compare(this.priority, other.priority);
    }

    /**
     * Banner title — a localized component with foreground, optional glow
     * secondary color, and background fill.
     *
     * <p>Colors are ARGB ints: {@code 0xAARRGGBB}. The JSON format accepts
     * either the packed int or a {@code "#AARRGGBB"} hex string via
     * {@link #COLOR_CODEC}.
     *
     * @param text         Localized text to render on the banner.
     * @param color        Primary text color (ARGB).
     * @param secondaryColor Optional glow/accent color; falls back to a
     *                     darkened {@code color} when absent.
     * @param background   Text background fill color (ARGB).
     */
    public record Title(Component text, int color, Optional<Integer> secondaryColor, int background) {
        /** Hex-string color codec: {@code "#AARRGGBB"} or bare {@code "AARRGGBB"}. */
        public static final Codec<Integer> COLOR_CODEC = Codec.STRING.comapFlatMap(
                s -> {
                    try {
                        String hex = s.startsWith("#") ? s.substring(1) : s;
                        return com.mojang.serialization.DataResult.success(Integer.parseUnsignedInt(hex, 16));
                    } catch (NumberFormatException e) {
                        return com.mojang.serialization.DataResult.error(() -> "Invalid color hex: " + s);
                    }
                },
                i -> String.format("#%08x", i));

        public static final Codec<Title> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ComponentSerialization.CODEC.fieldOf("text").forGetter(Title::text),
                COLOR_CODEC.fieldOf("color").orElse(0xFFFFFFFF).forGetter(Title::color),
                COLOR_CODEC.optionalFieldOf("secondary_color").forGetter(Title::secondaryColor),
                COLOR_CODEC.fieldOf("background").orElse(0xAA000000).forGetter(Title::background)
        ).apply(instance, Title::new));

        /**
         * Secondary color with a default derivation: when absent, returns
         * {@link #color} darkened to ~80% brightness per channel. Cheap enough
         * to compute on every render; cached in the banner-draw path.
         */
        public int secondaryOrDerived() {
            return secondaryColor.orElseGet(() -> darken(color, 0.8f));
        }

        private static int darken(int argb, float factor) {
            int a = (argb >>> 24) & 0xFF;
            int r = (int) (((argb >>> 16) & 0xFF) * factor);
            int g = (int) (((argb >>> 8) & 0xFF) * factor);
            int b = (int) ((argb & 0xFF) * factor);
            return (a << 24) | (r << 16) | (g << 8) | b;
        }
    }
}
