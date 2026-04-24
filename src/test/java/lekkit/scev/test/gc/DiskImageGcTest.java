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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToLongFunction;
import lekkit.scev.server.gc.DiskImageGc;
import lekkit.scev.server.gc.DiskImageRegistry;
import lekkit.scev.server.gc.GcPolicy;
import lekkit.scev.server.gc.GcResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link DiskImageGc} — the three deletion paths (event, sweep,
 * purge) and the safety rules they share (protection, live scan, grace).
 *
 * <h2>Test setup</h2>
 *
 * <p>Each test case builds its own temp image dir + in-memory registry +
 * policy + controlled file-clock. Filenames are UUIDs; the content doesn't
 * matter — what matters is the presence/absence of the file and the fake
 * ctime reported by the file-clock.
 *
 * <p>We drive the clock explicitly rather than setting filesystem birth
 * times because:
 * <ul>
 *   <li>File birth-time setattr isn't portable (Linux ext4 has it; macOS APFS
 *       has it but only via undocumented paths from Java).</li>
 *   <li>The point is to verify grace <em>semantics</em>, not a particular
 *       FS's birthtime behaviour.</li>
 * </ul>
 */
class DiskImageGcTest {

    /** Short: 1 minute grace, 7-day retention, 1-hour sweep interval. */
    private static final GcPolicy POLICY = new GcPolicy(
            Duration.ofMinutes(1).toMillis(),
            Duration.ofDays(7).toMillis(),
            Duration.ofHours(1).toMillis());

    /** Arbitrary but stable "now" anchor so test math stays readable. */
    private static final long NOW = 1_000_000_000L;

    /**
     * Helper: touch a {@code <uuid>.img} file under {@code dir}. Returns the
     * path and the UUID for convenience.
     */
    private static Path touch(Path dir, UUID uuid) throws IOException {
        Path p = dir.resolve(uuid + ".img");
        Files.createDirectories(dir);
        Files.writeString(p, "image-bytes");
        return p;
    }

    /** Build a file-clock that maps each UUID's image file to a fixed ctime. */
    private static ToLongFunction<Path> clockFor(Map<UUID, Long> ctimes, long defaultCtime) {
        return file -> {
            String name = file.getFileName().toString();
            if (name.endsWith(".img")) {
                try {
                    UUID u = UUID.fromString(name.substring(0, name.length() - 4));
                    Long t = ctimes.get(u);
                    if (t != null) return t;
                } catch (IllegalArgumentException ignore) {}
            }
            return defaultCtime;
        };
    }

    @Nested
    @DisplayName("Event-driven GC")
    class EventDriven {

        @Test
        @DisplayName("Deletes an unreferenced, old-enough image")
        void deletesOrphan(@TempDir Path dir) throws IOException {
            UUID u = UUID.randomUUID();
            Path img = touch(dir, u);
            DiskImageRegistry reg = DiskImageRegistry.load(dir.resolve("r.json"));
            DiskImageGc gc = new DiskImageGc(dir, reg, POLICY,
                    clockFor(Map.of(u, NOW - Duration.ofHours(2).toMillis()), NOW));

            GcResult r = gc.runEventDriven(List.of(u), Set.of(), NOW);

            assertEquals(Set.of(u), r.deleted());
            assertFalse(Files.exists(img));
            assertFalse(r.dryRun());
        }

        @Test
        @DisplayName("Skips a candidate that appears in liveUuids (clone survived)")
        void skipsLiveCandidate(@TempDir Path dir) throws IOException {
            UUID u = UUID.randomUUID();
            Path img = touch(dir, u);
            DiskImageRegistry reg = DiskImageRegistry.load(dir.resolve("r.json"));
            DiskImageGc gc = new DiskImageGc(dir, reg, POLICY,
                    clockFor(Map.of(u, NOW - Duration.ofHours(2).toMillis()), NOW));

            GcResult r = gc.runEventDriven(List.of(u), Set.of(u), NOW);

            assertTrue(r.deleted().isEmpty());
            assertTrue(Files.exists(img), "live candidate's file must be preserved");
        }

        @Test
        @DisplayName("Skips a protected candidate")
        void skipsProtectedCandidate(@TempDir Path dir) throws IOException {
            UUID u = UUID.randomUUID();
            Path img = touch(dir, u);
            DiskImageRegistry reg = DiskImageRegistry.load(dir.resolve("r.json"));
            reg.protect(u);
            DiskImageGc gc = new DiskImageGc(dir, reg, POLICY,
                    clockFor(Map.of(u, NOW - Duration.ofHours(2).toMillis()), NOW));

            GcResult r = gc.runEventDriven(List.of(u), Set.of(), NOW);

            assertTrue(r.deleted().isEmpty());
            assertTrue(Files.exists(img));
        }

