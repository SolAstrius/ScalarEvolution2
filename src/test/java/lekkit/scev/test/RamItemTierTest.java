/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import lekkit.scev.items.RamItem;
import lekkit.scev.main.ScevRegistry;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the tier → MiB mapping of every {@link RamItem} registered in
 * {@link ScevRegistry}. The capacity doubling across tiers is the user-facing
 * promise ("next stick is twice the RAM"); a silent change here would break
 * {@link lekkit.scev.machine.MachineSpecParser}'s total-RAM sum downstream
 * without surfacing until a player notices their machine got slower.
 *
 * <p>Mirrors {@link SocItemTierTest} — same shape, same intent.
 */
class RamItemTierTest {

    @BeforeAll
    static void bootstrap() {
        // Registries must exist before ScevRegistry.RAM_SODIMM*.get() resolves.
        Bootstrap.bootStrap();
    }

    @Test
    @DisplayName("RAM_SODIMM1: 8 MiB (iron-nugget tier)")
    void tier1Capacity() {
        assertEquals(8, ScevRegistry.RAM_SODIMM1.get().getMegabytes());
    }

    @Test
    @DisplayName("RAM_SODIMM2: 16 MiB (gold-nugget tier)")
    void tier2Capacity() {
        assertEquals(16, ScevRegistry.RAM_SODIMM2.get().getMegabytes());
    }

    @Test
    @DisplayName("RAM_SODIMM3: 32 MiB (gold-ingot tier)")
    void tier3Capacity() {
        assertEquals(32, ScevRegistry.RAM_SODIMM3.get().getMegabytes());
    }

    @Test
    @DisplayName("RAM_SODIMM4: 64 MiB (diamond tier)")
    void tier4Capacity() {
        assertEquals(64, ScevRegistry.RAM_SODIMM4.get().getMegabytes());
    }

    @Test
    @DisplayName("RAM_SODIMM5: 128 MiB (netherite tier)")
    void tier5Capacity() {
        assertEquals(128, ScevRegistry.RAM_SODIMM5.get().getMegabytes());
    }

    @Test
    @DisplayName("Capacity doubles each tier — regression guard on the ladder shape")
    void capacityDoublesPerTier() {
        // Explicit progression check. If someone bumps one tier without
        // touching the others the `x2` invariant breaks and this test
        // catches it louder than per-tier equals — failure points straight
        // at "the ladder slipped".
        int[] expected = {8, 16, 32, 64, 128};
        RamItem[] tiers = {
                ScevRegistry.RAM_SODIMM1.get(),
                ScevRegistry.RAM_SODIMM2.get(),
                ScevRegistry.RAM_SODIMM3.get(),
                ScevRegistry.RAM_SODIMM4.get(),
                ScevRegistry.RAM_SODIMM5.get(),
        };
        for (int i = 0; i < tiers.length; i++) {
            assertEquals(expected[i], tiers[i].getMegabytes(),
                    "tier " + (i + 1) + " should be " + expected[i] + " MiB");
            if (i > 0) {
                assertEquals(expected[i - 1] * 2, tiers[i].getMegabytes(),
                        "tier " + (i + 1) + " must be exactly 2x tier " + i);
            }
        }
    }
}
