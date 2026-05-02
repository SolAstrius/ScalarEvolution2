/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server.gc

import com.mojang.logging.LogUtils
import lekkit.scev.main.ScevConfig
import net.neoforged.neoforge.event.tick.ServerTickEvent

/**
 * Fires the periodic sweep when the `gc.sweep_enabled` config knob is true.
 * Hooks `ServerTickEvent.Post`; every 20 ticks (= 1 second of wall clock at
 * normal TPS) checks whether enough time has passed to dispatch a sweep.
 *
 * **One-second granularity.** Sweep cadence is measured in hours; per-tick
 * checking is pointless waste. Low-resolution timing also means lagging
 * servers (low TPS) still trigger sweeps at approximately the right wall-
 * clock interval rather than drifting with TPS.
 *
 * **Wall-clock not tick-count.** Wall clock is what admins configure ("sweep
 * every 24 hours"). `System.currentTimeMillis()` is the straightforward
 * reading. Server-downtime edge case: if the server was offline for 30 days,
 * the first post-boot tick sees a 30-day delta and fires immediately. That's
 * arguably correct — the retention window did accumulate.
 *
 * **What the sweep does.** Full scan via [GcRunner.sweep]. Runs inline on
 * the server thread; scans walk loaded chunks/inventories, deletion is a
 * small number of file ops.
 */
object GcScheduler {
    private val log = LogUtils.getLogger()

    /** Check the schedule once per second. */
    private const val CHECK_INTERVAL_TICKS = 20

    /** Millis at last successful dispatch. `0L` = never dispatched. */
    @Volatile private var lastSweepMillis = 0L
    private var tickCounter = 0

    /** Reset state on server start — old timestamps don't carry across worlds. */
    @JvmStatic fun reset() {
        lastSweepMillis = System.currentTimeMillis()
        tickCounter = 0
    }

    @JvmStatic fun onServerTick(event: ServerTickEvent.Post) {
        if (++tickCounter < CHECK_INTERVAL_TICKS) return
        tickCounter = 0

        if (!ScevConfig.GC_SWEEP_ENABLED.get()) return
        val gc = ScevGc.active() ?: return

        val nowMillis = System.currentTimeMillis()
        val intervalMillis = ScevConfig.GC_SWEEP_INTERVAL_HOURS.get() * 3_600_000L
        if (nowMillis - lastSweepMillis < intervalMillis) return

        try {
            val r = GcRunner.sweep(gc, event.server, false)
            if (r.affected() > 0) {
                log.info("[scev-gc] scheduled sweep: deleted={} freed={} bytes", r.affected(), r.bytesFreed)
            } else {
                log.debug("[scev-gc] scheduled sweep: nothing to delete")
            }
            gc.registry().save()
        } catch (t: Throwable) {
            // A sweep failure must not crash the server tick loop.
            log.error("[scev-gc] scheduled sweep failed", t)
        }
        lastSweepMillis = nowMillis
    }
}