        @Test
        @DisplayName("Skips a candidate whose file is younger than grace")
        void skipsNewborn(@TempDir Path dir) throws IOException {
            UUID u = UUID.randomUUID();
            Path img = touch(dir, u);
            DiskImageRegistry reg = DiskImageRegistry.load(dir.resolve("r.json"));
            // ctime 30 seconds ago; grace is 60s. Not eligible yet.
            DiskImageGc gc = new DiskImageGc(dir, reg, POLICY,
                    clockFor(Map.of(u, NOW - 30_000L), NOW));

            GcResult r = gc.runEventDriven(List.of(u), Set.of(), NOW);

            assertTrue(r.deleted().isEmpty(), "within grace → skip");
            assertTrue(Files.exists(img));
        }

        @Test
        @DisplayName("No-op on missing file (already gone)")
        void skipsMissingFile(@TempDir Path dir) {
            UUID u = UUID.randomUUID();
            DiskImageRegistry reg = DiskImageRegistry.load(dir.resolve("r.json"));
            DiskImageGc gc = new DiskImageGc(dir, reg, POLICY,
                    clockFor(Map.of(u, 0L), NOW));

            GcResult r = gc.runEventDriven(List.of(u), Set.of(), NOW);

            assertTrue(r.deleted().isEmpty(), "no file → nothing to delete, not an error");
        }

        @Test
        @DisplayName("No-op on empty candidate list")
        void skipsEmptyCandidates(@TempDir Path dir) {
            DiskImageRegistry reg = DiskImageRegistry.load(dir.resolve("r.json"));
            DiskImageGc gc = new DiskImageGc(dir, reg, POLICY);
            GcResult r = gc.runEventDriven(List.of(), Set.of(), NOW);
            assertTrue(r.deleted().isEmpty());
            assertEquals(0L, r.bytesFreed());
        }

        @Test
        @DisplayName("Multiple candidates: only non-live ones get deleted")
        void mixedCandidates(@TempDir Path dir) throws IOException {
            UUID dead = UUID.randomUUID();
            UUID alive = UUID.randomUUID();
            touch(dir, dead);
            Path aliveImg = touch(dir, alive);
            DiskImageRegistry reg = DiskImageRegistry.load(dir.resolve("r.json"));

            Map<UUID, Long> ctimes = new HashMap<>();
            long old = NOW - Duration.ofHours(2).toMillis();
            ctimes.put(dead, old);
            ctimes.put(alive, old);
            DiskImageGc gc = new DiskImageGc(dir, reg, POLICY, clockFor(ctimes, NOW));

            GcResult r = gc.runEventDriven(List.of(dead, alive), Set.of(alive), NOW);

            assertEquals(Set.of(dead), r.deleted());
            assertTrue(Files.exists(aliveImg));
        }

        @Test
        @DisplayName("Deletion forgets the UUID from the registry")
        void deletionForgetsFromRegistry(@TempDir Path dir) throws IOException {
            UUID u = UUID.randomUUID();
            touch(dir, u);
            DiskImageRegistry reg = DiskImageRegistry.load(dir.resolve("r.json"));
            reg.observe(u, NOW - 1_000);
            DiskImageGc gc = new DiskImageGc(dir, reg, POLICY,
                    clockFor(Map.of(u, NOW - Duration.ofHours(2).toMillis()), NOW));

            gc.runEventDriven(List.of(u), Set.of(), NOW);
            assertFalse(reg.isTracked(u), "post-delete registry entry must be removed");
        }
    }

    @Nested
    @DisplayName("Sweep GC")
    class Sweep {

        @Test
        @DisplayName("Live UUIDs get lastSeen refreshed, files preserved")
        void liveRefreshesLastSeen(@TempDir Path dir) throws IOException {
            UUID u = UUID.randomUUID();
            Path img = touch(dir, u);
            DiskImageRegistry reg = DiskImageRegistry.load(dir.resolve("r.json"));
            DiskImageGc gc = new DiskImageGc(dir, reg, POLICY,
                    clockFor(Map.of(u, NOW - Duration.ofHours(2).toMillis()), NOW));

            gc.runSweep(Set.of(u), false, NOW);

            assertTrue(Files.exists(img));
            assertEquals(NOW, reg.lastSeen(u, -1));
        }

