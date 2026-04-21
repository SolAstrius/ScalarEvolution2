/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import lekkit.scev.items.SocItem;
import lekkit.scev.main.ScevRegistry;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the spec tuples of the three {@link SocItem} tier registrations.
 * The upcoming MCU-board block will validate acceptance against these exact
 * values; a silent change here (someone flipping rv32im to rv32i, or
 * bumping RAM) would let items through that the board doesn't actually
 * support, or exclude items it should accept. Explicit regression guard.
 */
class SocItemTierTest {

    @BeforeAll
    static void bootstrap() {
        // Registries must exist before ScevRegistry.SOC1.get() resolves.
        Bootstrap.bootStrap();
    }

    @Test
    @DisplayName("SOC1: bare-metal MCU tier — rv32im, 1 hart, 4 KiB")
    void tier1Spec() {
        SocItem s = ScevRegistry.SOC1.get();
        assertEquals(1,        s.getTier());
        assertEquals("rv32im", s.getIsa());
        assertEquals(1,        s.getHartCount());
        assertEquals(4,        s.getEmbeddedRamKib());
    }

    @Test
    @DisplayName("SOC2: MCU+RTOS tier — rv32imac, 1 hart, 256 KiB")
    void tier2Spec() {
        SocItem s = ScevRegistry.SOC2.get();
        assertEquals(2,          s.getTier());
        assertEquals("rv32imac", s.getIsa());
        assertEquals(1,          s.getHartCount());
        assertEquals(256,        s.getEmbeddedRamKib());
    }

    @Test
    @DisplayName("SOC3: embedded Linux tier — rv64imac, 2 harts, 32 MiB")
    void tier3Spec() {
        SocItem s = ScevRegistry.SOC3.get();
        assertEquals(3,          s.getTier());
        assertEquals("rv64imac", s.getIsa());
        assertEquals(2,          s.getHartCount());
        assertEquals(32 * 1024,  s.getEmbeddedRamKib());
    }

    @Test
    @DisplayName("formatRam: sub-MiB → KiB, MiB-aligned → whole MiB, else → decimal MiB")
    void formatRamBuckets() {
        // The pattern mirrors StorageItem.formatSize but one unit down:
        // sub-MiB render as KiB (keeps microcontroller tier readable at
        // 4 KiB, not 0.004 MiB); whole MiB render without a decimal;
        // fractional values get one decimal place.
        assertEquals("4 KiB",      SocItem.formatRam(4));
        assertEquals("256 KiB",    SocItem.formatRam(256));
        assertEquals("1023 KiB",   SocItem.formatRam(1023));
        assertEquals("1 MiB",      SocItem.formatRam(1024));
        assertEquals("32 MiB",     SocItem.formatRam(32 * 1024));
        assertEquals("1.5 MiB",    SocItem.formatRam(1536));
    }
}
