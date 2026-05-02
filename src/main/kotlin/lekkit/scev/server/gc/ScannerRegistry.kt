/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server.gc

import com.mojang.logging.LogUtils

/**
 * Extensible registry of [DiskImageScanner]s. The GC orchestrator asks this
 * class for "every scanner we should run right now"; every entry gets invoked
 * in registration order.
 *
 * Built-in scanners are registered during `FMLCommonSetupEvent`. Mod-compat
 * modules (AE2, RS, Create, Mekanism, …) can register their own scanner from
 * their own common-setup hook — no classpath dep on scev required beyond
 * [DiskImageScanner].
 *
 * Plain singleton guarded by class-level synchronization. Registration happens
 * at mod init; queries during the GC path. Contention is nil.
 *
 * Scanner contract: see [DiskImageScanner]. Be fast, idempotent, read-only,
 * and report UUIDs conservatively.
 */
object ScannerRegistry {
    private val log = LogUtils.getLogger()

    /** Backing list. Registration order is preserved so logs stay stable. */
    private val scanners = mutableListOf<DiskImageScanner>()

    /**
     * Add [scanner] to the active set. Repeat registrations of the **same
     * instance** are ignored (identity check); different-instance duplicates
     * from the same source are allowed.
     */
    @JvmStatic @Synchronized
    fun register(scanner: DiskImageScanner) {
        if (scanners.any { it === scanner }) return
        scanners += scanner
        log.debug("Registered scev GC scanner: {}", scanner.javaClass.name)
    }

    /** Defensive snapshot of the current scanner list. */
    @JvmStatic @Synchronized
    fun snapshot(): List<DiskImageScanner> = scanners.toList()

    /** For tests + status readouts. */
    @JvmStatic @Synchronized
    fun size(): Int = scanners.size

    /** Test-only: clear all registrations. Avoids leaking state between tests. */
    @JvmStatic @Synchronized
    fun clearForTests() { scanners.clear() }
}
