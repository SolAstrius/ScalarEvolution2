/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.machine.storage;

import static org.junit.jupiter.api.Assertions.*;

import lekkit.scev.items.NvmeItem;
import lekkit.scev.items.PreloadedNvmeItem;
import lekkit.scev.machine.storage.BuildrootDiskTemplate;
import lekkit.scev.machine.storage.DiskTemplateRegistry;
import lekkit.scev.main.ScevRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link PreloadedNvmeItem} — the "NVMe SSD that ships preloaded
 * with a disk template". Verifies:
 *
 * <ol>
 *   <li>{@code PreloadedNvmeItem} is an {@link NvmeItem} subclass so the
 *       motherboard NVMe slot predicate
 *       ({@code MotherboardInventory.expectedKind}) accepts it without
 *       modification.</li>
 *   <li>{@link PreloadedNvmeItem#getDefaultTemplateId()} returns the id
 *       the item was constructed with — that's what the parser reads.</li>
 *   <li>{@link PreloadedNvmeItem#getOrigin()} and
 *       {@link PreloadedNvmeItem#getSizeMb()} reflect the resolved
 *       template's asset name and size once the template is registered.</li>
 *   <li>Missing-template fallback: if the registry doesn't know the id
 *       (mod registration order glitch, registry wiped, etc), the item
 *       still behaves as a blank NVMe rather than throwing. This keeps
 *       a machine's world save openable even if a template mod is removed.</li>
 * </ol>
 *
 * <p>All tests operate on the registered {@link ScevRegistry#NVME_PRELOADED}
 * singleton. We can't instantiate {@link PreloadedNvmeItem} directly in a
 * unit-test context because {@code BuiltInRegistries.ITEM} is frozen after
 * mod registration — {@code new Item(...)} would throw
 * {@code IllegalStateException: Registry is already frozen}. Instead we
 * toggle {@link DiskTemplateRegistry} state to exercise the registered
 * item's resolve-and-fallback path.
 */
class PreloadedNvmeItemTest {

    @BeforeAll
    static void bootstrap() {
        Bootstrap.bootStrap();
        BuiltInRegistries.ITEM.getClass();
    }

    @AfterEach
    void restoreBuiltins() {
        // Every test may have stomped the registry — restore the production
        // state so later tests (in any class) see a populated registry.
        DiskTemplateRegistry.clearForTests();
        DiskTemplateRegistry.registerBuiltins();
    }

    @Test
    @DisplayName("PreloadedNvmeItem extends NvmeItem (motherboard slot predicate compatible)")
    void isNvmeSubclass() {
        // MotherboardInventory.expectedKind returns NvmeItem.class for NVMe
        // slots. PreloadedNvmeItem must pass instanceof NvmeItem or players
        // can't place it in those slots.
        assertTrue(NvmeItem.class.isAssignableFrom(PreloadedNvmeItem.class),
                "PreloadedNvmeItem must extend NvmeItem — otherwise the motherboard "
                        + "slot predicate rejects it at placement time.");
    }

    @Test
    @DisplayName("Registered NVME_PRELOADED item reports ALPINE template id")
    void registeredItemHasAlpineTemplate() {
        PreloadedNvmeItem item = ScevRegistry.NVME_PRELOADED.get();
        assertEquals(DiskTemplateRegistry.ALPINE, item.getDefaultTemplateId(),
                "ScevRegistry.NVME_PRELOADED must be constructed with DiskTemplateRegistry.ALPINE — "
                        + "the preloaded NVMe is the Alpine live-install image. BUILDROOT still exists "
                        + "as a creative-tab variant via the DISK_TEMPLATE component, not as the ctor "
                        + "default. Changing the default? Update ScevRegistry AND this test together.");
    }

    @Test
    @DisplayName("With template registered: getOrigin + getSizeMb come from the template")
    void reflectsRegisteredTemplate() {
        DiskTemplateRegistry.clearForTests();
        DiskTemplateRegistry.registerBuiltins();

        PreloadedNvmeItem item = ScevRegistry.NVME_PRELOADED.get();
        assertEquals(lekkit.scev.machine.storage.AlpineDiskTemplate.ASSET_NAME, item.getOrigin(),
                "getOrigin must come from the resolved template's assetName when the template is registered");
        assertEquals(lekkit.scev.machine.storage.AlpineDiskTemplate.SIZE_MB, item.getSizeMb(),
                "getSizeMb must come from the resolved template's sizeMb when the template is registered");
    }

    @Test
    @DisplayName("Missing-template fallback: item behaves as a blank NVMe")
    void fallbackWhenTemplateMissing() {
        // Simulate the mod init ordering where the item is registered before
        // the template (or the user removes a template-providing mod
        // post-install). The item must NOT throw — a world save with a
        // PreloadedNvmeItem whose template vanished should still open.
        DiskTemplateRegistry.clearForTests();
        PreloadedNvmeItem item = ScevRegistry.NVME_PRELOADED.get();

        assertEquals("rootfs.ext2", item.getOrigin(),
                "When the template isn't registered, getOrigin must fall back to the "
                        + "NvmeItem default ('rootfs.ext2') so the item remains placeable "
                        + "and the world stays openable. Lost rootfs > crashed save.");
        assertEquals(lekkit.scev.items.NvmeItem.SIZE_MB, item.getSizeMb(),
                "Same fallback shape for getSizeMb — matches blank NvmeItem's declared "
                        + "capacity. Keeping the test bound to the constant so a future resize "
                        + "lifts both sides together.");
        // defaultTemplateId is still reported — it's hardcoded on the item,
        // independent of whether the registry currently has it.
        assertEquals(DiskTemplateRegistry.ALPINE, item.getDefaultTemplateId());
    }
}
