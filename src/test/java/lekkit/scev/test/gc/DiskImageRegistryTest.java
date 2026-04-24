/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.gc;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import lekkit.scev.server.gc.DiskImageRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link DiskImageRegistry}. Covers:
 *
 * <ul>
 *   <li>Load / save round-trip (persistence is the point of the class).</li>
 *   <li>Missing file on load → empty, non-crashing.</li>
 *   <li>Corrupt file on load → empty, non-crashing (don't take the server
 *       down over bad JSON).</li>
 *   <li>Version mismatch → empty, non-crashing.</li>
 *   <li>Protected-set semantics.</li>
 *   <li>{@code observe} vs {@code observeIfMissing} distinction.</li>
 *   <li>Forget removes tracking entries.</li>
 * </ul>
 *
 * <p>No Minecraft bootstrap required — the registry is pure JSON I/O over
 * {@link UUID} / {@code long}.
 */
class DiskImageRegistryTest {

    @Test
    @DisplayName("load returns an empty registry when the file doesn't exist")
    void loadMissingFileIsEmpty(@TempDir Path dir) {
        Path file = dir.resolve("registry.json");
        DiskImageRegistry reg = DiskImageRegistry.load(file);
        assertEquals(0, reg.trackedCount());
        assertEquals(0, reg.protectedCount());
    }

    @Test
    @DisplayName("save then load round-trips entries and protected UUIDs")
    void saveLoadRoundTrip(@TempDir Path dir) {
        Path file = dir.resolve("registry.json");
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID prot = UUID.randomUUID();

        DiskImageRegistry out = DiskImageRegistry.load(file);
        out.observe(a, 1_000L);
        out.observe(b, 2_000L);
        out.protect(prot);
        out.save();

        DiskImageRegistry in = DiskImageRegistry.load(file);
        assertEquals(2, in.trackedCount());
        assertEquals(1_000L, in.lastSeen(a, -1));
        assertEquals(2_000L, in.lastSeen(b, -1));
        assertTrue(in.isProtected(prot));
        assertFalse(in.isProtected(a));
    }

    @Test
    @DisplayName("load with corrupt JSON returns empty registry, doesn't throw")
    void loadCorruptFileIsEmpty(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("registry.json");
        Files.writeString(file, "{ this is not valid json", StandardCharsets.UTF_8);
        DiskImageRegistry reg = DiskImageRegistry.load(file);
        assertEquals(0, reg.trackedCount(),
                "corrupt file must not crash the server; we'd rather lose GC state than crash");
    }

    @Test
    @DisplayName("load with unsupported version returns empty registry")
    void loadWrongVersionIsEmpty(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("registry.json");
        // Deliberately wrong version — guard against a future schema rollback
        // silently reading forward-incompatible data.
        Files.writeString(file,
                "{\"version\": 999, \"entries\": {\"" + UUID.randomUUID() + "\": 1}}",
                StandardCharsets.UTF_8);
        DiskImageRegistry reg = DiskImageRegistry.load(file);
        assertEquals(0, reg.trackedCount());
    }

    @Test
    @DisplayName("load tolerates malformed UUID keys by skipping them")
    void loadSkipsMalformedUuids(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("registry.json");
        UUID good = UUID.randomUUID();
        Files.writeString(file,
                "{\"version\":1,\"entries\":{"
                        + "\"not-a-uuid\":1,"
                        + "\"" + good + "\":42"
                        + "}}",
                StandardCharsets.UTF_8);
        DiskImageRegistry reg = DiskImageRegistry.load(file);
        assertEquals(1, reg.trackedCount(), "malformed key was skipped, valid key kept");
        assertEquals(42L, reg.lastSeen(good, -1));
    }

    @Test
    @DisplayName("observe updates timestamp, observeIfMissing does not")
    void observeVsObserveIfMissing(@TempDir Path dir) {
        Path file = dir.resolve("registry.json");
        DiskImageRegistry reg = DiskImageRegistry.load(file);
        UUID u = UUID.randomUUID();

        reg.observe(u, 100L);
        assertEquals(100L, reg.lastSeen(u, -1));

        reg.observeIfMissing(u, 200L);
        assertEquals(100L, reg.lastSeen(u, -1), "observeIfMissing must not overwrite existing");

        reg.observe(u, 300L);
        assertEquals(300L, reg.lastSeen(u, -1), "observe overwrites");
    }

