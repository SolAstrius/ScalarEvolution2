/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.gc;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lekkit.scev.items.MotherboardItem;
import lekkit.scev.items.NvmeItem;
import lekkit.scev.main.ScevDataComponents;
import lekkit.scev.main.ScevRegistry;
import lekkit.scev.server.gc.StackInspector;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StackInspector} — the recursive unpacker that turns a
 * single {@link ItemStack} into every {@code STORAGE_UUID} reachable through
 * it.
 *
 * <p>Covers the data-component paths in isolation:
 *
 * <ul>
 *   <li>Direct {@code STORAGE_UUID} on a flat stack.</li>
 *   <li>{@code MOTHERBOARD_INVENTORY} — our 14-slot nested component.</li>
 *   <li>{@code BUNDLE_CONTENTS} — vanilla's bundle fallback path (NeoForge
 *       doesn't auto-register {@code ItemHandler.ITEM} for bundles, so we
 *       read the raw component).</li>
 *   <li>Mixed nesting — motherboard inside a bundle.</li>
 *   <li>Empty / null / no-UUID stacks → no emissions.</li>
 * </ul>
 *
 * <p><b>Not covered here:</b> {@code ItemHandler.ITEM} on shulker boxes. That
 * capability is registered by NeoForge's mod init, not by vanilla's
 * {@link Bootstrap#bootStrap()}, so it's unavailable in a pure-JUnit context.
 * Covered by the shulker GameTest on the live server instead.
 */
class StackInspectorTest {

    @BeforeAll
    static void bootstrap() {
        Bootstrap.bootStrap();
        // Touch the ITEM registry so DataComponents / ScevRegistry entries are
        // actually available. Matches the pattern in StorageUuidTest.
        BuiltInRegistries.ITEM.getClass();
    }

    private static NvmeItem nvme()         { return (NvmeItem)        ScevRegistry.NVME.get(); }
    private static MotherboardItem mb()    { return (MotherboardItem) ScevRegistry.MOTHERBOARD3.get(); }

    /**
     * Collect the UUIDs emitted by inspecting {@code stack}. Helper that
     * wraps the {@link java.util.function.Consumer} plumbing so test bodies
     * stay readable.
     */
    private static Set<UUID> harvest(ItemStack stack) {
        Set<UUID> out = new HashSet<>();
        StackInspector.inspect(stack, out::add);
        return out;
    }

    @Test
    @DisplayName("null stack is a no-op")
    void nullStackNoOp() {
        assertTrue(harvest(null).isEmpty());
    }

    @Test
    @DisplayName("empty stack is a no-op")
    void emptyStackNoOp() {
        assertTrue(harvest(ItemStack.EMPTY).isEmpty());
    }

    @Test
    @DisplayName("stack without STORAGE_UUID emits nothing")
    void stackWithoutUuidEmitsNothing() {
        ItemStack fresh = new ItemStack(nvme());
        assertTrue(harvest(fresh).isEmpty(),
                "a freshly-minted NVMe (no ensureUuid yet) has no UUID to emit");
    }

    @Test
    @DisplayName("flat stack with STORAGE_UUID emits exactly that UUID")
    void flatStackEmitsUuid() {
        ItemStack stack = new ItemStack(nvme());
        UUID u = nvme().ensureUuid(stack);
        Set<UUID> out = harvest(stack);
        assertEquals(1, out.size());
        assertTrue(out.contains(u));
    }

    @Test
    @DisplayName("motherboard with a nested NVMe emits the nested UUID")
    void motherboardNestedNvme() {
        // Build an NVMe with a known UUID.
        ItemStack nvmeStack = new ItemStack(nvme());
        UUID nvmeUuid = nvme().ensureUuid(nvmeStack);

        // Drop it into slot NVME_START of a motherboard.
        ItemStack mbStack = new ItemStack(mb());
        NonNullList<ItemStack> slots = NonNullList.withSize(MotherboardItem.INVENTORY_SIZE, ItemStack.EMPTY);
        slots.set(MotherboardItem.SLOT_NVME_START, nvmeStack);
        mbStack.set(ScevDataComponents.MOTHERBOARD_INVENTORY.get(),
                ItemContainerContents.fromItems(slots));

        // The motherboard itself has no UUID; the nested NVMe does.
        Set<UUID> out = harvest(mbStack);
        assertEquals(Set.of(nvmeUuid), out);
    }

    @Test
    @DisplayName("motherboard with no nested items emits nothing")
    void emptyMotherboardEmitsNothing() {
        ItemStack mbStack = new ItemStack(mb());
        NonNullList<ItemStack> slots = NonNullList.withSize(MotherboardItem.INVENTORY_SIZE, ItemStack.EMPTY);
        mbStack.set(ScevDataComponents.MOTHERBOARD_INVENTORY.get(),
                ItemContainerContents.fromItems(slots));
        assertTrue(harvest(mbStack).isEmpty());
    }

    @Test
    @DisplayName("motherboard with multiple nested disks emits all their UUIDs")
    void motherboardMultipleNvmes() {
        ItemStack nvme1 = new ItemStack(nvme());
        ItemStack nvme2 = new ItemStack(nvme());
        UUID u1 = nvme().ensureUuid(nvme1);
        UUID u2 = nvme().ensureUuid(nvme2);

        ItemStack mbStack = new ItemStack(mb());
        NonNullList<ItemStack> slots = NonNullList.withSize(MotherboardItem.INVENTORY_SIZE, ItemStack.EMPTY);
        slots.set(MotherboardItem.SLOT_NVME_START, nvme1);
        slots.set(MotherboardItem.SLOT_NVME_START + 1, nvme2);
        mbStack.set(ScevDataComponents.MOTHERBOARD_INVENTORY.get(),
                ItemContainerContents.fromItems(slots));

        assertEquals(Set.of(u1, u2), harvest(mbStack));
    }

    @Test
    @DisplayName("bundle with a nested NVMe emits via BUNDLE_CONTENTS")
    void bundleNestedNvme() {
        ItemStack nvmeStack = new ItemStack(nvme());
        UUID nvmeUuid = nvme().ensureUuid(nvmeStack);

        ItemStack bundle = new ItemStack(Items.BUNDLE);
        bundle.set(DataComponents.BUNDLE_CONTENTS,
                new BundleContents(List.of(nvmeStack)));

        assertEquals(Set.of(nvmeUuid), harvest(bundle));
    }

    @Test
    @DisplayName("motherboard-inside-bundle double-nests correctly")
    void motherboardInsideBundle() {
        // Most pathological packaging a player might do: put a motherboard
        // loaded with a preloaded NVMe into a bundle and drop the bundle in a
        // chest. StackInspector must unpack both levels.
        ItemStack nvmeStack = new ItemStack(nvme());
        UUID nvmeUuid = nvme().ensureUuid(nvmeStack);

        ItemStack mbStack = new ItemStack(mb());
        NonNullList<ItemStack> slots = NonNullList.withSize(MotherboardItem.INVENTORY_SIZE, ItemStack.EMPTY);
        slots.set(MotherboardItem.SLOT_NVME_START, nvmeStack);
        mbStack.set(ScevDataComponents.MOTHERBOARD_INVENTORY.get(),
                ItemContainerContents.fromItems(slots));

        ItemStack bundle = new ItemStack(Items.BUNDLE);
        bundle.set(DataComponents.BUNDLE_CONTENTS,
                new BundleContents(List.of(mbStack)));

        assertEquals(Set.of(nvmeUuid), harvest(bundle));
    }

    @Test
    @DisplayName("a flash chip with its own UUID inside a motherboard is also reported")
    void motherboardFlashChipContribution() {
        // Flash chips are StorageItems too — they have UUIDs for the flash
        // backing file. The inspector treats them identically to NVMes: pull
        // the UUID from any nested slot that carries STORAGE_UUID.
        ItemStack flash = new ItemStack(ScevRegistry.FLASH_CHIP.get());
        UUID flashUuid = ((lekkit.scev.items.StorageItem) flash.getItem()).ensureUuid(flash);

        ItemStack nvmeStack = new ItemStack(nvme());
        UUID nvmeUuid = nvme().ensureUuid(nvmeStack);

        ItemStack mbStack = new ItemStack(mb());
        NonNullList<ItemStack> slots = NonNullList.withSize(MotherboardItem.INVENTORY_SIZE, ItemStack.EMPTY);
        slots.set(MotherboardItem.SLOT_FLASH, flash);
        slots.set(MotherboardItem.SLOT_NVME_START, nvmeStack);
        mbStack.set(ScevDataComponents.MOTHERBOARD_INVENTORY.get(),
                ItemContainerContents.fromItems(slots));

        assertEquals(Set.of(flashUuid, nvmeUuid), harvest(mbStack));
    }

    @Test
    @DisplayName("inspect accepts a Consumer that sees every UUID in order added")
    void inspectConsumerCallback() {
        ItemStack a = new ItemStack(nvme());
        ItemStack b = new ItemStack(nvme());
        UUID ua = nvme().ensureUuid(a);
        UUID ub = nvme().ensureUuid(b);

        ItemStack mbStack = new ItemStack(mb());
        NonNullList<ItemStack> slots = NonNullList.withSize(MotherboardItem.INVENTORY_SIZE, ItemStack.EMPTY);
        slots.set(MotherboardItem.SLOT_NVME_START, a);
        slots.set(MotherboardItem.SLOT_NVME_START + 1, b);
        mbStack.set(ScevDataComponents.MOTHERBOARD_INVENTORY.get(),
                ItemContainerContents.fromItems(slots));

        List<UUID> seen = new ArrayList<>();
        StackInspector.inspect(mbStack, seen::add);
        assertEquals(2, seen.size(),
                "consumer called once per UUID; duplicates would indicate a walker bug");
        assertTrue(seen.contains(ua));
        assertTrue(seen.contains(ub));
    }
}
