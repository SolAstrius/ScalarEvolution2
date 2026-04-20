/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.machine.firmware;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import lekkit.scev.machine.firmware.FirmwareRegistry;
import lekkit.scev.machine.firmware.ScevFirmware;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link FirmwareRegistry} — the static registry that turns a
 * {@link ResourceLocation} into a {@link ScevFirmware} implementation.
 *
 * <p>These tests pin down the three invariants the production code relies on:
 * <ol>
 *   <li>{@link FirmwareRegistry#registerBuiltins()} installs all first-party
 *       firmwares; every built-in id resolves to a non-null entry with
 *       payloads.</li>
 *   <li>Duplicate registrations don't overwrite (matches Forge's
 *       DeferredRegister behavior so a crashed-then-retried mod init
 *       doesn't silently swap one firmware for another).</li>
 *   <li>Lookup is null-safe: missing / null ids return null, they don't
 *       throw. The backend relies on this to fall through to the demo
 *       bootrom instead of crashing.</li>
 * </ol>
 */
class FirmwareRegistryTest {

    @BeforeAll
    static void bootstrap() {
        Bootstrap.bootStrap(); // ResourceLocation.parse needs registries ready
    }

    @BeforeEach
    void resetRegistry() {
        FirmwareRegistry.clearForTests();
    }

    @Test
    @DisplayName("Empty registry: get / contains / size / ids")
    void emptyRegistry() {
        assertEquals(0, FirmwareRegistry.size());
        assertFalse(FirmwareRegistry.contains(FirmwareRegistry.LINUX));
        assertNull(FirmwareRegistry.get(FirmwareRegistry.LINUX));
        assertTrue(FirmwareRegistry.ids().isEmpty());
    }

    @Test
    @DisplayName("Null id: get returns null, contains returns false (null-safe)")
    void nullIdIsSafe() {
        // Backend code paths call get(fw.firmwareId()) which can be null
        // when the FirmwareSpec uses the direct-origin path. Must not NPE.
        assertNull(FirmwareRegistry.get(null));
        assertFalse(FirmwareRegistry.contains(null));
    }

    @Test
    @DisplayName("register + get + contains + size roundtrip")
    void registerAndLookup() {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("testmod", "toy");
        ScevFirmware toy = new ToyFirmware("toy-boot.bin");

        assertFalse(FirmwareRegistry.contains(id));
        FirmwareRegistry.register(id, toy);
        assertTrue(FirmwareRegistry.contains(id));
        assertSame(toy, FirmwareRegistry.get(id));
        assertEquals(1, FirmwareRegistry.size());
        assertTrue(FirmwareRegistry.ids().contains(id));
    }

    @Test
    @DisplayName("Duplicate registration keeps the first entry (doesn't overwrite)")
    void duplicateIgnored() {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("testmod", "dup");
        ScevFirmware first = new ToyFirmware("first.bin");
        ScevFirmware second = new ToyFirmware("second.bin");

        FirmwareRegistry.register(id, first);
        FirmwareRegistry.register(id, second); // should be ignored

        assertSame(first, FirmwareRegistry.get(id),
                "Duplicate registration must keep the first entry — mods that "
                        + "accidentally re-register a firmware shouldn't swap "
                        + "production firmware with a test stub.");
        assertEquals(1, FirmwareRegistry.size());
    }

    @Test
    @DisplayName("Null id / null firmware / null payloads -> IllegalArgumentException")
    void validation() {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("testmod", "bad");
        assertThrows(NullPointerException.class,
                () -> FirmwareRegistry.register(null, new ToyFirmware("a")));
        assertThrows(NullPointerException.class,
                () -> FirmwareRegistry.register(id, null));
        assertThrows(IllegalArgumentException.class,
                () -> FirmwareRegistry.register(id, new EmptyFirmware()),
                "A firmware with no payloads is useless; registry must reject it");
    }

    @Test
    @DisplayName("registerBuiltins installs LINUX + OPENSBI_ONLY + OPEN_FIRMWARE")
    void registerBuiltinsInstallsAllThree() {
        FirmwareRegistry.registerBuiltins();

        assertTrue(FirmwareRegistry.contains(FirmwareRegistry.LINUX),
                "LINUX must be registered by registerBuiltins (the default flash-chip firmware)");
        assertTrue(FirmwareRegistry.contains(FirmwareRegistry.OPENSBI_ONLY));
        assertTrue(FirmwareRegistry.contains(FirmwareRegistry.OPEN_FIRMWARE));
        assertEquals(3, FirmwareRegistry.size());

        // Every built-in must have at least one payload (BOOTROM) or the
        // backend's assertion-free load loop produces a broken machine.
        for (ResourceLocation id : FirmwareRegistry.ids()) {
            ScevFirmware fw = FirmwareRegistry.get(id);
            assertNotNull(fw, "get(" + id + ") returned null after registration");
            assertNotNull(fw.payloads(), "firmware " + id + " has null payloads");
            assertFalse(fw.payloads().isEmpty(), "firmware " + id + " has no payloads");
            assertNotNull(fw.displayName(), "firmware " + id + " has null displayName");
        }
    }

    @Test
    @DisplayName("registerBuiltins is idempotent (safe to call twice)")
    void registerBuiltinsIdempotent() {
        FirmwareRegistry.registerBuiltins();
        int firstCount = FirmwareRegistry.size();
        FirmwareRegistry.registerBuiltins(); // dup warnings, no growth
        assertEquals(firstCount, FirmwareRegistry.size(),
                "Second registerBuiltins() must not re-add entries — duplicate-registration "
                        + "guard is what keeps production firmware stable across multiple init calls");
    }

    @Test
    @DisplayName("clearForTests wipes all entries")
    void clearWorks() {
        FirmwareRegistry.registerBuiltins();
        assertTrue(FirmwareRegistry.size() > 0);
        FirmwareRegistry.clearForTests();
        assertEquals(0, FirmwareRegistry.size());
        assertNull(FirmwareRegistry.get(FirmwareRegistry.LINUX));
    }

    // ---- Test helpers -------------------------------------------------------

    private static final class ToyFirmware implements ScevFirmware {
        private final List<Payload> payloads;
        ToyFirmware(String asset) {
            this.payloads = List.of(new Payload(Payload.Kind.BOOTROM, asset));
        }
        @Override public List<Payload> payloads() { return payloads; }
        @Override public Component displayName() { return Component.literal("Toy"); }
    }

    private static final class EmptyFirmware implements ScevFirmware {
        @Override public List<Payload> payloads() { return List.of(); }
        @Override public Component displayName() { return Component.literal("Empty"); }
    }
}
