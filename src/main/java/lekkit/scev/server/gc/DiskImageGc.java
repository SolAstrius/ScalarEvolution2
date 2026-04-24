/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server.gc;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToLongFunction;
import org.slf4j.Logger;

/**
 * Orchestrator for disk-image garbage collection. Three operations:
 *
 * <ul>
 *   <li>{@link #runEventDriven} — called from the item-lifecycle listener when
 *       an {@link net.minecraft.world.entity.item.ItemEntity} with
 *       {@code STORAGE_UUID}-carrying stacks is destroyed. Narrow: only the
 *       UUIDs from the destroyed stack are candidates. Respects creation grace,
 *       protection, live-scan, running machines.</li>
 *   <li>{@link #runSweep} — the opt-in periodic safety net. Walks every image
 *       file on disk; anything unreferenced for longer than
 *       {@link GcPolicy#sweepRetentionMillis()} gets deleted. Respects
 *       creation grace, protection, live-scan, running machines, retention.</li>
 *   <li>{@link #runPurge} — manual {@code /scev gc purge}. Same scan as sweep
 *       but bypasses retention and creation grace — still protects
 *       running-machine UUIDs and admin-pinned UUIDs.</li>
 * </ul>
 *
 * <h2>Protection ordering</h2>
 *
 * <p>A UUID is safe from every path iff ANY of these is true:
 *
 * <ol>
 *   <li>It appears in {@code liveUuids} (some scanner found it referenced).</li>
 *   <li>It's pinned via {@link DiskImageRegistry#isProtected(UUID)}
 *       ({@code /scev gc protect}).</li>
 *   <li>Its image file is younger than {@link GcPolicy#creationGraceMillis()}
 *       — and the current path honours grace (sweep, event; not purge).</li>
 *   <li>Sweep only: its registry {@code lastSeen} is more recent than
 *       {@code now - sweepRetentionMillis}.</li>
 * </ol>
 *
 * <p>If a scanner includes running-machine UUIDs in {@code liveUuids}
 * (that's what {@code RunningMachineScanner} exists for), rule (1) covers
 * machine protection too.
 *
 * <h2>Testability</h2>
 *
 * <p>All state passed through parameters: {@code imagesDir}, {@code registry},
 * {@code policy}, {@code liveUuids}, {@code now}. Tests drive this class
 * with a {@link java.nio.file.Path#resolve(String) @TempDir} imagesDir, a
 * fresh registry, and hand-rolled live sets. No MinecraftServer required.
 *
 * <h2>Concurrency</h2>
 *
 * <p>Assume single-threaded use. Event and sweep currently run on the server
 * thread. If/when they move to a worker, we'll add explicit synchronization;
 * per-run atomicity isn't needed because deletion of a specific file is
 * idempotent (missing file → no-op).
 */
public final class DiskImageGc {
    private static final Logger LOG = LogUtils.getLogger();

    private final Path imagesDir;
    private final DiskImageRegistry registry;
    private final GcPolicy policy;

    /**
     * Returns a file's creation time in wall-clock millis. Production binds
     * this to the filesystem's {@code BasicFileAttributes.creationTime()};
     * tests inject a deterministic function so grace-window behaviour is
     * testable without sleeping or setattr'ing actual files.
     *
     * <p>If the default read fails (missing file, FS doesn't track birth
     * time) we return {@link System#currentTimeMillis()} — "brand new" — so
     * a filesystem hiccup never causes a wrongful deletion.
     */
    private final ToLongFunction<Path> fileClock;

    /** Production constructor: creation time comes from the filesystem. */
    public DiskImageGc(Path imagesDir, DiskImageRegistry registry, GcPolicy policy) {
        this(imagesDir, registry, policy, DiskImageGc::defaultFileCreationMillis);
    }

    /**
     * Test-friendly constructor: caller supplies the file-creation-time
     * function. Useful for controlling grace-window behaviour in unit tests
     * without relying on platform-specific attribute manipulation.
     */
    public DiskImageGc(
            Path imagesDir,
            DiskImageRegistry registry,
            GcPolicy policy,
            ToLongFunction<Path> fileClock) {
        this.imagesDir = Objects.requireNonNull(imagesDir, "imagesDir");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.fileClock = Objects.requireNonNull(fileClock, "fileClock");
    }

    /* -----------------------------------------------------------------
     * Event-driven path
     * ----------------------------------------------------------------- */

