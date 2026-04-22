/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.machine.storage;

import static org.junit.jupiter.api.Assertions.*;

import lekkit.scev.machine.storage.BuildrootDiskTemplate;
import lekkit.scev.machine.storage.DiskTemplateRegistry;
import lekkit.scev.machine.storage.ScevDiskTemplate;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link DiskTemplateRegistry} contract — same shape as
 * {@code FirmwareRegistryTest}, without any built-ins yet.
 */
class DiskTemplateRegistryTest {

    @BeforeAll
    static void bootstrap() { Bootstrap.bootStrap(); }

    @BeforeEach
    void reset() { DiskTemplateRegistry.clearForTests(); }

    @Test
    @DisplayName("Empty registry: size 0, get null, no ids")
    void emptyRegistry() {
        assertEquals(0, DiskTemplateRegistry.size());
        assertNull(DiskTemplateRegistry.get(ResourceLocation.fromNamespaceAndPath("scev", "any")));
        assertTrue(DiskTemplateRegistry.ids().isEmpty());
    }

    @Test
    @DisplayName("Null id is safe (null return, no NPE)")
    void nullIdIsSafe() {
        assertNull(DiskTemplateRegistry.get(null));
        assertFalse(DiskTemplateRegistry.contains(null));
    }

    @Test
    @DisplayName("register + get + contains")
    void registerAndLookup() {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("testmod", "alpine");
        ScevDiskTemplate tpl = new ToyTemplate("alpine.ext4", 1024);
        DiskTemplateRegistry.register(id, tpl);
        assertSame(tpl, DiskTemplateRegistry.get(id));
        assertTrue(DiskTemplateRegistry.contains(id));
        assertEquals(1, DiskTemplateRegistry.size());
    }

    @Test
    @DisplayName("Duplicate registration keeps the first entry")
    void duplicateIgnored() {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("testmod", "dup");
        ScevDiskTemplate first = new ToyTemplate("first.ext2", 1024);
        ScevDiskTemplate second = new ToyTemplate("second.ext2", 2048);
        DiskTemplateRegistry.register(id, first);
        DiskTemplateRegistry.register(id, second);
        assertSame(first, DiskTemplateRegistry.get(id));
    }

    @Test
    @DisplayName("Validation: empty asset / zero size / null params")
    void validation() {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("testmod", "bad");
        assertThrows(NullPointerException.class,
                () -> DiskTemplateRegistry.register(null, new ToyTemplate("a.ext2", 16)));
        assertThrows(NullPointerException.class,
                () -> DiskTemplateRegistry.register(id, null));
        assertThrows(IllegalArgumentException.class,
                () -> DiskTemplateRegistry.register(id, new ToyTemplate("", 16)),
                "empty asset name is invalid");
        assertThrows(IllegalArgumentException.class,
                () -> DiskTemplateRegistry.register(id, new ToyTemplate("a.ext2", 0)),
                "zero size is invalid");
    }

    @Test
    @DisplayName("registerBuiltins installs BUILDROOT + ALPINE disk templates")
    void builtinsInstallsBuildroot() {
        DiskTemplateRegistry.registerBuiltins();
        assertEquals(2, DiskTemplateRegistry.size(),
                "Two built-in templates today (BUILDROOT + ALPINE). If you added another, "
                        + "update this test AND add targeted coverage for the new template's "
                        + "metadata (assetName/sizeMb/displayName).");
        ScevDiskTemplate buildroot = DiskTemplateRegistry.get(DiskTemplateRegistry.BUILDROOT);
        assertNotNull(buildroot, "BUILDROOT must resolve after registerBuiltins");
        assertSame(BuildrootDiskTemplate.INSTANCE, buildroot,
                "BUILDROOT must point at the BuildrootDiskTemplate singleton — any other "
                        + "instance would mean duplicate registration or a rewritten entry.");

        ScevDiskTemplate alpine = DiskTemplateRegistry.get(DiskTemplateRegistry.ALPINE);
        assertNotNull(alpine, "ALPINE must resolve after registerBuiltins");
        assertSame(lekkit.scev.machine.storage.AlpineDiskTemplate.INSTANCE, alpine);
    }

    @Test
    @DisplayName("registerBuiltins is idempotent")
    void registerBuiltinsIdempotent() {
        DiskTemplateRegistry.registerBuiltins();
        int firstCount = DiskTemplateRegistry.size();
        DiskTemplateRegistry.registerBuiltins();
        assertEquals(firstCount, DiskTemplateRegistry.size(),
                "Second registerBuiltins() call must not re-add entries — duplicate-registration "
                        + "guard protects against crashed-then-retried mod init.");
    }

    // ---- Helpers ------------------------------------------------------------

    private record ToyTemplate(String asset, long size) implements ScevDiskTemplate {
        @Override public String assetName() { return asset; }
        @Override public long sizeMb() { return size; }
        @Override public Component displayName() { return Component.literal("Toy"); }
    }
}
