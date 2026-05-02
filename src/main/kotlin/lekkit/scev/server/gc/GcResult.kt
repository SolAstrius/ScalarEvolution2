/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server.gc

import java.util.UUID

/**
 * Outcome of a single GC operation (event, sweep, or purge). Returned from
 * every [DiskImageGc] run method so callers can log / report / test.
 *
 * @property deleted     UUIDs whose image files were actually removed. Empty in dry-run mode.
 * @property wouldDelete UUIDs that would have been deleted in a non-dry run. In a real run
 *                       this mirrors [deleted]; in a dry run it holds the plan only.
 * @property bytesFreed  Total bytes reclaimed (sum of each deleted image's logical size at
 *                       deletion time). Zero in dry-run mode.
 * @property dryRun      Echoed back so callers can format their log message correctly without
 *                       tracking the flag themselves.
 */
data class GcResult @JvmOverloads constructor(
    @get:JvmName("deleted")     val deleted: Set<UUID> = emptySet(),
    @get:JvmName("wouldDelete") val wouldDelete: Set<UUID> = emptySet(),
    @get:JvmName("bytesFreed")  val bytesFreed: Long = 0L,
    @get:JvmName("dryRun")      val dryRun: Boolean = false,
) {
    /** Number of images affected by this run (real or would-be). */
    fun affected(): Int = if (dryRun) wouldDelete.size else deleted.size

    companion object {
        /** Empty result — nothing deleted, nothing would-be-deleted. */
        @JvmStatic fun empty(dryRun: Boolean): GcResult = GcResult(dryRun = dryRun)
    }
}