    @Test
    @DisplayName("observeIfMissing on a fresh UUID records it")
    void observeIfMissingFresh(@TempDir Path dir) {
        Path file = dir.resolve("registry.json");
        DiskImageRegistry reg = DiskImageRegistry.load(file);
        UUID u = UUID.randomUUID();
        reg.observeIfMissing(u, 42L);
        assertEquals(42L, reg.lastSeen(u, -1));
    }

    @Test
    @DisplayName("forget removes the tracking entry")
    void forgetRemoves(@TempDir Path dir) {
        Path file = dir.resolve("registry.json");
        DiskImageRegistry reg = DiskImageRegistry.load(file);
        UUID u = UUID.randomUUID();
        reg.observe(u, 100L);
        assertTrue(reg.isTracked(u));
        reg.forget(u);
        assertFalse(reg.isTracked(u));
        assertEquals(-1L, reg.lastSeen(u, -1));
    }

    @Test
    @DisplayName("protect is idempotent and returns true only on first add")
    void protectIdempotent(@TempDir Path dir) {
        Path file = dir.resolve("registry.json");
        DiskImageRegistry reg = DiskImageRegistry.load(file);
        UUID u = UUID.randomUUID();
        assertTrue(reg.protect(u));
        assertFalse(reg.protect(u), "second add must return false");
        assertTrue(reg.isProtected(u));
    }

    @Test
    @DisplayName("unprotect returns true only when the UUID was protected")
    void unprotectReturnsWhetherRemoved(@TempDir Path dir) {
        Path file = dir.resolve("registry.json");
        DiskImageRegistry reg = DiskImageRegistry.load(file);
        UUID u = UUID.randomUUID();
        assertFalse(reg.unprotect(u), "unprotect of a non-protected UUID returns false");
        reg.protect(u);
        assertTrue(reg.unprotect(u));
        assertFalse(reg.isProtected(u));
    }

    @Test
    @DisplayName("lastSeenCopy returns a defensive copy — mutations don't leak")
    void lastSeenCopyIsDefensive(@TempDir Path dir) {
        Path file = dir.resolve("registry.json");
        DiskImageRegistry reg = DiskImageRegistry.load(file);
        UUID u = UUID.randomUUID();
        reg.observe(u, 100L);
        var copy = reg.lastSeenCopy();
        copy.put(UUID.randomUUID(), 999L);
        assertEquals(1, reg.trackedCount(),
                "mutating the returned map must not affect the registry");
    }

    @Test
    @DisplayName("protectedUuidsCopy returns a defensive copy")
    void protectedUuidsCopyIsDefensive(@TempDir Path dir) {
        Path file = dir.resolve("registry.json");
        DiskImageRegistry reg = DiskImageRegistry.load(file);
        UUID u = UUID.randomUUID();
        reg.protect(u);
        Set<UUID> copy = reg.protectedUuidsCopy();
        copy.add(UUID.randomUUID());
        assertEquals(1, reg.protectedCount());
    }

    @Test
    @DisplayName("save creates parent directories if missing")
    void saveCreatesParentDirs(@TempDir Path dir) {
        Path nested = dir.resolve("a").resolve("b").resolve("registry.json");
        DiskImageRegistry reg = DiskImageRegistry.load(nested);
        reg.observe(UUID.randomUUID(), 1L);
        reg.save();
        assertTrue(Files.isRegularFile(nested));
    }

    @Test
    @DisplayName("save then load is stable across multiple rounds")
    void saveLoadStable(@TempDir Path dir) {
        Path file = dir.resolve("registry.json");
        UUID u = UUID.randomUUID();
        DiskImageRegistry a = DiskImageRegistry.load(file);
        a.observe(u, 42L);
        a.save();

        DiskImageRegistry b = DiskImageRegistry.load(file);
        b.save(); // write back unchanged

        DiskImageRegistry c = DiskImageRegistry.load(file);
        assertEquals(42L, c.lastSeen(u, -1), "value survived two save/load cycles");
    }
}
