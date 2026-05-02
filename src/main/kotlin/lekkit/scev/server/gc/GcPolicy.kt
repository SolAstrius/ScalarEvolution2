/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server.gc

import java.time.Duration

/**
 * Knobs that govern what [DiskImageGc] considers safe to delete. All durations
 * are wall-clock — the GC doesn't distinguish playtime from real time.
 *
 * @property creationGraceMillis  Minimum age before any path will consider an image
 *                                for deletion. Protects against fresh-creation races
 *                                (event-driven GC and sweep alike). **Purge bypasses this.**
 * @property sweepRetentionMillis How long a UUID must be unseen by any scanner before
 *                                sweep deletes its image. Safety net for `/clear`,
 *                                `/setblock`, and other event-blind destructions.
 * @property sweepIntervalMillis  How often automatic sweeps fire (only when enabled
 *                                via config; manual `/scev gc sweep execute` works regardless).
 *
 * Construct via [defaults] or directly. Config reading lives in the integration
 * layer (`ScalarEvolution` + `ScevConfig`); this stays decoupled from NeoForge.
 */
data class GcPolicy(
    @get:JvmName("creationGraceMillis")  val creationGraceMillis: Long,
    @get:JvmName("sweepRetentionMillis") val sweepRetentionMillis: Long,
    @get:JvmName("sweepIntervalMillis")  val sweepIntervalMillis: Long,
) {
    companion object {
        /** Conservative defaults matching the shipped `ScevConfig` values. */
        @JvmStatic fun defaults(): GcPolicy = GcPolicy(
            creationGraceMillis  = Duration.ofMinutes(60).toMillis(),
            sweepRetentionMillis = Duration.ofDays(30).toMillis(),
            sweepIntervalMillis  = Duration.ofHours(24).toMillis(),
        )
    }
}