        @Test
        @DisplayName("First-time-seen unreferenced image is tracked, not deleted")
        void firstTimeSeenJustTracked(@TempDir Path dir) throws IOException {
            UUID u = UUID.randomUUID();
            Path img = touch(dir, u);
            DiskImageRegistry reg = DiskImageRegistry.load(dir.resolve("r.json"));
            DiskImageGc gc = new DiskImageGc(dir, reg, POLICY,
                    clockFor(Map.of(u, NOW - Duration.ofHours(2).toMillis()), NOW));

            GcResult r = gc.runSweep(Set.of(), false, NOW);

            assertTrue(r.deleted().isEmpty(),
                    "newly-discovered orphan gets a lastSeen lease, not a deletion");
            assertTrue(Files.exists(img));
            assertEquals(NOW, reg.lastSeen(u, -1), "lastSeen set to now on discovery");
        }

        @Test
        @DisplayName("Orphan tracked within retention is not deleted")
        void orphanWithinRetentionPreserved(@TempDir Path dir) throws IOException {
            UUID u = UUID.randomUUID();
            Path img = touch(dir, u);
            DiskImageRegistry reg = DiskImageRegistry.load(dir.resolve("r.json"));
            // Observed 3 days ago; retention is 7 days.
            reg.observe(u, NOW - Duration.ofDays(3).toMillis());
            DiskImageGc gc = new DiskImageGc(dir, reg, POLICY,
                    clockFor(Map.of(u, NOW - Duration.ofDays(3).toMillis()), NOW));

            GcResult r = gc.runSweep(Set.of(), false, NOW);

            assertTrue(r.deleted().isEmpty());
            assertTrue(Files.exists(img));
        }

        @Test
        @DisplayName("Orphan tracked past retention is deleted")
        void orphanBeyondRetentionDeleted(@TempDir Path dir) throws IOException {
            UUID u = UUID.randomUUID();
            Path img = touch(dir, u);
            DiskImageRegistry reg = DiskImageRegistry.load(dir.resolve("r.json"));
            // Observed 8 days ago; retention is 7 days. Past the edge.
            reg.observe(u, NOW - Duration.ofDays(8).toMillis());
            DiskImageGc gc = new DiskImageGc(dir, reg, POLICY,
                    clockFor(Map.of(u, NOW - Duration.ofDays(8).toMillis()), NOW));

            GcResult r = gc.runSweep(Set.of(), false, NOW);

            assertEquals(Set.of(u), r.deleted());
            assertFalse(Files.exists(img));
            assertFalse(reg.isTracked(u));
        }

        @Test
        @DisplayName("Protected UUIDs are never swept, even when stale")
        void protectedNotSwept(@TempDir Path dir) throws IOException {
            UUID u = UUID.randomUUID();
            Path img = touch(dir, u);
            DiskImageRegistry reg = DiskImageRegistry.load(dir.resolve("r.json"));
            reg.protect(u);
            reg.observe(u, NOW - Duration.ofDays(30).toMillis()); // way past retention
            DiskImageGc gc = new DiskImageGc(dir, reg, POLICY,
                    clockFor(Map.of(u, NOW - Duration.ofDays(30).toMillis()), NOW));

            GcResult r = gc.runSweep(Set.of(), false, NOW);

            assertTrue(r.deleted().isEmpty());
            assertTrue(Files.exists(img));
            assertTrue(reg.isProtected(u));
        }

        @Test
        @DisplayName("Creation grace protects recent orphan files")
        void creationGraceProtectsRecent(@TempDir Path dir) throws IOException {
            UUID u = UUID.randomUUID();
            Path img = touch(dir, u);
            DiskImageRegistry reg = DiskImageRegistry.load(dir.resolve("r.json"));
            // 30 seconds old — within the 60s grace.
            DiskImageGc gc = new DiskImageGc(dir, reg, POLICY,
                    clockFor(Map.of(u, NOW - 30_000L), NOW));

            GcResult r = gc.runSweep(Set.of(), false, NOW);

            assertTrue(r.deleted().isEmpty());
            assertTrue(Files.exists(img));
            // Not tracked yet either — we don't start retention until after grace.
            assertFalse(reg.isTracked(u), "don't start retention clock for newborns");
        }

