/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server.gc;

import org.jetbrains.annotations.Nullable;

/**
 * Tiny holder for the currently-active {@link DiskImageGc}. The GC instance
 * is world-specific (its registry lives under {@code <world>/scev/images/}),
 * so one is installed on {@code ServerStartingEvent} and uninstalled on
 * {@code ServerStoppingEvent}.
 *
 * <p>Between those events, the event-driven listener, the scheduled sweep,
 * and the {@code /scev gc} command all need to find the active instance.
 * This holder is the rendezvous point — volatile so a stop on one thread
 * is visible to an event firing on another.
 *
 * <p>When the active GC is {@code null} (no server running, or between
 * worlds in single-player), all paths no-op cleanly: callers short-circuit
 * on null and nothing gets deleted.
 */
public final class ScevGc {
    private static volatile @Nullable DiskImageGc active;

    private ScevGc() {}

    /**
     * Install the GC instance for the currently-starting server. Called
     * from {@code ServerStartingEvent} after the world path is known.
     *
     * <p>Replaces any prior instance — the previous world's GC is dropped.
     * Callers must call {@link #uninstall()} on server stop to release.
     */
    public static void install(DiskImageGc gc) {
        active = gc;
    }

    /** Clear the active GC. Called on {@code ServerStoppingEvent}. */
    public static void uninstall() {
        active = null;
    }

    /**
     * Current GC or {@code null} if no server is running. All call sites
     * must null-check — pre-server test code and between-world transitions
     * both see null here.
     */
    public static @Nullable DiskImageGc active() {
        return active;
    }
}
