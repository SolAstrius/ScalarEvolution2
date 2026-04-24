/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server.gc;

/**
 * A source of "live" {@code STORAGE_UUID}s for the disk-image garbage collector.
 *
 * <p>Implementations enumerate some portion of server state (player inventories,
 * block-entity containers, entity-held item stacks, running machines, mod-specific
 * virtual-storage systems, …) and call {@link ScanContext#addLive(java.util.UUID)}
 * for each UUID they find referenced.
 *
 * <p>Stock scanners live in {@code lekkit.scev.server.gc.scanners.*}. Other mods
 * can register additional scanners by subscribing to
 * {@link ScevGcScannerRegistryEvent} on the mod event bus — this is the integration
 * point for Applied Energistics 2 cells, Refined Storage disks, Mekanism QIO
 * frequencies, Create contraption entities, and similar virtualized-storage
 * systems that aren't covered by the standard
 * {@link net.neoforged.neoforge.capabilities.Capabilities.ItemHandler} surfaces.
 *
 * <p><b>Contract.</b> A scanner must:
 *
 * <ul>
 *   <li>Be safe to call from the server thread (scanners run inline during a sweep
 *       or immediately after an event fires). If a scanner needs to touch the
 *       world, it's already on the correct thread.</li>
 *   <li>Be idempotent: calling {@link #scan(ScanContext)} twice with the same
 *       underlying state must produce the same set of UUIDs. The orchestrator
 *       may invoke a scanner multiple times in a single sweep (e.g., purge
 *       re-scans at confirm time).</li>
 *   <li>Not block. If the underlying source is slow (region-file NBT reads,
 *       network queries), the scanner must either short-circuit or be behind
 *       its own throttle. A slow scanner stalls every GC operation.</li>
 *   <li>Not mutate world state. Scanning is read-only; mutations belong in
 *       the orchestrator.</li>
 * </ul>
 *
 * <p><b>Failure handling.</b> A scanner that throws doesn't crash the GC —
 * {@link DiskImageGc} catches and logs. But a scanner that silently skips a
 * referenced UUID (return without adding) leaks the image to eventual deletion.
 * Prefer "add conservatively" over "skip on uncertainty" when in doubt.
 */
@FunctionalInterface
public interface DiskImageScanner {
    /**
     * Walk the source this scanner is responsible for and report every
     * referenced {@code STORAGE_UUID} to {@code ctx}.
     */
    void scan(ScanContext ctx);
}
