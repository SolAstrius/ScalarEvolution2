/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server.gc

/**
 * A source of "live" `STORAGE_UUID`s for the disk-image garbage collector.
 *
 * Implementations enumerate some portion of server state (player inventories,
 * block-entity containers, entity-held item stacks, running machines, mod-
 * specific virtual-storage systems, …) and call [ScanContext.addLive] for
 * each UUID they find referenced.
 *
 * Stock scanners live in `lekkit.scev.server.gc.scanners.*`. Other mods can
 * register additional scanners by calling [ScannerRegistry.register] from their
 * own common-setup hook — this is the integration point for AE2 cells, Refined
 * Storage disks, Mekanism QIO frequencies, Create contraption entities, and
 * similar virtualised-storage systems that aren't covered by the standard
 * `Capabilities.ItemHandler` surfaces.
 *
 * **Contract.** A scanner must:
 * - Be safe to call from the server thread (scanners run inline during a sweep
 *   or immediately after an event fires; if a scanner needs to touch the world,
 *   it's already on the correct thread).
 * - Be idempotent: repeated [scan] with the same underlying state must yield
 *   the same UUID set. The orchestrator may invoke a scanner multiple times in
 *   one sweep (purge re-scans at confirm time).
 * - Not block. If the underlying source is slow (region-file reads, network
 *   queries), short-circuit or throttle internally.
 * - Not mutate world state. Scanning is read-only; mutations belong in the
 *   orchestrator.
 *
 * **Failure handling.** A scanner that throws doesn't crash the GC —
 * [DiskImageGc] catches and logs. But a scanner that silently skips a
 * referenced UUID leaks the image to eventual deletion. Prefer "add
 * conservatively" over "skip on uncertainty".
 */
fun interface DiskImageScanner {
    /**
     * Walk the source this scanner is responsible for and report every
     * referenced `STORAGE_UUID` to [ctx].
     */
    fun scan(ctx: ScanContext)
}
