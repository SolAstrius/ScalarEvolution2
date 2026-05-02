/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server.gc

/**
 * Holder for the currently-active [DiskImageGc]. The GC instance is world-
 * specific (its registry lives under `<world>/scev/images/`), so one is
 * installed on `ServerStartingEvent` and uninstalled on `ServerStoppingEvent`.
 *
 * Between those events the event-driven listener, the scheduled sweep, and
 * the `/scev gc` command all need to find the active instance. This holder is
 * the rendezvous point — `@Volatile` so a stop on one thread is visible to an
 * event firing on another.
 *
 * When the active GC is `null` (no server running, or between worlds in
 * single-player), all paths no-op cleanly: callers short-circuit on null and
 * nothing gets deleted.
 */
object ScevGc {
    @Volatile @JvmStatic
    private var activeGc: DiskImageGc? = null

    /**
     * Install the GC instance for the currently-starting server. Replaces any
     * prior instance. Callers must call [uninstall] on server stop.
     */
    @JvmStatic fun install(gc: DiskImageGc) { activeGc = gc }

    /** Clear the active GC. Called on `ServerStoppingEvent`. */
    @JvmStatic fun uninstall() { activeGc = null }

    /**
     * Current GC or `null` if no server is running. All call sites must null-
     * check — pre-server test code and between-world transitions both see null.
     */
    @JvmStatic fun active(): DiskImageGc? = activeGc
}
