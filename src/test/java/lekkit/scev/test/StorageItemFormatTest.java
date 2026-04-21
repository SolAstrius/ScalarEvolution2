/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import lekkit.scev.items.StorageItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the capacity-formatter used by {@link StorageItem#appendHoverText}.
 * Nobody wants to read "2048 MiB" on an NVMe tooltip, and nobody wants to
 * read "0.0 GiB" on an 8 MiB flash chip. The format switches at the 1 GiB
 * boundary; this test guards the switch points and the fractional fallback.
 */
class StorageItemFormatTest {

    @Test
    @DisplayName("Sub-GiB values render as MiB")
    void subGibAsMib() {
        assertEquals("1 MiB", StorageItem.formatSize(1));
        assertEquals("8 MiB", StorageItem.formatSize(8));
        assertEquals("512 MiB", StorageItem.formatSize(512));
        assertEquals("1023 MiB", StorageItem.formatSize(1023));
    }

    @Test
    @DisplayName("GiB-aligned values render as whole GiB")
    void gibAlignedAsWholeGib() {
        assertEquals("1 GiB", StorageItem.formatSize(1024));
        assertEquals("2 GiB", StorageItem.formatSize(2048));
        assertEquals("4 GiB", StorageItem.formatSize(4096));
        assertEquals("16 GiB", StorageItem.formatSize(16384));
    }

    @Test
    @DisplayName("Non-aligned GiB values render with one decimal")
    void nonAlignedFractionalGib() {
        assertEquals("1.5 GiB", StorageItem.formatSize(1536));
        assertEquals("2.5 GiB", StorageItem.formatSize(2560));
        assertEquals("3.5 GiB", StorageItem.formatSize(3584));
    }

    @Test
    @DisplayName("Zero renders as 0 MiB (tooltip caller is expected to skip zero)")
    void zeroAsMib() {
        assertEquals("0 MiB", StorageItem.formatSize(0));
    }
}