        @Test
        @DisplayName("Dry-run reports would-delete set without touching files")
        void dryRunNoSideEffects(@TempDir Path dir) throws IOException {
            UUID u = UUID.randomUUID();
            Path img = touch(dir, u);
            DiskImageRegistry reg = DiskImageRegistry.load(dir.resolve("r.json"));
            reg.observe(u, NOW - Duration.ofDays(8).toMillis());
            DiskImageGc gc = new DiskImageGc(dir, reg, POLICY,
                    clockFor(Map.of(u, NOW - Duration.ofDays(8).toMillis()), NOW));

            GcResult r = gc.runSweep(Set.of(), true, NOW);

            assertTrue(r.dryRun());
            assertEquals(Set.of(u), r.wouldDelete());
            assertTrue(r.deleted().isEmpty(), "dry-run must not actually delete");
            assertTrue(Files.exists(img));
            assertTrue(reg.isTracked(u), "dry-run must not mutate the registry either");
        }

        @Test
        @DisplayName("Non-UUID-named files are ignored")
        void nonUuidFilesIgnored(@TempDir Path dir) throws IOException {
            Files.createDirectories(dir);
            Path stray = dir.resolve("not-a-uuid.img");
            Files.writeString(stray, "hello");
            Path alsoStray = dir.resolve(".registry.json");
            Files.writeString(alsoStray, "{}");

            DiskImageRegistry reg = DiskImageRegistry.load(dir.resolve("r.json"));
            DiskImageGc gc = new DiskImageGc(dir, reg, POLICY);

            GcResult r = gc.runSweep(Set.of(), false, NOW);

            assertTrue(r.deleted().isEmpty());
            assertTrue(Files.exists(stray));
            assertTrue(Files.exists(alsoStray));
        }

        @Test
        @DisplayName("Bytes freed equals sum of deleted file sizes")
        void bytesFreedAccurate(@TempDir Path dir) throws IOException {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(a + ".img"), "a".repeat(100));
            Files.writeString(dir.resolve(b + ".img"), "b".repeat(250));

            DiskImageRegistry reg = DiskImageRegistry.load(dir.resolve("r.json"));
            reg.observe(a, NOW - Duration.ofDays(8).toMillis());
            reg.observe(b, NOW - Duration.ofDays(8).toMillis());
            long old = NOW - Duration.ofDays(8).toMillis();
            DiskImageGc gc = new DiskImageGc(dir, reg, POLICY,
                    clockFor(Map.of(a, old, b, old), NOW));

            GcResult r = gc.runSweep(Set.of(), false, NOW);

            assertEquals(2, r.deleted().size());
            assertEquals(350L, r.bytesFreed());
        }

