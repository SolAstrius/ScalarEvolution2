/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.items;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.JsonOps;
import lekkit.scev.items.FirmwareBlob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FirmwareBlob carries player-authored bytes inside an item stack. These
 * tests pin down the three invariants the rest of the system relies on:
 *
 * <ul>
 *   <li>Persistence codec round-trips arbitrary bytes — otherwise flashing
 *       a chip, closing the world, reopening it would corrupt the payload.</li>
 *   <li>Equality is content-based, not reference-based — two stacks with
 *       the same flashed bytes must compare equal so the item-stacking and
 *       menu-slot identity checks work right.</li>
 *   <li>The size cap rejects oversized blobs at construction, before they
 *       can sneak into an ItemStack component and nuke the network packet.</li>
 * </ul>
 */
class FirmwareBlobTest {

    @Test
    @DisplayName("Codec round-trip: empty, small, and full-random blobs")
    void codecRoundTrip() {
        assertRoundTrip(new byte[0]);
        assertRoundTrip(new byte[]{0x01, 0x02, 0x03, 0x04});
        byte[] random = new byte[1024];
        for (int i = 0; i < random.length; i++) random[i] = (byte) (i * 31);
        assertRoundTrip(random);
    }

    private static void assertRoundTrip(byte[] bytes) {
        FirmwareBlob original = new FirmwareBlob(bytes);
        var encoded = FirmwareBlob.CODEC.encodeStart(JsonOps.INSTANCE, original)
                .getOrThrow();
        FirmwareBlob decoded = FirmwareBlob.CODEC.parse(JsonOps.INSTANCE, encoded)
                .getOrThrow();
        assertEquals(original, decoded,
                "codec should round-trip " + bytes.length + " bytes without mutation");
    }

    @Test
    @DisplayName("equals / hashCode are content-based, not reference-based")
    void contentEquality() {
        byte[] a = {1, 2, 3};
        byte[] b = {1, 2, 3};
        assertEquals(new FirmwareBlob(a), new FirmwareBlob(b));
        assertEquals(new FirmwareBlob(a).hashCode(), new FirmwareBlob(b).hashCode());
        assertNotEquals(new FirmwareBlob(a), new FirmwareBlob(new byte[]{1, 2, 4}));
    }

    @Test
    @DisplayName("Size cap rejects over-MAX_SIZE blobs at construction")
    void sizeCapEnforced() {
        byte[] tooBig = new byte[FirmwareBlob.MAX_SIZE + 1];
        assertThrows(IllegalArgumentException.class, () -> new FirmwareBlob(tooBig),
                "MAX_SIZE is a packet-safety invariant; a blob over it must not construct");
    }

    @Test
    @DisplayName("Exactly-MAX_SIZE is the largest accepted blob")
    void sizeCapBoundary() {
        // Boundary case — ensure off-by-one in the check doesn't reject
        // the exact-cap value.
        byte[] maxed = new byte[FirmwareBlob.MAX_SIZE];
        FirmwareBlob blob = new FirmwareBlob(maxed);
        assertEquals(FirmwareBlob.MAX_SIZE, blob.bytes().length);
    }

    @Test
    @DisplayName("copyBytes defends against external mutation")
    void copyBytesDefends() {
        byte[] source = {1, 2, 3};
        FirmwareBlob blob = new FirmwareBlob(source);
        byte[] copy = blob.copyBytes();
        copy[0] = 99;
        assertEquals(1, blob.bytes()[0], "external mutation to copy must not touch blob state");
    }

    @Test
    @DisplayName("isEmpty: empty blob yes; any other size no")
    void isEmpty() {
        assertTrue(new FirmwareBlob(new byte[0]).isEmpty());
        assertFalse(new FirmwareBlob(new byte[]{0}).isEmpty());
    }
}
