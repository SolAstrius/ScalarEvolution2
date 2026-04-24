/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server.gc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;

/**
 * Persistent state for the disk-image garbage collector.
 *
 * <p>Lives at {@code <world>/scev/images/.registry.json}. Stores:
 *
 * <ul>
 *   <li><b>{@code lastSeen}</b> — UUID → wall-clock millis. The last time each
 *       UUID was observed by a scanner as referenced. Sweep uses this to decide
 *       which images have been orphaned long enough to delete.</li>
 *   <li><b>{@code protectedUuids}</b> — set of UUIDs the admin has pinned via
 *       {@code /scev gc protect}. These are never deleted by any GC path,
 *       regardless of scanner results or retention. Escape hatch for items
 *       stored in virtual-storage systems we can't scan (AE2 cells, RS disks,
 *       …).</li>
 * </ul>
 *
 * <h2>Concurrency</h2>
 *
 * <p>GC operations aren't hot-path. All mutation goes through {@code synchronized}
 * methods backed by plain hash collections — simpler than a concurrent map and
 * fast enough that the lock contention never shows up in profiling. Readers get
 * defensive-copies so iteration outside the lock is safe.
 *
 * <h2>Persistence</h2>
 *
 * <p>JSON via Gson, with a top-level {@code version} field so future migrations
 * can detect + handle old schemas. Writes are atomic: write to a {@code .tmp}
 * sibling, then rename. A corrupt file (partial write, user edit gone wrong)
 * doesn't crash us — {@link #load(Path)} logs a warning and returns a fresh
 * empty registry.
 */
public final class DiskImageRegistry {
    private static final Logger LOG = LogUtils.getLogger();

    /** Current on-disk schema version. Bump on incompatible layout changes. */
    static final int CURRENT_VERSION = 1;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;

    /** UUID → last-seen wall-clock millis. */
    private final Map<UUID, Long> lastSeen = new HashMap<>();

    /** Admin-pinned UUIDs: always protected against every GC path. */
    private final Set<UUID> protectedUuids = new HashSet<>();

    private DiskImageRegistry(Path file) {
        this.file = file;
    }

    /**
     * Load a registry from disk. Returns a fresh empty registry if the file
     * doesn't exist. If it exists but is unparseable, logs a warning and
     * returns empty — we prefer to lose GC state than to crash the server
     * over a malformed JSON file.
     */
    public static DiskImageRegistry load(Path file) {
        DiskImageRegistry reg = new DiskImageRegistry(file);
        if (!Files.isRegularFile(file)) return reg;
        try (Reader r = Files.newBufferedReader(file)) {
            Snapshot snap = GSON.fromJson(r, Snapshot.class);
            if (snap == null) return reg;
            if (snap.version != CURRENT_VERSION) {
                LOG.warn("scev GC registry {} has version {}, expected {} — discarding and starting fresh",
                        file, snap.version, CURRENT_VERSION);
                return reg;
            }
            if (snap.entries != null) {
                for (Map.Entry<String, Long> e : snap.entries.entrySet()) {
                    try {
                        reg.lastSeen.put(UUID.fromString(e.getKey()), e.getValue());
                    } catch (IllegalArgumentException bad) {
                        LOG.warn("scev GC registry: ignoring malformed UUID key '{}'", e.getKey());
                    }
                }
            }
            if (snap.protectedUuids != null) {
                for (String s : snap.protectedUuids) {
                    try {
                        reg.protectedUuids.add(UUID.fromString(s));
                    } catch (IllegalArgumentException bad) {
                        LOG.warn("scev GC registry: ignoring malformed protected UUID '{}'", s);
                    }
                }
            }
        } catch (IOException | JsonSyntaxException e) {
            LOG.warn("Failed to load scev GC registry from {}: {}. Starting fresh.",
                    file, e.getMessage());
        }
        return reg;
    }

