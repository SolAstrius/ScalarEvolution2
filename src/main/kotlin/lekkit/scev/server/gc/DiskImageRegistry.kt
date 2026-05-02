/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server.gc

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import com.mojang.logging.LogUtils
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Persistent state for the disk-image garbage collector.
 *
 * Lives at `<world>/scev/images/.registry.json`. Stores:
 *   - **lastSeen** — UUID → wall-clock millis. The last time each UUID was
 *     observed by a scanner as referenced. Sweep uses this to decide which
 *     images have been orphaned long enough to delete.
 *   - **protectedUuids** — set of UUIDs the admin has pinned via
 *     `/scev gc protect`. Never deleted by any GC path, regardless of
 *     scanner results or retention. Escape hatch for items in virtual
 *     storage we can't scan (AE2 cells, RS disks, …).
 *
 * Concurrency: GC operations aren't hot-path. All mutation goes through
 * `@Synchronized` methods backed by plain hash collections — simpler than a
 * concurrent map and fast enough that contention never shows up in profiling.
 * Readers get defensive copies so iteration outside the lock is safe.
 *
 * Persistence: JSON via Gson, with a top-level `version` field so future
 * migrations can detect old schemas. Writes are atomic: write to a `.tmp`
 * sibling, then rename. A corrupt file (partial write, user edit gone wrong)
 * doesn't crash us — [load] logs a warning and returns a fresh empty registry.
 */
class DiskImageRegistry private constructor(private val path: Path) {
    /** UUID → last-seen wall-clock millis. */
    private val lastSeen = HashMap<UUID, Long>()
    /** Admin-pinned UUIDs: always protected against every GC path. */
    private val protectedUuidSet = HashSet<UUID>()

    /** Persist current state to disk atomically. */
    @Synchronized fun save() {
        try {
            Files.createDirectories(path.parent)
            val tmp = path.resolveSibling("${path.fileName}.tmp")
            val snap = Snapshot().apply {
                version = CURRENT_VERSION
                entries = lastSeen.mapKeysTo(LinkedHashMap()) { (k, _) -> k.toString() }
                protectedUuids = protectedUuidSet.mapTo(HashSet()) { it.toString() }
            }
            Files.newBufferedWriter(tmp).use { GSON.toJson(snap, it) }
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                // FAT and some network FSes don't support atomic moves. Fall back —
                // worst case a partial write loses prior state, which matches the
                // load path's "start fresh if corrupt."
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            LOG.warn("Failed to save scev GC registry to {}: {}", path, e.message)
        }
    }

    /** Mark [uuid] as observed right now. */
    @Synchronized fun observe(uuid: UUID, nowMillis: Long) {
        lastSeen[uuid] = nowMillis
    }

    /**
     * Mark [uuid] as observed only if not already tracked. Used on file
     * discovery: when an image exists on disk but has no registry entry,
     * record it with a fresh timestamp so the retention window starts from
     * discovery, not from filesystem ctime.
     */
    @Synchronized fun observeIfMissing(uuid: UUID, nowMillis: Long) {
        lastSeen.putIfAbsent(uuid, nowMillis)
    }

    /** Last-seen timestamp for [uuid], or [defaultIfMissing] if untracked. */
    @Synchronized fun lastSeen(uuid: UUID, defaultIfMissing: Long): Long =
        lastSeen[uuid] ?: defaultIfMissing

    @Synchronized fun isTracked(uuid: UUID): Boolean = uuid in lastSeen

    /** Remove [uuid] from tracking. Called after the image file is deleted. */
    @Synchronized fun forget(uuid: UUID) { lastSeen.remove(uuid) }

    @Synchronized fun protect(uuid: UUID): Boolean = protectedUuidSet.add(uuid)
    @Synchronized fun unprotect(uuid: UUID): Boolean = protectedUuidSet.remove(uuid)
    @Synchronized fun isProtected(uuid: UUID): Boolean = uuid in protectedUuidSet

    /** Defensive copy — safe to iterate outside the lock. */
    @Synchronized fun protectedUuidsCopy(): Set<UUID> = HashSet(protectedUuidSet)
    @Synchronized fun lastSeenCopy(): Map<UUID, Long> = HashMap(lastSeen)

    @Synchronized fun trackedCount(): Int = lastSeen.size
    @Synchronized fun protectedCount(): Int = protectedUuidSet.size

    fun file(): Path = path

    /** Gson-serializable payload. Public mutable fields by design — Gson writes them directly. */
    internal class Snapshot {
        @JvmField var version: Int = CURRENT_VERSION
        @JvmField var entries: MutableMap<String, Long>? = null
        @JvmField var protectedUuids: MutableSet<String>? = null
    }

    companion object {
        private val LOG = LogUtils.getLogger()

        /** Current on-disk schema version. Bump on incompatible layout changes. */
        @JvmField val CURRENT_VERSION: Int = 1

        private val GSON: Gson = GsonBuilder().setPrettyPrinting().create()

        /**
         * Load a registry from disk. Returns a fresh empty registry if the file
         * doesn't exist OR is unparseable — we'd rather lose GC state than
         * crash the server over a malformed JSON file.
         */
        @JvmStatic fun load(file: Path): DiskImageRegistry {
            val reg = DiskImageRegistry(file)
            if (!Files.isRegularFile(file)) return reg
            try {
                Files.newBufferedReader(file).use { r ->
                    val snap = GSON.fromJson(r, Snapshot::class.java) ?: return reg
                    if (snap.version != CURRENT_VERSION) {
                        LOG.warn("scev GC registry {} has version {}, expected {} — discarding and starting fresh",
                            file, snap.version, CURRENT_VERSION)
                        return reg
                    }
                    snap.entries?.forEach { (key, value) ->
                        try { reg.lastSeen[UUID.fromString(key)] = value }
                        catch (_: IllegalArgumentException) {
                            LOG.warn("scev GC registry: ignoring malformed UUID key '{}'", key)
                        }
                    }
                    snap.protectedUuids?.forEach { s ->
                        try { reg.protectedUuidSet.add(UUID.fromString(s)) }
                        catch (_: IllegalArgumentException) {
                            LOG.warn("scev GC registry: ignoring malformed protected UUID '{}'", s)
                        }
                    }
                }
            } catch (e: IOException) {
                LOG.warn("Failed to load scev GC registry from {}: {}. Starting fresh.", file, e.message)
            } catch (e: JsonSyntaxException) {
                LOG.warn("Failed to load scev GC registry from {}: {}. Starting fresh.", file, e.message)
            }
            return reg
        }
    }
}
