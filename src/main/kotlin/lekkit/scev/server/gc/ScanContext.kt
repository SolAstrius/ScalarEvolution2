/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server.gc

import java.util.UUID
import net.minecraft.server.MinecraftServer

/**
 * Collector passed to every [DiskImageScanner]. Each scanner adds the UUIDs it
 * finds referenced; the orchestrator reads the final set to decide which image
 * files are orphaned.
 *
 * The [server] handle is `null` for unit-test contexts that don't stand up a
 * real server — scanners that need a server should null-check and either
 * short-circuit or extract what they need from another path.
 */
class ScanContext(
    /** Running server, or `null` in tests. */
    @get:JvmName("server") val server: MinecraftServer?,
) {
    private val liveUuidSet = HashSet<UUID>()
    private val excludedEntityUuids = HashSet<UUID>()

    /**
     * Mark [uuid] as referenced — its image file will be preserved by the
     * current sweep/purge. Null-safe: scanners can pass `stack.get(STORAGE_UUID)`
     * directly without bothering with the wrapper.
     */
    fun addLive(uuid: UUID?) {
        if (uuid != null) liveUuidSet.add(uuid)
    }

    /** Read-only snapshot of UUIDs accumulated so far. */
    fun liveUuids(): Set<UUID> = java.util.Collections.unmodifiableSet(liveUuidSet)

    /** Number of UUIDs added — for logging / status readouts. */
    fun size(): Int = liveUuidSet.size

    /**
     * Mark an entity (Minecraft entity UUID — `Entity.getUUID()`, NOT a
     * `STORAGE_UUID`) as excluded from the current scan. Used by the event-
     * driven GC path: when an `ItemEntity` is about to despawn / burn / void,
     * the [lekkit.scev.server.gc.scanners.EntityScanner] must skip it —
     * otherwise it would "protect" the to-be-destroyed entity's own stack and
     * make the event-driven path a no-op for items not referenced elsewhere.
     */
    fun excludeEntity(entityUuid: UUID?) {
        if (entityUuid != null) excludedEntityUuids.add(entityUuid)
    }

    /** True iff [entityUuid] has been registered via [excludeEntity]. */
    fun isEntityExcluded(entityUuid: UUID?): Boolean =
        entityUuid != null && entityUuid in excludedEntityUuids
}
