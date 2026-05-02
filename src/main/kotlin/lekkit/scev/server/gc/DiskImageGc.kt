/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server.gc

import com.mojang.logging.LogUtils
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.function.ToLongFunction

/**
 * Orchestrator for disk-image garbage collection. Three operations:
 *
 *   - [runEventDriven]: called from the item-lifecycle listener when an
 *     [net.minecraft.world.entity.item.ItemEntity] with `STORAGE_UUID`-
 *     carrying stacks is destroyed. Narrow: only the UUIDs from the
 *     destroyed stack are candidates. Respects creation grace, protection,
 *     live-scan, running machines.
 *   - [runSweep]: opt-in periodic safety net. Walks every image file on
 *     disk; anything unreferenced for longer than [GcPolicy.sweepRetentionMillis]
 *     is deleted. Respects all of the above plus retention.
 *   - [runPurge]: manual `/scev gc purge`. Same scan as sweep but bypasses
 *     retention and creation grace — still protects running-machine UUIDs
 *     and admin-pinned UUIDs.
 *
 * **Protection ordering.** A UUID is safe iff ANY:
 *   1. Appears in `liveUuids` (some scanner found it referenced).
 *   2. Pinned via [DiskImageRegistry.isProtected].
 *   3. Image file younger than [GcPolicy.creationGraceMillis] — and the path
 *      honours grace (sweep, event; not purge).
 *   4. Sweep only: `lastSeen` more recent than `now - sweepRetentionMillis`.
 *
 * If a scanner includes running-machine UUIDs in `liveUuids` (that's
 * [scanners.RunningMachineScanner]'s job), rule 1 covers machine protection.
 *
 * **Testability.** All state passed in: [imagesDir], [registry], [policy],
 * `liveUuids`, `now`. Tests drive this with a `@TempDir` imagesDir and a
 * fresh registry; no MinecraftServer required.
 *
 * **Concurrency.** Single-threaded. Event and sweep currently run on the
 * server thread. Per-run atomicity isn't needed because deletion of a
 * specific file is idempotent (missing file → no-op).
 */
