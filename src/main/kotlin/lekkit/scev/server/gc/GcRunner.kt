/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server.gc

import com.mojang.logging.LogUtils
import java.util.UUID
import net.minecraft.server.MinecraftServer

/**
 * Thin facade that plugs [ScannerRegistry] into [DiskImageGc].
 *
 * Production callers (event listener, scheduled sweep, command handlers)
 * invoke a [GcRunner] method which:
 *   1. Builds a fresh [ScanContext]
 *   2. Runs every registered [DiskImageScanner] against it
 *   3. Hands the live-set + candidates to the matching [DiskImageGc] method
 *   4. Returns the [GcResult] for caller-side logging / reporting
 *
 * Scanner exceptions are caught and logged so one misbehaving scanner doesn't
 * take down GC for the whole server. A scanner that throws every time will be
 * silent but the rest of the GC operates normally.
 */
object GcRunner {
    private val log = LogUtils.getLogger()

    /**
     * Event-driven path: called when an `ItemEntity` carrying `STORAGE_UUID`
     * stacks is destroyed.
     *
     * @param excludeEntity entity UUID of the entity that's about to die (NOT
     *   a STORAGE_UUID). The [lekkit.scev.server.gc.scanners.EntityScanner]
     *   skips this entity so its own stack doesn't self-protect.
     */
    @JvmStatic fun event(
        gc: DiskImageGc,
        server: MinecraftServer?,
        candidates: Collection<UUID>,
        excludeEntity: UUID?,
    ): GcResult {
        val ctx = ScanContext(server)
        if (excludeEntity != null) ctx.excludeEntity(excludeEntity)
        runScanners(ctx)
        return gc.runEventDriven(candidates, ctx.liveUuids(), System.currentTimeMillis())
    }

    /** Periodic safety-net sweep. Deletes UUIDs unseen longer than [GcPolicy.sweepRetentionMillis]. */
    @JvmStatic fun sweep(gc: DiskImageGc, server: MinecraftServer?, dryRun: Boolean): GcResult {
        val ctx = ScanContext(server)
        runScanners(ctx)
        return gc.runSweep(ctx.liveUuids(), dryRun, System.currentTimeMillis())
    }

    /** Forced purge — bypasses retention + grace. Backs `/scev gc purge`. */
    @JvmStatic fun purge(gc: DiskImageGc, server: MinecraftServer?, dryRun: Boolean): GcResult {
        val ctx = ScanContext(server)
        runScanners(ctx)
        return gc.runPurge(ctx.liveUuids(), dryRun, System.currentTimeMillis())
    }

    private fun runScanners(ctx: ScanContext) {
        for (scanner in ScannerRegistry.snapshot()) {
            try {
                scanner.scan(ctx)
            } catch (t: Throwable) {
                // One scanner failing must not prevent the rest from running.
                log.warn("[scev-gc] scanner {} threw during scan: {}", scanner.javaClass.name, t.toString(), t)
            }
        }
    }
}
