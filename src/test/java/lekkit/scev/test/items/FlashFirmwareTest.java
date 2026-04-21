/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.items;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.JsonOps;
import lekkit.scev.items.FlashFirmware;
import lekkit.scev.machine.firmware.FirmwareRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FlashFirmware is the typed layer on top of FirmwareRegistry. The tests
 * below pin down four contracts the rest of the mod (parser + recipe JSON
 * + tooltip renderer) assumes:
 *
 * <ul>
 *   <li>Every non-BLANK entry resolves to a real FirmwareRegistry id.</li>
 *   <li>BLANK resolves to null (explicit "no firmware" state).</li>
 *   <li>StringRepresentable names are stable and lowercase — recipe JSON
 *       is {@code "scev:firmware_kind": "blinky"}, any case change breaks
 *       existing recipes.</li>
 *   <li>The Codec round-trips every enum value — catches a rename of an
 *       enum value that would silently lose the component on load.</li>
 * </ul>
 */
class FlashFirmwareTest {

    @Test
    @DisplayName("BLANK's id is null (explicit no-firmware state)")
    void blankIsNull() {
        assertNull(FlashFirmware.BLANK.id());
    }

    @Test
    @DisplayName("Every non-BLANK value points at a known FirmwareRegistry id")
    void idsAreKnown() {
        for (FlashFirmware kind : FlashFirmware.values()) {
            if (kind == FlashFirmware.BLANK) continue;
            assertNotNull(kind.id(), "non-BLANK kind " + kind + " must reference an id");
        }
        // Specific mappings — these are part of the public save format, not
        // implementation detail; a silent renumber would break worlds.
        assertEquals(FirmwareRegistry.LINUX,         FlashFirmware.LINUX.id());
        assertEquals(FirmwareRegistry.OPENSBI_ONLY,  FlashFirmware.OPENSBI.id());
        assertEquals(FirmwareRegistry.OPEN_FIRMWARE, FlashFirmware.OPEN_FW.id());
        assertEquals(FirmwareRegistry.BLINKY,        FlashFirmware.BLINKY.id());
    }

    @Test
    @DisplayName("Serialized names are stable lowercase tokens")
    void serializedNamesStable() {
        // These strings appear in recipe JSON and ItemStack component dumps.
        // Changing one without a codec migration breaks saved worlds.
        assertEquals("blank",   FlashFirmware.BLANK.getSerializedName());
        assertEquals("linux",   FlashFirmware.LINUX.getSerializedName());
        assertEquals("opensbi", FlashFirmware.OPENSBI.getSerializedName());
        assertEquals("open_fw", FlashFirmware.OPEN_FW.getSerializedName());
        assertEquals("blinky",  FlashFirmware.BLINKY.getSerializedName());
    }

    @Test
    @DisplayName("Codec round-trips every enum value through JSON")
    void codecRoundTrip() {
        for (FlashFirmware kind : FlashFirmware.values()) {
            var encoded = FlashFirmware.CODEC.encodeStart(JsonOps.INSTANCE, kind).getOrThrow();
            FlashFirmware decoded = FlashFirmware.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
            assertEquals(kind, decoded,
                    "codec should round-trip " + kind + " without loss");
        }
    }
}