class DiskImageGc @JvmOverloads constructor(
    private val imagesDir: Path,
    private val registry: DiskImageRegistry,
    private val policy: GcPolicy,
    /**
     * File creation time in wall-clock millis. Production binds this to the
     * filesystem's `BasicFileAttributes.creationTime()`; tests inject a
     * deterministic function so grace-window behaviour is testable without
     * sleeping or `setattr`-ing actual files. On error returns
     * `System.currentTimeMillis()` — "treat as brand new" — so a transient
     * FS hiccup never causes a wrongful deletion via the grace check.
     */
    private val fileClock: ToLongFunction<Path> = ToLongFunction { defaultFileCreationMillis(it) },
) {
    /* ----- Event-driven path ----- */

    /**
     * Consider [candidates] (extracted from a just-destroyed stack) for
     * deletion. Delete iff: not in [liveUuids], not protected, file exists,
     * and file is older than [GcPolicy.creationGraceMillis]. Event-driven
     * has no dry-run mode — destruction is immediate and so is the cleanup.
     */
    fun runEventDriven(candidates: Collection<UUID>?, liveUuids: Set<UUID>?, nowMillis: Long): GcResult {
        if (candidates.isNullOrEmpty()) return GcResult.empty(false)
        val deleted = LinkedHashSet<UUID>()
        var bytesFreed = 0L
        for (uuid in candidates) {
            if (uuid == null) continue
            if (liveUuids?.contains(uuid) == true) continue                  // cloned copy lives
            if (registry.isProtected(uuid)) continue                         // admin pin
            val file = imageFile(uuid)
            if (!Files.isRegularFile(file)) continue                         // already gone
            val created = fileClock.applyAsLong(file)
            if (nowMillis - created < policy.creationGraceMillis) continue   // newborn race-guard
            val size = tryFileSize(file)
            if (deleteImage(file, uuid, "event-driven")) {
                deleted += uuid
                bytesFreed += size
                registry.forget(uuid)
            }
        }
        return GcResult(deleted, deleted, bytesFreed, false)
    }

    /* ----- Sweep path ----- */

    /**
     * Walk every image file under [imagesDir] and delete those unseen longer
     * than retention. Refreshes `lastSeen` for live UUIDs, then evaluates
     * deletion candidates.
     */
    fun runSweep(liveUuids: Set<UUID>?, dryRun: Boolean, nowMillis: Long): GcResult {
        // Phase 1: refresh lastSeen for every live UUID.
        liveUuids?.forEach { registry.observe(it, nowMillis) }

        // Phase 2: walk the images dir.
        val deleted = LinkedHashSet<UUID>()
        val wouldDelete = LinkedHashSet<UUID>()
        var bytesFreed = 0L

        for (uuid in listImageUuids()) {
            if (liveUuids?.contains(uuid) == true) continue
            if (registry.isProtected(uuid)) continue

            val file = imageFile(uuid)
            if (!Files.isRegularFile(file)) continue

            val created = fileClock.applyAsLong(file)
            if (nowMillis - created < policy.creationGraceMillis) continue   // newborn

            // First-time observation of an unreferenced image: record lastSeen
            // = now so retention starts from discovery, not ctime (which can
            // drift across world copies). Next sweep is the one that may act.
            if (!registry.isTracked(uuid)) {
                registry.observeIfMissing(uuid, nowMillis)
                continue
            }

            val lastSeen = registry.lastSeen(uuid, nowMillis)
            if (nowMillis - lastSeen < policy.sweepRetentionMillis) continue // still fresh

            val size = tryFileSize(file)
            if (dryRun) {
                wouldDelete += uuid
                bytesFreed += size
                LOG.info("[scev-gc] DRY-RUN sweep would delete {} ({} bytes, last seen {} ms ago)",
                    file, size, nowMillis - lastSeen)
            } else if (deleteImage(file, uuid, "sweep")) {
                deleted += uuid
                bytesFreed += size
                registry.forget(uuid)
            }
        }
        return GcResult(deleted, if (dryRun) wouldDelete else deleted, bytesFreed, dryRun)
    }

    /* ----- Purge path ----- */

    /**
     * Delete every non-live, non-protected image. Bypasses retention +
     * creation grace. Still respects protection and live-scan (running
     * machines). The token handshake lives in `ScevGcCommand`; this method
     * just does the work.
     */
    fun runPurge(liveUuids: Set<UUID>?, dryRun: Boolean, nowMillis: Long): GcResult {
        val deleted = LinkedHashSet<UUID>()
        val wouldDelete = LinkedHashSet<UUID>()
        var bytesFreed = 0L

        for (uuid in listImageUuids()) {
            if (liveUuids?.contains(uuid) == true) continue
            if (registry.isProtected(uuid)) continue
            val file = imageFile(uuid)
            if (!Files.isRegularFile(file)) continue
            val size = tryFileSize(file)
            if (dryRun) {
                wouldDelete += uuid
                bytesFreed += size
                LOG.info("[scev-gc] DRY-RUN purge would delete {} ({} bytes)", file, size)
            } else if (deleteImage(file, uuid, "purge")) {
                deleted += uuid
                bytesFreed += size
                registry.forget(uuid)
            }
        }
        return GcResult(deleted, if (dryRun) wouldDelete else deleted, bytesFreed, dryRun)
    }

    /* ----- Public read helpers (used by command + scheduler) ----- */

    fun trackedUuidCount(): Int = registry.trackedCount()
    fun policy(): GcPolicy = policy
    fun registry(): DiskImageRegistry = registry
    fun onDiskImageCount(): Int = listImageUuids().size

    /* ----- Internals ----- */

    private fun imageFile(uuid: UUID): Path = imagesDir.resolve("$uuid.img")

    /**
     * List every `<uuid>.img` directly under [imagesDir] and return their UUIDs.
     * Non-UUID-named files (`.registry.json`, operator drops, …) are skipped.
     */
    private fun listImageUuids(): List<UUID> {
        if (!Files.isDirectory(imagesDir)) return emptyList()
        val out = ArrayList<UUID>()
        try {
            Files.newDirectoryStream(imagesDir, "*.img").use { stream ->
                for (p in stream) {
                    val name = p.fileName.toString()
                    if (!name.endsWith(".img")) continue
                    val uuidStr = name.substring(0, name.length - 4)
                    try {
                        out += UUID.fromString(uuidStr)
                    } catch (_: IllegalArgumentException) {
                        LOG.debug("[scev-gc] Skipping non-UUID image filename: {}", name)
                    }
                }
            }
        } catch (e: IOException) {
            LOG.warn("[scev-gc] Failed to list {}: {}", imagesDir, e.message)
        }
        return out
    }

    private fun deleteImage(file: Path, uuid: UUID, reason: String): Boolean = try {
        val size = tryFileSize(file)
        Files.delete(file)
        LOG.info("[scev-gc] DELETE {} uuid={} size={} reason={}", file, uuid, size, reason)
        true
    } catch (e: IOException) {
        LOG.warn("[scev-gc] Failed to delete {} ({}): {}", file, reason, e.message)
        false
    }

    companion object {
        private val LOG = LogUtils.getLogger()

        private fun defaultFileCreationMillis(file: Path): Long = try {
            Files.readAttributes(file, BasicFileAttributes::class.java).creationTime().toMillis()
        } catch (_: IOException) {
            System.currentTimeMillis()
        }

        /** Size in bytes, or 0 on error. Used for the bytes-freed counter. */
        private fun tryFileSize(file: Path): Long = try { Files.size(file) } catch (_: IOException) { 0L }
    }
}
