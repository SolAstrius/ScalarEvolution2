/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server.gc;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;

/**
 * Outcome of a single GC operation (event, sweep, or purge). Returned from
 * every {@link DiskImageGc} run method so callers can log / report / test
 * the result.
 *
 * <dl>
 *   <dt>{@link #deleted()}</dt>
 *   <dd>UUIDs whose image files were actually removed. Empty in dry-run mode.</dd>
 *
 *   <dt>{@link #wouldDelete()}</dt>
 *   <dd>UUIDs that would have been deleted in a non-dry-run. In a real run
 *       this mirrors {@link #deleted()}; in a dry run it holds the plan
 *       without touching any files.</dd>
 *
 *   <dt>{@link #bytesFreed()}</dt>
 *   <dd>Total bytes of disk space reclaimed — sum of each deleted image's
 *       logical size at deletion time. Zero in dry-run mode.</dd>
 *
 *   <dt>{@link #dryRun()}</dt>
 *   <dd>True iff this was a dry run. Echoed back so callers can format their
 *       log message correctly without tracking the flag themselves.</dd>
 * </dl>
 *
 * <p>All sets are immutable snapshots.
 */
public record GcResult(
        Set<UUID> deleted,
        Set<UUID> wouldDelete,
        long bytesFreed,
        boolean dryRun
) {
    public GcResult {
        deleted = deleted == null ? Set.of() : Collections.unmodifiableSet(deleted);
        wouldDelete = wouldDelete == null ? Set.of() : Collections.unmodifiableSet(wouldDelete);
    }

    /** Empty result — nothing deleted, nothing would-be-deleted. */
    public static GcResult empty(boolean dryRun) {
        return new GcResult(Set.of(), Set.of(), 0L, dryRun);
    }

    /** Number of images affected by this run (real or would-be). */
    public int affected() {
        return dryRun ? wouldDelete.size() : deleted.size();
    }
}
