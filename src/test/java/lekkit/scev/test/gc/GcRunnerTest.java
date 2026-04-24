/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.gc;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lekkit.scev.server.gc.DiskImageGc;
import lekkit.scev.server.gc.DiskImageRegistry;
import lekkit.scev.server.gc.DiskImageScanner;
import lekkit.scev.server.gc.GcPolicy;
import lekkit.scev.server.gc.GcResult;
import lekkit.scev.server.gc.GcRunner;
import lekkit.scev.server.gc.ScannerRegistry;
import lekkit.scev.server.gc.ScevGc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration tests for {@link GcRunner} (the scanner-plus-orchestrator
 * facade) and {@link ScevGc} (the active-instance holder). Drives the public
 * surface that event handlers and commands use without requiring a real
 * {@code MinecraftServer} — scanners receive a null server, the built-in
 * ones no-op, and the test registers its own fake to populate the live set.
 */
class GcRunnerTest {

    private static final GcPolicy POLICY = new GcPolicy(
            Duration.ofMinutes(1).toMillis(),
            Duration.ofDays(7).toMillis(),
            Duration.ofHours(1).toMillis());

    @BeforeEach
    void reset() { ScannerRegistry.clearForTests(); ScevGc.uninstall(); }

    @AfterEach
    void tearDown() { ScannerRegistry.clearForTests(); ScevGc.uninstall(); }

    private static Path touch(Path dir, UUID uuid) throws IOException {
        Files.createDirectories(dir);
        Path p = dir.resolve(uuid + ".img");
        Files.writeString(p, "x");
        return p;
    }

    @Test
    @DisplayName("GcRunner.event runs registered scanners, respects protection via live set")
    void eventConsultsRegisteredScanners(@TempDir Path dir) throws IOException {
        UUID alive = UUID.randomUUID();
        UUID dead = UUID.randomUUID();
        touch(dir, alive);
        touch(dir, dead);
        DiskImageRegistry reg = DiskImageRegistry.load(dir.resolve("r.json"));
        DiskImageGc gc = new DiskImageGc(dir, reg, POLICY,
                file -> System.currentTimeMillis() - Duration.ofHours(2).toMillis());

        // Register a fake scanner that reports `alive` as live.
        ScannerRegistry.register(ctx -> ctx.addLive(alive));

        GcResult r = GcRunner.event(gc, null, List.of(alive, dead), null);

        assertEquals(1, r.affected());
        assertTrue(r.deleted().contains(dead));
        assertFalse(r.deleted().contains(alive), "scanner reported alive → skipped");
    }

    @Test
    @DisplayName("GcRunner.sweep refreshes live UUIDs' lastSeen")
    void sweepRefreshesLastSeen(@TempDir Path dir) throws IOException {
        UUID u = UUID.randomUUID();
        touch(dir, u);
        DiskImageRegistry reg = DiskImageRegistry.load(dir.resolve("r.json"));
        reg.observe(u, 1L); // stale
        DiskImageGc gc = new DiskImageGc(dir, reg, POLICY);

        ScannerRegistry.register(ctx -> ctx.addLive(u));
        GcRunner.sweep(gc, null, false);

        long nowish = System.currentTimeMillis();
        long seen = reg.lastSeen(u, -1);
        assertTrue(Math.abs(nowish - seen) < 5_000L,
                "live UUID lastSeen should be refreshed to ~now, got " + seen);
    }

    @Test
    @DisplayName("GcRunner.purge deletes everything not in live set")
    void purgeSweepsOrphans(@TempDir Path dir) throws IOException {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        touch(dir, a);
        touch(dir, b);
        touch(dir, c);
        DiskImageRegistry reg = DiskImageRegistry.load(dir.resolve("r.json"));
        DiskImageGc gc = new DiskImageGc(dir, reg, POLICY);

        // b is "live" — scanner reports it.
        ScannerRegistry.register(ctx -> ctx.addLive(b));

        GcResult r = GcRunner.purge(gc, null, false);

        assertEquals(2, r.deleted().size());
        assertTrue(r.deleted().contains(a));
        assertTrue(r.deleted().contains(c));
        assertFalse(r.deleted().contains(b));
    }

    @Test
    @DisplayName("Scanner exception is logged and doesn't break the run")
    void scannerExceptionContained(@TempDir Path dir) throws IOException {
        UUID u = UUID.randomUUID();
        touch(dir, u);
        DiskImageRegistry reg = DiskImageRegistry.load(dir.resolve("r.json"));
        DiskImageGc gc = new DiskImageGc(dir, reg, POLICY);

        // A scanner that throws, followed by one that reports the UUID.
        // The throwing scanner should be caught; the next one should still run.
        ScannerRegistry.register(ctx -> { throw new RuntimeException("boom"); });
        ScannerRegistry.register(ctx -> ctx.addLive(u));

        // Should not throw.
        GcResult r = GcRunner.purge(gc, null, false);
        assertTrue(r.deleted().isEmpty(), "second scanner's addLive should still have protected u");
    }

    @Test
    @DisplayName("ScevGc.active returns installed instance")
    void scevGcHolder(@TempDir Path dir) {
        assertNull(ScevGc.active(), "nothing installed");
        DiskImageRegistry reg = DiskImageRegistry.load(dir.resolve("r.json"));
        DiskImageGc gc = new DiskImageGc(dir, reg, POLICY);
        ScevGc.install(gc);
        assertSame(gc, ScevGc.active());
        ScevGc.uninstall();
        assertNull(ScevGc.active());
    }

    @Test
    @DisplayName("ScevGc.install replaces previous instance")
    void scevGcReplacesPrior(@TempDir Path dir) {
        DiskImageRegistry reg = DiskImageRegistry.load(dir.resolve("r.json"));
        DiskImageGc first = new DiskImageGc(dir, reg, POLICY);
        DiskImageGc second = new DiskImageGc(dir, reg, POLICY);
        ScevGc.install(first);
        ScevGc.install(second);
        assertSame(second, ScevGc.active());
    }

    @Test
    @DisplayName("ScanContext.excludeEntity filters subsequent entity-UUID checks")
    void excludeEntityPropagates() {
        var ctx = new lekkit.scev.server.gc.ScanContext(null);
        UUID entity = UUID.randomUUID();
        assertFalse(ctx.isEntityExcluded(entity));
        ctx.excludeEntity(entity);
        assertTrue(ctx.isEntityExcluded(entity));
        assertFalse(ctx.isEntityExcluded(UUID.randomUUID()));
    }
}
