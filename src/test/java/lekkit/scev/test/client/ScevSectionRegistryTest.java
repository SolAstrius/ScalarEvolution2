/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.client;

import static org.junit.jupiter.api.Assertions.*;

import lekkit.scev.client.sections.ScevSectionRegistry;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the item→section mapping semantics. The creative tab layer looks
 * up assignments via this registry on every {@code buildContents} — if the
 * contract breaks, items silently vanish from the sectioned tab.
 */
class ScevSectionRegistryTest {

    @Test
    @DisplayName("assign + sectionOf by ResourceLocation round-trips")
    void roundTripById() {
        ResourceLocation itemId = ResourceLocation.parse("testmod:widget_" + System.nanoTime());
        ResourceLocation section = ResourceLocation.parse("scev:computing");
        ScevSectionRegistry.assign(itemId, section);
        assertEquals(section, ScevSectionRegistry.sectionOf(itemId));
    }

    @Test
    @DisplayName("Unassigned item returns null (not thrown)")
    void unassignedReturnsNull() {
        ResourceLocation itemId = ResourceLocation.parse("testmod:never_assigned_" + System.nanoTime());
        assertNull(ScevSectionRegistry.sectionOf(itemId));
    }

    @Test
    @DisplayName("Reassigning overwrites the previous mapping")
    void reassignOverwrites() {
        ResourceLocation itemId = ResourceLocation.parse("testmod:rewritten_" + System.nanoTime());
        ResourceLocation first = ResourceLocation.parse("scev:storage");
        ResourceLocation second = ResourceLocation.parse("scev:expansion");
        ScevSectionRegistry.assign(itemId, first);
        ScevSectionRegistry.assign(itemId, second);
        assertEquals(second, ScevSectionRegistry.sectionOf(itemId),
                "Latest assignment must win — the static init block in ScevRegistry "
                        + "is the last word on section membership.");
    }

    @Test
    @DisplayName("snapshot() reflects all current assignments")
    void snapshotContainsAssignments() {
        ResourceLocation a = ResourceLocation.parse("testmod:snap_a_" + System.nanoTime());
        ResourceLocation b = ResourceLocation.parse("testmod:snap_b_" + System.nanoTime());
        ResourceLocation section = ResourceLocation.parse("scev:crafting");
        ScevSectionRegistry.assign(a, section);
        ScevSectionRegistry.assign(b, section);
        var snap = ScevSectionRegistry.snapshot();
        assertEquals(section, snap.get(a));
        assertEquals(section, snap.get(b));
    }
}