    /**
     * Consider the given candidate UUIDs (extracted from a just-destroyed
     * stack) for deletion.
     *
     * <p>Delete iff:
     * <ul>
     *   <li>Candidate is NOT in {@code liveUuids} (no other reference found).</li>
     *   <li>Candidate is NOT protected.</li>
     *   <li>Image file exists and is older than {@link GcPolicy#creationGraceMillis()}.</li>
     * </ul>
     *
     * <p>Returns a {@link GcResult} recording what happened. No-op if
     * {@code candidates} is empty. Event-driven never has a dry-run mode —
     * the destruction signal is immediate, the deletion should be too.
     */
    public GcResult runEventDriven(Collection<UUID> candidates, Set<UUID> liveUuids, long nowMillis) {
        if (candidates == null || candidates.isEmpty()) return GcResult.empty(false);
        Set<UUID> deleted = new LinkedHashSet<>();
        long bytesFreed = 0L;
        for (UUID uuid : candidates) {
            if (uuid == null) continue;
            if (liveUuids != null && liveUuids.contains(uuid)) continue;     // cloned copy lives
            if (registry.isProtected(uuid))                          continue; // admin pin
            Path file = imageFile(uuid);
            if (!Files.isRegularFile(file))                          continue; // already gone
            long created = fileClock.applyAsLong(file);
            if (nowMillis - created < policy.creationGraceMillis())  continue; // newborn, race guard
            long size = tryFileSize(file);
            if (deleteImage(file, uuid, "event-driven")) {
                deleted.add(uuid);
                bytesFreed += size;
                registry.forget(uuid);
            }
        }
        return new GcResult(deleted, deleted, bytesFreed, false);
    }

    /* -----------------------------------------------------------------
     * Sweep path
     * ----------------------------------------------------------------- */

    /**
     * Walk every image file under {@code imagesDir} and delete those that
     * have been unseen for longer than retention. Call this from a scheduled
     * task or the manual command. Returns the result for logging.
     *
     * @param liveUuids  UUIDs observed during the scan — refresh their
     *                   lastSeen timestamps, never delete them
     * @param dryRun     if true, log what would be deleted but touch nothing
     * @param nowMillis  current wall-clock time in millis
     */
    public GcResult runSweep(Set<UUID> liveUuids, boolean dryRun, long nowMillis) {
        // Phase 1: refresh lastSeen for every live UUID.
        if (liveUuids != null) {
            for (UUID u : liveUuids) registry.observe(u, nowMillis);
        }

        // Phase 2: walk the images dir.
        List<UUID> onDisk = listImageUuids();
        Set<UUID> deleted = new LinkedHashSet<>();
        Set<UUID> wouldDelete = new LinkedHashSet<>();
        long bytesFreed = 0L;

        for (UUID uuid : onDisk) {
            if (liveUuids != null && liveUuids.contains(uuid)) continue;     // referenced
            if (registry.isProtected(uuid))                    continue;     // pinned

            Path file = imageFile(uuid);
            if (!Files.isRegularFile(file)) continue;

            long created = fileClock.applyAsLong(file);
            if (nowMillis - created < policy.creationGraceMillis()) continue; // newborn

            // First-time observation of an unreferenced image: record lastSeen
            // = now so retention starts from discovery, not ctime (which can
            // drift across world copies). Next sweep is the one that may act.
            if (!registry.isTracked(uuid)) {
                registry.observeIfMissing(uuid, nowMillis);
                continue;
            }

            long lastSeen = registry.lastSeen(uuid, nowMillis);
            if (nowMillis - lastSeen < policy.sweepRetentionMillis()) continue; // still fresh

            // Candidate for deletion.
long size = tryFileSize(file);
            if (dryRun) {
                wouldDelete.add(uuid);
                bytesFreed += size;
                LOG.info("[scev-gc] DRY-RUN sweep would delete {} ({} bytes, last seen {} ms ago)",
                        file, size, nowMillis - lastSeen);
            } else if (deleteImage(file, uuid, "sweep")) {
                deleted.add(uuid);
                bytesFreed += size;
                registry.forget(uuid);
            }
        }
        return new GcResult(deleted, dryRun ? wouldDelete : deleted, bytesFreed, dryRun);
    }

    /* -----------------------------------------------------------------
     * Purge path
     * ----------------------------------------------------------------- */