        @Test
        @DisplayName("Idempotent: second sweep on clean state does nothing")
        void idempotent(@TempDir Path dir) throws IOException {
            UUID u = UUID.randomUUID();
            touch(dir, u);
            DiskImageRegistry reg = DiskImageRegistry.load(dir.resolve("r.json"));
            reg.observe(u, NOW - Duration.ofDays(8).toMillis());
            DiskImageGc gc = new DiskImageGc(dir, reg, POLICY,
                    clockFor(Map.of(u, NOW - Duration.ofDays(8).toMillis()), NOW));

            GcResult first = gc.runSweep(Set.of(), false, NOW);
            GcResult second = gc.runSweep(Set.of(), false, NOW);

            assertEquals(1, first.deleted().size());
            assertEquals(0, second.deleted().size());
            assertEquals(0L, second.bytesFreed());
        }
    }

    @Nested
    @DisplayName("Purge GC")
    class Purge {

        @Test
        @DisplayName("Deletes unreferenced orphan regardless of creation grace")
        void deletesNewborn(@TempDir Path dir) throws IOException {
            UUID u = UUID.randomUUID();
            Path img = touch(dir, u);
            DiskImageRegistry reg = DiskImageRegistry.load(dir.resolve("r.json"));
            // 5 seconds old — well within grace. Sweep would skip; purge doesn't.
            DiskImageGc gc = new DiskImageGc(dir, reg, POLICY,
                    clockFor(Map.of(u, NOW - 5_000L), NOW));

            GcResult r = gc.runPurge(Set.of(), false, NOW);

            assertEquals(Set.of(u), r.deleted());
            assertFalse(Files.exists(img));
        }

        @Test
        @DisplayName("Deletes untracked orphan regardless of retention lease")
        void bypassesRetention(@TempDir Path dir) throws IOException {
            // Untracked (first time we see it). Sweep would give a lease;
            // purge deletes on the spot.
            UUID u = UUID.randomUUID();
            Path img = touch(dir, u);
            DiskImageRegistry reg = DiskImageRegistry.load(dir.resolve("r.json"));
            DiskImageGc gc = new DiskImageGc(dir, reg, POLICY,
                    clockFor(Map.of(u, NOW - Duration.ofHours(2).toMillis()), NOW));

            GcResult r = gc.runPurge(Set.of(), false, NOW);

            assertEquals(Set.of(u), r.deleted());
            assertFalse(Files.exists(img));
        }

        @Test
        @DisplayName("Live UUID is protected")
        void liveProtected(@TempDir Path dir) throws IOException {
            UUID u = UUID.randomUUID();
            Path img = touch(dir, u);
            DiskImageRegistry reg = DiskImageRegistry.load(dir.resolve("r.json"));
            DiskImageGc gc = new DiskImageGc(dir, reg, POLICY,
                    clockFor(Map.of(u, NOW - 5_000L), NOW));

            GcResult r = gc.runPurge(Set.of(u), false, NOW);

            assertTrue(r.deleted().isEmpty());
            assertTrue(Files.exists(img));
        }

        @Test
        @DisplayName("Protected UUID is protected")
        void protectedProtected(@TempDir Path dir) throws IOException {
            UUID u = UUID.randomUUID();
            Path img = touch(dir, u);
            DiskImageRegistry reg = DiskImageRegistry.load(dir.resolve("r.json"));
            reg.protect(u);
            DiskImageGc gc = new DiskImageGc(dir, reg, POLICY,
                    clockFor(Map.of(u, NOW - 5_000L), NOW));

            GcResult r = gc.runPurge(Set.of(), false, NOW);

            assertTrue(r.deleted().isEmpty());
            assertTrue(Files.exists(img));
        }

        @Test
        @DisplayName("Dry-run purge reports would-delete, touches nothing")
        void dryRun(@TempDir Path dir) throws IOException {
            UUID u = UUID.randomUUID();
            Path img = touch(dir, u);
            DiskImageRegistry reg = DiskImageRegistry.load(dir.resolve("r.json"));
            DiskImageGc gc = new DiskImageGc(dir, reg, POLICY);

            GcResult r = gc.runPurge(Set.of(), true, NOW);

            assertTrue(r.dryRun());
            assertEquals(Set.of(u), r.wouldDelete());
            assertTrue(r.deleted().isEmpty());
            assertTrue(Files.exists(img));
        }

        @Test
        @DisplayName("Multiple orphans all deleted in a single call")
        void multipleOrphans(@TempDir Path dir) throws IOException {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            UUID c = UUID.randomUUID();
            touch(dir, a);
            touch(dir, b);
            touch(dir, c);
            DiskImageRegistry reg = DiskImageRegistry.load(dir.resolve("r.json"));
            DiskImageGc gc = new DiskImageGc(dir, reg, POLICY);

            GcResult r = gc.runPurge(Set.of(), false, NOW);

            assertEquals(3, r.deleted().size());
            assertEquals(Set.of(a, b, c), r.deleted());
        }
    }

    @Test
    @DisplayName("onDiskImageCount returns count of .img files under imagesDir")
    void onDiskImageCount(@TempDir Path dir) throws IOException {
        touch(dir, UUID.randomUUID());
        touch(dir, UUID.randomUUID());
        Files.writeString(dir.resolve("not-uuid.img"), "x");
        Files.writeString(dir.resolve("non-img.txt"), "x");

        DiskImageRegistry reg = DiskImageRegistry.load(dir.resolve("r.json"));
        DiskImageGc gc = new DiskImageGc(dir, reg, POLICY);
        assertEquals(2, gc.onDiskImageCount(),
                "only UUID-named .img files count");
    }

    @Test
    @DisplayName("No images dir present: every path is a no-op")
    void missingDirNoOp(@TempDir Path parent) {
        Path missing = parent.resolve("does-not-exist");
        DiskImageRegistry reg = DiskImageRegistry.load(parent.resolve("r.json"));
        DiskImageGc gc = new DiskImageGc(missing, reg, POLICY);
        assertTrue(gc.runSweep(Set.of(), false, NOW).deleted().isEmpty());
        assertTrue(gc.runPurge(Set.of(), false, NOW).deleted().isEmpty());
        assertTrue(gc.runEventDriven(List.of(UUID.randomUUID()), Set.of(), NOW).deleted().isEmpty());
    }
}
