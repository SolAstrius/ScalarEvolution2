/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server.gc;

import java.time.Duration;

/**
 * Value object: the knobs that govern what {@link DiskImageGc} considers
 * safe to delete.
 *
 * <p>All durations are wall-clock. The GC doesn't distinguish playtime from
 * real time; a server that's down isn't accumulating retention, a server
 * that's up is.
 *
 * <dl>
 *   <dt>{@link #creationGraceMillis()}</dt>
 *   <dd>Minimum age of an image file before any GC path will consider it
 *       for deletion. Protects against races where an image was just
 *       created (first power-on of a fresh NVMe) but the item hasn't yet
 *       been seen by a scanner (chunk not loaded, player just logged in,
 *       etc.). Applies to event-driven GC and sweep alike. <b>Purge bypasses
 *       this.</b></dd>
 *
 *   <dt>{@link #sweepRetentionMillis()}</dt>
 *   <dd>How long a UUID must be unseen by any scanner before sweep will
 *       delete its image. The safety net for "silent destructions" the event
 *       path can't catch ({@code /clear}, {@code /setblock}, mod-specific
 *       deletions). Only consulted by sweep.</dd>
 *
 *   <dt>{@link #sweepIntervalMillis()}</dt>
 *   <dd>How often automatic sweeps fire. Only relevant when sweep is enabled
 *       via config; manual {@code /scev gc sweep execute} works regardless.</dd>
 * </dl>
 *
 * <p>Construct via {@link #defaults()} or the explicit constructor. Config
 * reading lives in the integration layer ({@code ScalarEvolution} +
 * {@code ScevConfig}) so this record stays decoupled from NeoForge.
 */
public record GcPolicy(
        long creationGraceMillis,
        long sweepRetentionMillis,
        long sweepIntervalMillis
) {
    /**
     * Conservative defaults matching the shipped {@code ScevConfig} values.
     * Used by tests and as a fallback when config isn't available.
     */
    public static GcPolicy defaults() {
        return new GcPolicy(
                Duration.ofMinutes(60).toMillis(),
                Duration.ofDays(30).toMillis(),
                Duration.ofHours(24).toMillis());
    }
}