    /**
     * Walk every image file and delete any that aren't live, protected, or
     * owned by a running machine. Bypasses retention + creation grace.
     *
     * <p>Still respects: protection (admin pins), live scan (running machines
     * and scannable storage). The token handshake for confirming a purge
     * lives in {@code ScevGcCommand} — this method just does the work.
     */
    public GcResult runPurge(Set<UUID> liveUuids, boolean dryRun, long nowMillis) {
        List<UUID> onDisk = listImageUuids();
        Set<UUID> deleted = new LinkedHashSet<>();
        Set<UUID> wouldDelete = new LinkedHashSet<>();
        long bytesFreed = 0L;

        for (UUID uuid : onDisk) {
            if (liveUuids != null && liveUuids.contains(uuid)) continue;
            if (registry.isProtected(uuid))                    continue;
            Path file = imageFile(uuid);
            if (!Files.isRegularFile(file)) continue;
            long size = tryFileSize(file);
            if (dryRun) {
                wouldDelete.add(uuid);
                bytesFreed += size;
                LOG.info("[scev-gc] DRY-RUN purge would delete {} ({} bytes)", file, size);
            } else if (deleteImage(file, uuid, "purge")) {
                deleted.add(uuid);
                bytesFreed += size;
                registry.forget(uuid);
            }
        }
        return new GcResult(deleted, dryRun ? wouldDelete : deleted, bytesFreed, dryRun);
    }

    /* -----------------------------------------------------------------
     * Internal helpers
     * ----------------------------------------------------------------- */

    private Path imageFile(UUID uuid) {
        return imagesDir.resolve(uuid + ".img");
    }

    /**
     * List every {@code <uuid>.img} file directly under {@link #imagesDir}
     * and return their UUIDs. Files that don't match the UUID pattern
     * (e.g. {@code .registry.json}, operator-dropped test files) are
     * silently skipped.
     */
    private List<UUID> listImageUuids() {
        List<UUID> out = new ArrayList<>();
        if (!Files.isDirectory(imagesDir)) return out;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(imagesDir, "*.img")) {
            for (Path p : stream) {
                String name = p.getFileName().toString();
                // name.endsWith(".img") guaranteed by glob, but strip-and-parse
                // is robust against the glob glob-edge-cases across filesystems.
                if (!name.endsWith(".img")) continue;
                String uuidStr = name.substring(0, name.length() - 4);
                try {
                    out.add(UUID.fromString(uuidStr));
                } catch (IllegalArgumentException e) {
                    // Not a UUID-named image — user file, leftover from another
                    // tool, whatever. Not ours to touch.
                    LOG.debug("[scev-gc] Skipping non-UUID image filename: {}", name);
                }
            }
        } catch (IOException e) {
            LOG.warn("[scev-gc] Failed to list {}: {}", imagesDir, e.getMessage());
        }
        return out;
    }

    /**
     * Default file-creation-time reader: queries
     * {@link BasicFileAttributes#creationTime()}. On error (missing file,
     * FS doesn't support birthtime) returns {@code System.currentTimeMillis()}
     * — "treat as brand new" — so a transient FS error never causes a
     * wrongful deletion via the grace check.
     */
    private static long defaultFileCreationMillis(Path file) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            return attrs.creationTime().toMillis();
        } catch (IOException e) {
            return System.currentTimeMillis();
        }
    }

    /** Size in bytes, or 0 on error. Used for the bytes-freed counter. */
    private static long tryFileSize(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0L;
        }
    }

    /**
     * Delete an image file and log the outcome. Logging is at INFO (for
     * success) or WARN (for failure); each deletion is one line so admins
     * can grep for {@code [scev-gc] DELETE} to audit activity.
     */
    private boolean deleteImage(Path file, UUID uuid, String reason) {
        try {
            long size = tryFileSize(file);
            Files.delete(file);
            LOG.info("[scev-gc] DELETE {} uuid={} size={} reason={}",
                    file, uuid, size, reason);
            return true;
        } catch (IOException e) {
            LOG.warn("[scev-gc] Failed to delete {} ({}): {}", file, reason, e.getMessage());
            return false;
        }
    }

    /** Used by sweep-scheduler telemetry — total tracked UUIDs. */
    public int trackedUuidCount() {
        return registry.trackedCount();
    }

    /** Used by {@code /scev gc status}. */
    public GcPolicy policy() { return policy; }

    /** Used by {@code /scev gc protect} / {@code unprotect}. */
    public DiskImageRegistry registry() { return registry; }

    /** Used by {@code /scev gc status} for image-count readouts. */
    public int onDiskImageCount() {
        return listImageUuids().size();
    }

}
