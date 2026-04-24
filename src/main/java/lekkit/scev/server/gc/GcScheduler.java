/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server.gc;

import com.mojang.logging.LogUtils;
import lekkit.scev.main.ScevConfig;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/**
 * Fires the periodic sweep when the {@code gc.sweep_enabled} config knob is
 * true. Hooks {@link ServerTickEvent.Post}; every 20 ticks (= 1 second of
 * wall clock at normal TPS) checks whether enough time has passed to dispatch
 * a sweep.
 *
 * <h2>Why one-second granularity</h2>
 *
 * <p>Sweep cadence is measured in hours. Per-tick check is pointless waste;
 * per-second check is plenty. Low-resolution timing also means lagging
 * servers (low TPS) still trigger sweeps at approximately the right wall
 * clock interval rather than drifting with TPS.
 *
 * <h2>Why wall-clock, not tick-count</h2>
 *
 * <p>Wall-clock interval is what admins configure ("sweep every 24 hours").
 * {@code System.currentTimeMillis()} is the straightforward reading.
 * Server-downtime edge case: if the server was offline for 30 days, the
 * first post-boot tick sees 30-day delta → fires a sweep immediately. That's
 * arguably correct: the retention window did accumulate, and there may be
 * genuine orphans to clean. No special handling.
 *
 * <h2>What the sweep does</h2>
 *
 * <p>Full scan via {@link GcRunner#sweep}. Runs inline on the server thread
 * — scans walk loaded chunks and inventories (cheap on typical worlds),
 * deletion is a small number of file ops. No need for a worker thread.
 */
public final class GcScheduler {
    private static final Logger LOG = LogUtils.getLogger();

    /** Check the schedule once per second. */
    private static final int CHECK_INTERVAL_TICKS = 20;

    /** Millis at last successful dispatch. {@code 0L} = never dispatched. */
    private static volatile long lastSweepMillis = 0L;

    private static int tickCounter = 0;

    private GcScheduler() {}

    /** Reset state on server start — old timestamps don't carry across worlds. */
    public static void reset() {
        lastSweepMillis = System.currentTimeMillis();
        tickCounter = 0;
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        if (++tickCounter < CHECK_INTERVAL_TICKS) return;
        tickCounter = 0;

        if (!ScevConfig.GC_SWEEP_ENABLED.get()) return;

        DiskImageGc gc = ScevGc.active();
        if (gc == null) return;

        long nowMillis = System.currentTimeMillis();
        long intervalMillis = ScevConfig.GC_SWEEP_INTERVAL_HOURS.get() * 3_600_000L;
        if (nowMillis - lastSweepMillis < intervalMillis) return;

        MinecraftServer server = event.getServer();
        try {
            GcResult r = GcRunner.sweep(gc, server, false);
            if (r.affected() > 0) {
                LOG.info("[scev-gc] scheduled sweep: deleted={} freed={} bytes",
                        r.affected(), r.bytesFreed());
            } else {
                LOG.debug("[scev-gc] scheduled sweep: nothing to delete");
            }
            gc.registry().save();
        } catch (Throwable t) {
            // A sweep failure must not crash the server tick loop.
            LOG.error("[scev-gc] scheduled sweep failed", t);
        }
        lastSweepMillis = nowMillis;
    }
}
