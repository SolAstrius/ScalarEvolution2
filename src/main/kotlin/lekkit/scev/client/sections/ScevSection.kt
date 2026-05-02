/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.sections

import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.codecs.RecordCodecBuilder
import java.util.Optional
import lekkit.scev.main.ScalarEvolution
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ComponentSerialization
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.ExtraCodecs

/**
 * A visual section of the creative tab. Sections are defined via JSON at
 * `assets/<modid>/scev/sections/<name>.json` and loaded by
 * [ScevSectionManager] on datapack reload.
 *
 * Each section has a [priority] (lower comes first), a display [Title]
 * that renders on an in-tab banner, and a [sprite] GUI sprite that
 * backgrounds the banner.
 *
 * The data-driven design lets sibling mods contribute their own sections
 * by shipping JSON in their own resource namespace. SCEv doesn't have to
 * know about them at compile time — all that's required is that the mod
 * assigns its items to the section's registry id via
 * [ScevSectionRegistry.assign].
 */
data class ScevSection(
    @get:JvmName("priority")       val priority: Int,
    @get:JvmName("title")          val title: Title,
    @get:JvmName("sprite")         val sprite: ResourceLocation,
    @get:JvmName("animateOnHover") val animateOnHover: Boolean,
) : Comparable<ScevSection> {

    override fun compareTo(other: ScevSection): Int = priority.compareTo(other.priority)

    /**
     * Banner title — a localized component with foreground, optional glow
     * secondary color, and background fill.
     *
     * Colors are ARGB ints (`0xAARRGGBB`). The JSON format accepts either
     * the packed int or a `"#AARRGGBB"` hex string via [COLOR_CODEC].
     */
    data class Title(
        @get:JvmName("text")           val text: Component,
        @get:JvmName("color")          val color: Int,
        @get:JvmName("secondaryColor") val secondaryColor: Optional<Int>,
        @get:JvmName("background")     val background: Int,
    ) {
        /**
         * Secondary color with a default derivation: when absent, returns
         * [color] darkened to ~80% per channel. Cheap; cached in the
         * banner-draw path.
         */
        fun secondaryOrDerived(): Int = secondaryColor.orElseGet { darken(color, 0.8f) }

        companion object {
            /** Hex-string color codec: `"#AARRGGBB"` or bare `"AARRGGBB"`. */
            @JvmField val COLOR_CODEC: Codec<Int> = Codec.STRING.comapFlatMap(
                { s ->
                    try {
                        val hex = if (s.startsWith("#")) s.substring(1) else s
                        DataResult.success(Integer.parseUnsignedInt(hex, 16))
                    } catch (e: NumberFormatException) {
                        DataResult.error { "Invalid color hex: $s" }
                    }
                },
                { i -> "#%08x".format(i) },
            )

            @JvmField val CODEC: Codec<Title> = RecordCodecBuilder.create { instance ->
                instance.group(
                    ComponentSerialization.CODEC.fieldOf("text").forGetter(Title::text),
                    COLOR_CODEC.fieldOf("color").orElse(0xFFFFFFFF.toInt()).forGetter(Title::color),
                    COLOR_CODEC.optionalFieldOf("secondary_color").forGetter(Title::secondaryColor),
                    COLOR_CODEC.fieldOf("background").orElse(0xAA000000.toInt()).forGetter(Title::background),
                ).apply(instance, ::Title)
            }

            private fun darken(argb: Int, factor: Float): Int {
                val a = (argb ushr 24) and 0xFF
                val r = (((argb ushr 16) and 0xFF) * factor).toInt()
                val g = (((argb ushr 8) and 0xFF) * factor).toInt()
                val b = ((argb and 0xFF) * factor).toInt()
                return (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    companion object {
        /** Used when a section omits its `sprite` field. */
        @JvmField val DEFAULT_BANNER: ResourceLocation =
            ResourceLocation.fromNamespaceAndPath(ScalarEvolution.MODID, "default_banner")

        @JvmField val CODEC: Codec<ScevSection> = RecordCodecBuilder.create { instance ->
            instance.group(
                ExtraCodecs.NON_NEGATIVE_INT.fieldOf("priority").orElse(0).forGetter(ScevSection::priority),
                Title.CODEC.fieldOf("title").forGetter(ScevSection::title),
                ResourceLocation.CODEC.fieldOf("sprite").orElse(DEFAULT_BANNER).forGetter(ScevSection::sprite),
                Codec.BOOL.fieldOf("only_animate_on_hover").orElse(false).forGetter(ScevSection::animateOnHover),
            ).apply(instance, ::ScevSection)
        }
    }
}
