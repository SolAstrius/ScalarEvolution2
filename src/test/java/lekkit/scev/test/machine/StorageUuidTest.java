/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.machine;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import lekkit.scev.items.FlashItem;
import lekkit.scev.items.NvmeItem;
import lekkit.scev.items.StorageItem;
import lekkit.scev.main.ScevRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Storage UUIDs are how {@link StorageItem} identifies "which disk image on
 * disk is this ItemStack backed by". The UUID is stored as a data component
 * and must survive:
 *
 * <ul>
 *   <li>Being created on first use ({@code ensureUuid} generates fresh if absent).</li>
 *   <li>Being copied via {@link ItemStack#copy()} — chest transfer, hotbar swap, etc.</li>
 *   <li>Being read back by {@code getUuid} without reallocating.</li>
 * </ul>
 *
 * <p>If the UUID drift silently, players would see their disk contents
 * "reset" when they move a flash/NVMe between inventories.
 */
class StorageUuidTest {

    @BeforeAll
    static void bootstrap() {
        Bootstrap.bootStrap();
        BuiltInRegistries.ITEM.getClass();
    }

    /**
     * Use the already-registered items from ScevRegistry. Constructing new
     * Items in a test fails because {@code BuiltInRegistries} is frozen by
     * bootstrap — the Item ctor would try to register an intrusive holder.
     */
    private static FlashItem flash() { return (FlashItem) ScevRegistry.FLASH_CHIP.get(); }
    private static NvmeItem nvme()   { return (NvmeItem)  ScevRegistry.NVME.get(); }

    @Test
    @DisplayName("ensureUuid allocates a fresh UUID on first use")
    void ensureAllocatesFresh() {
        ItemStack stack = new ItemStack(flash());
        UUID a = flash().ensureUuid(stack);
        assertNotNull(a);
        assertEquals(a, flash().getUuid(stack));
    }

    @Test
    @DisplayName("ensureUuid is idempotent — second call returns the same UUID")
    void ensureIsIdempotent() {
        ItemStack stack = new ItemStack(flash());
        UUID a = flash().ensureUuid(stack);
        UUID b = flash().ensureUuid(stack);
        assertEquals(a, b);
    }

    @Test
    @DisplayName("UUID is preserved across ItemStack.copy()")
    void uuidSurvivesCopy() {
        ItemStack original = new ItemStack(flash());
        UUID assigned = flash().ensureUuid(original);
        ItemStack copy = original.copy();
        assertEquals(assigned, flash().getUuid(copy),
                "stack copy must carry the data component; otherwise disk images orphan");
    }

    @Test
    @DisplayName("Two freshly-created stacks have different UUIDs")
    void freshStacksAreDistinct() {
        ItemStack a = new ItemStack(flash());
        ItemStack b = new ItemStack(flash());
        UUID ua = flash().ensureUuid(a);
        UUID ub = flash().ensureUuid(b);
        assertNotEquals(ua, ub);
    }

    @Test
    @DisplayName("getOrigin returns the expected template names")
    void originsMatch() {
        assertEquals("fw_payload.bin", flash().getOrigin());
        assertEquals("rootfs.ext2", nvme().getOrigin());
    }

    @Test
    @DisplayName("getSizeMb matches the declared flash/nvme sizes")
    void sizesMatch() {
        assertEquals(8, flash().getSizeMb());
        assertEquals(lekkit.scev.items.NvmeItem.SIZE_MB, nvme().getSizeMb(),
                "Blank NvmeItem advertises 1 GiB — if this drifts, the tooltip and the "
                        + "underlying per-UUID image capacity get out of sync again.");
    }

    @Test
    @DisplayName("getUuid returns null on a fresh stack without ensureUuid")
    void unassignedReturnsNull() {
        ItemStack stack = new ItemStack(flash());
        assertNull(flash().getUuid(stack));
    }
}
