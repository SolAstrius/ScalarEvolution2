/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.client;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import lekkit.scev.client.sections.ScevSection;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the wire format of {@link ScevSection} JSON — the shape shipped by
 * addon mods must keep parsing across refactors. Breaking the schema means
 * sibling mods' section files silently vanish from the creative tab.
 */
class ScevSectionCodecTest {

    @BeforeAll
    static void bootstrap() {
        // ComponentSerialization.CODEC depends on registries being available.
        Bootstrap.bootStrap();
    }

    @Test
    @DisplayName("Parses a full section with explicit hex colors")
    void parsesFullSection() {
        JsonObject json = JsonParser.parseString("""
            {
              "priority": 150,
              "title": {
                "text": { "translate": "itemGroup.scev.section.computing" },
                "background": "#bb0c2a52",
                "color": "#ff6bc5ff",
                "secondary_color": "#ff2878bc"
              },
              "sprite": "scev:banner",
              "only_animate_on_hover": true
            }""").getAsJsonObject();

        ScevSection section = ScevSection.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        assertEquals(150, section.priority());
        assertEquals("scev:banner", section.sprite().toString());
        assertTrue(section.animateOnHover());
        assertEquals(0xff6bc5ff, section.title().color());
        assertTrue(section.title().secondaryColor().isPresent());
        assertEquals(0xff2878bc, section.title().secondaryColor().get());
        assertEquals(0xbb0c2a52, section.title().background());
    }

    @Test
    @DisplayName("Missing optional fields fall back to defaults")
    void defaultsForOptionals() {
        JsonObject json = JsonParser.parseString("""
            {
              "title": {
                "text": { "translate": "x.y.z" },
                "background": "#aa000000"
              }
            }""").getAsJsonObject();

        ScevSection section = ScevSection.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        assertEquals(0, section.priority(), "priority defaults to 0");
        assertFalse(section.animateOnHover(), "animation defaults to false");
        assertEquals(ScevSection.DEFAULT_BANNER, section.sprite(),
                "sprite defaults to mod default banner");
        assertTrue(section.title().secondaryColor().isEmpty(),
                "secondary_color absent → Optional.empty()");
        // Default title color is white when omitted; verify the fallback so
        // a typo in the default doesn't silently render invisible text.
        assertEquals(0xFFFFFFFF, section.title().color(), "color defaults to opaque white");
    }

    @Test
    @DisplayName("Invalid hex string falls back to default, does not crash")
    void malformedHexDefaults() {
        // Codec.fieldOf(...).orElse(...) swallows parse errors in the sub-field
        // and substitutes the default. A typo'd color is a cosmetic mistake,
        // not a crash — the addon mod's section still loads with a default
        // black background. Author will notice visually and fix.
        JsonObject json = JsonParser.parseString("""
            {
              "title": {
                "text": { "translate": "x" },
                "background": "not-hex",
                "color": "#ffffff"
              }
            }""").getAsJsonObject();

        var result = ScevSection.CODEC.parse(JsonOps.INSTANCE, json);
        assertTrue(result.result().isPresent(), "malformed hex must still produce a section");
        assertEquals(0xAA000000, result.getOrThrow().title().background(),
                "malformed background must fall back to codec default (0xAA000000)");
    }

    @Test
    @DisplayName("secondaryOrDerived falls back to darkened primary when unset")
    void derivedSecondaryColor() {
        ScevSection.Title t = new ScevSection.Title(
                net.minecraft.network.chat.Component.empty(),
                0xFF_64_96_C8, // primary = (100, 150, 200) ARGB
                java.util.Optional.empty(),
                0x00_00_00_00);
        int derived = t.secondaryOrDerived();
        // Each channel should be ~80% of the primary's channel. Alpha preserved.
        assertEquals(0xFF, (derived >>> 24) & 0xFF);
        assertEquals((int) (100 * 0.8f), (derived >>> 16) & 0xFF);
        assertEquals((int) (150 * 0.8f), (derived >>> 8) & 0xFF);
        assertEquals((int) (200 * 0.8f), derived & 0xFF);
    }

    @Test
    @DisplayName("Comparable sorts by priority ascending")
    void sortByPriority() {
        ScevSection a = parse(10);
        ScevSection b = parse(5);
        ScevSection c = parse(20);
        List<ScevSection> list = new java.util.ArrayList<>(Arrays.asList(a, b, c));
        Collections.sort(list);
        assertEquals(List.of(b, a, c), list);
    }

    private static ScevSection parse(int priority) {
        JsonObject json = JsonParser.parseString("""
            {
              "priority": %d,
              "title": {
                "text": { "translate": "x" },
                "background": "#aa000000"
              }
            }""".formatted(priority)).getAsJsonObject();
        return ScevSection.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
    }
}