    /** Persist the current state to disk atomically. */
    public synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Snapshot snap = new Snapshot();
            snap.version = CURRENT_VERSION;
            snap.entries = new LinkedHashMap<>();
            for (Map.Entry<UUID, Long> e : lastSeen.entrySet()) {
                snap.entries.put(e.getKey().toString(), e.getValue());
            }
            snap.protectedUuids = new HashSet<>();
            for (UUID u : protectedUuids) snap.protectedUuids.add(u.toString());
            try (Writer w = Files.newBufferedWriter(tmp)) {
                GSON.toJson(snap, w);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                // Some filesystems (e.g. FAT, some network FSes) don't support
                // atomic moves. Fall back to non-atomic — the worst case is a
                // corrupt write loses prior state, which matches the load-path
                // behaviour of "start fresh if corrupt."
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOG.warn("Failed to save scev GC registry to {}: {}", file, e.getMessage());
        }
    }

    /**
     * Mark {@code uuid} as observed right now. Called from the scan loop for
     * every live UUID.
     */
    public synchronized void observe(UUID uuid, long nowMillis) {
        lastSeen.put(uuid, nowMillis);
    }

    /**
     * Mark {@code uuid} as observed if it isn't already tracked. Used on file
     * discovery: if an image exists on disk but has no registry entry, record
     * it with a fresh timestamp so the retention window starts from discovery,
     * not from whatever the filesystem ctime happens to be.
     */
    public synchronized void observeIfMissing(UUID uuid, long nowMillis) {
        lastSeen.putIfAbsent(uuid, nowMillis);
    }

    /**
     * Return the last-seen timestamp for {@code uuid}, or {@code defaultIfMissing}
     * if the UUID isn't tracked.
     */
    public synchronized long lastSeen(UUID uuid, long defaultIfMissing) {
        Long v = lastSeen.get(uuid);
        return v == null ? defaultIfMissing : v;
    }

    /** True iff {@code uuid} is currently tracked. */
    public synchronized boolean isTracked(UUID uuid) {
        return lastSeen.containsKey(uuid);
    }

    /**
     * Remove {@code uuid} from tracking. Called after the image file is
     * deleted so the registry doesn't accumulate dead entries forever.
     */
    public synchronized void forget(UUID uuid) {
        lastSeen.remove(uuid);
}

    /** Add {@code uuid} to the protected set. */
    public synchronized boolean protect(UUID uuid) {
        return protectedUuids.add(uuid);
    }

    /** Remove {@code uuid} from the protected set. */
    public synchronized boolean unprotect(UUID uuid) {
        return protectedUuids.remove(uuid);
    }

    /** True iff the admin has pinned {@code uuid} via {@code /scev gc protect}. */
    public synchronized boolean isProtected(UUID uuid) {
        return protectedUuids.contains(uuid);
    }

    /** Defensive copy of the protected set — safe to iterate outside the lock. */
    public synchronized Set<UUID> protectedUuidsCopy() {
        return new HashSet<>(protectedUuids);
    }

    /** Defensive copy of the full lastSeen map — safe to iterate outside the lock. */
    public synchronized Map<UUID, Long> lastSeenCopy() {
        return new HashMap<>(lastSeen);
    }

    /** For tests and status readouts. */
    public synchronized int trackedCount() {
        return lastSeen.size();
    }

    /** For tests and status readouts. */
    public synchronized int protectedCount() {
        return protectedUuids.size();
    }

    /** Path this registry was loaded from / saves back to. */
    public Path file() {
        return file;
    }

    /**
     * Gson-serializable payload. Mutable by design; Gson writes public fields
     * directly. Don't leak instances — {@link #save()} / {@link #load(Path)}
     * build fresh copies inside the lock.
     */
    static final class Snapshot {
        int version;
        Map<String, Long> entries;
        Set<String> protectedUuids;

        /** Defensive default so a missing/empty file round-trips to an empty map. */
        Snapshot() {
            this.version = CURRENT_VERSION;
            this.entries = Collections.emptyMap();
            this.protectedUuids = Collections.emptySet();
        }
    }
}
