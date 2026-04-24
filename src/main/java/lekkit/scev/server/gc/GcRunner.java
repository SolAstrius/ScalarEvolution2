/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server.gc;

import com.mojang.logging.LogUtils;
import java.util.Collection;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Thin facade that plugs {@link ScannerRegistry} into {@link DiskImageGc}.
 *
 * <p>Production callers (event listener, scheduled sweep, command handlers)
 * invoke a GcRunner method, which:
 *
 * <ol>
 *   <li>Builds a fresh {@link ScanContext}.</li>
 *   <li>Runs every registered {@link DiskImageScanner} against it.</li>
 *   <li>Hands the resulting live-set + candidates to the matching
 *       {@link DiskImageGc} method.</li>
 *   <li>Returns the {@link GcResult} for caller-side logging / reporting.</li>
 * </ol>
 *
 * <p>Scanner exceptions are caught and logged so one misbehaving scanner
 * doesn't take down GC for the whole server. A scanner that throws every
 * time will be silent but the rest of the GC operates normally.
 */
public final class GcRunner {
    private static final Logger LOG = LogUtils.getLogger();

    private GcRunner() {}

    /**
     * Event-driven path: called when an {@code ItemEntity} with
     * {@code STORAGE_UUID}-carrying stacks is destroyed.
     *
     * @param gc             orchestrator bound to the current world
     * @param server         current server (may be null in edge cases —
     *                       then most scanners no-op and the live set
     *                       reflects only running machines)
     * @param candidates     UUIDs harvested from the destroyed stack
     * @param excludeEntity  entity UUID of the entity that's about to die
     *                       (NOT a STORAGE_UUID). The {@link lekkit.scev.server.gc.scanners.EntityScanner}
     *                       skips this entity so its own stack doesn't
     *                       self-protect against deletion.
     */
    public static GcResult event(
            DiskImageGc gc,
            @Nullable MinecraftServer server,
            Collection<UUID> candidates,
            @Nullable UUID excludeEntity) {
        ScanContext ctx = new ScanContext(server);
        if (excludeEntity != null) ctx.excludeEntity(excludeEntity);
        runScanners(ctx);
        return gc.runEventDriven(candidates, ctx.liveUuids(), System.currentTimeMillis());
    }

    /**
     * Periodic safety-net sweep. Scans everything, deletes UUIDs unseen
     * longer than {@link GcPolicy#sweepRetentionMillis()}.
     */
    public static GcResult sweep(DiskImageGc gc, @Nullable MinecraftServer server, boolean dryRun) {
        ScanContext ctx = new ScanContext(server);
        runScanners(ctx);
        return gc.runSweep(ctx.liveUuids(), dryRun, System.currentTimeMillis());
    }

    /**
     * Forced purge. Deletes every image file that isn't live or protected,
     * bypassing retention + grace. Backs the {@code /scev gc purge} command.
     */
    public static GcResult purge(DiskImageGc gc, @Nullable MinecraftServer server, boolean dryRun) {
        ScanContext ctx = new ScanContext(server);
        runScanners(ctx);
        return gc.runPurge(ctx.liveUuids(), dryRun, System.currentTimeMillis());
    }

    private static void runScanners(ScanContext ctx) {
        for (DiskImageScanner scanner : ScannerRegistry.snapshot()) {
            try {
                scanner.scan(ctx);
            } catch (Throwable t) {
                // One scanner failing must not prevent the rest from running.
                // Log at warn with the scanner class so it's grep-able.
                LOG.warn("[scev-gc] scanner {} threw during scan: {}",
                        scanner.getClass().getName(), t.toString(), t);
            }
        }
    }
}
