/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.terminal

import lekkit.mlterm.Mlterm

/**
 * Kotlin handle around a single native mlterm-fb-embed terminal.
 *
 * Same shape as the abandoned jexer-based `Terminal` class so the
 * upstream call sites (TerminalScreen, GlfwToVt) lift cleanly:
 *  - construct with grid dims
 *  - feed raw bytes via [feed] / [feedString]
 *  - read rendered pixels per frame via [render] or [renderToPtr]
 *  - close once done
 *
 * Internally each instance holds a `long` native handle to a
 * `scev_term_t`. mlterm's screen manager is process-global; the
 * native side enforces a one-at-a-time AND one-per-JVM-lifetime
 * invariant — see the constructor's check for the exact reason
 * a second open within a single JVM run is currently refused.
 */
class MltermBackend(
    /** Term type passed to mlterm (`vt100`, `vt220`, `vt340`, `xterm`,
     *  …). Drives DA reply, accepted escape sequences, and which
     *  terminfo entry the guest should match against. */
    val termType: String = DEFAULT_TERM_TYPE,
    val cols: Int = DEFAULT_COLS,
    val rows: Int = DEFAULT_ROWS,
) : AutoCloseable {

    private var handle: Long = Mlterm.nativeNew(termType, cols, rows)
    val pixelW: Int = if (handle != 0L) Mlterm.nativePixelW(handle) else 0
    val pixelH: Int = if (handle != 0L) Mlterm.nativePixelH(handle) else 0

    init {
        check(handle != 0L) {
            "MltermBackend init failed for ${termType} ${cols}×$rows. Likely causes:\n" +
            " - libscev_term native isn't loaded (see MltermNative.ensureLoaded log)\n" +
            " - another MltermBackend is currently open (single-buffer embed " +
            "limits us to one visible terminal at a time per JVM); close the " +
            "existing one before opening a new one"
        }
        // Track for shutdown-time cleanup so the worker thread joins
        // and mlterm's process-globals are torn down cleanly when
        // the JVM exits — particularly important if the user quits
        // the game with the VT100 GUI still on screen.
        synchronized(liveBackends) { liveBackends.add(this) }
    }

    override fun close() {
        // Snapshot + null FIRST, then free. Concurrent feed/render
        // calls (e.g. a Minecraft.execute lambda queued by the
        // SerialDispatcher receive) check handle != 0L and read the
        // pointer; if we freed before nulling, the brief window
        // between nativeDestroy() returning and `handle = 0L` would
        // let those calls dereference freed memory. Snapshot makes
        // the destroy operate on a local that's no longer reachable
        // from any other thread by the time the C side touches it.
        val h = handle
        if (h != 0L) {
            handle = 0L
            synchronized(liveBackends) { liveBackends.remove(this) }
            Mlterm.nativeDestroy(h)
        }
    }

    /** Push raw bytes through the VT parser. Returns count accepted
     *  (typically all). No-op once [close] has run. */
    fun feed(bytes: ByteArray): Int {
        if (handle == 0L || bytes.isEmpty()) return 0
        return Mlterm.nativeWrite(handle, bytes, 0, bytes.size)
    }

    fun feedString(s: String): Int = feed(s.toByteArray(Charsets.UTF_8))

    /** Drain pending reply bytes from the worker's reply_ring. Each
     *  call returns the actual bytes mlterm produced in response to
     *  guest queries (DA / DSR / mouse-report / etc.) — the host is
     *  expected to forward these back to the guest, same direction
     *  as typed keystrokes. Returns 0 if nothing's queued. */
    fun pollReply(out: ByteArray): Int {
        if (handle == 0L || out.isEmpty()) return 0
        return Mlterm.nativePollReply(handle, out, 0, out.size)
    }

    /** Pump one frame and copy rendered pixels into [out].
     *  [out] must hold at least [pixelH] * [stride] ints (ARGB8888). */
    fun render(out: IntArray, stride: Int = pixelW) {
        if (handle == 0L) return
        require(out.size >= pixelH * stride) {
            "render buffer too small: need ${pixelH * stride} ints, got ${out.size}"
        }
        Mlterm.nativeRender(handle, out, stride)
    }

    /** Pump one frame and write pixels directly to [outPtr] as RGBA8888
     *  byte order. [outPtr] must point at a buffer of at least
     *  [pixelH] * [stride] * 4 bytes. Caller owns the lifetime of
     *  the buffer; out-of-bounds writes corrupt the heap. */
    fun renderToPtr(outPtr: Long, stride: Int = pixelW) {
        if (handle == 0L || outPtr == 0L) return
        Mlterm.nativeRenderToPtr(handle, outPtr, stride)
    }

    companion object {
        const val DEFAULT_TERM_TYPE: String = "vt100"
        const val DEFAULT_COLS: Int = 80
        const val DEFAULT_ROWS: Int = 24

        private val liveBackends: MutableSet<MltermBackend> = HashSet()

        init {
            Runtime.getRuntime().addShutdownHook(Thread({
                // Race note: a render-thread close() can run concurrently
                // with this hook iterating its snapshot. close() removes
                // from liveBackends under the same lock; double-close on
                // an already-closed backend is a no-op (handle==0L). The
                // worst case is the hook briefly holds the snapshot lock
                // while a render-thread close waits to remove. C-side
                // state isn't double-touched: nativeDestroy nulls handle
                // synchronously inside the lock.
                val snapshot = synchronized(liveBackends) { liveBackends.toList() }
                for (b in snapshot) {
                    try { b.close() } catch (_: Throwable) {}
                }
                // Now that no live backend can be touched and the
                // render thread is gone, run mlterm's process-global
                // final. Doing this on a per-screen close froze the
                // game; deferring to shutdown is the safe place.
                try { Mlterm.nativeShutdown() } catch (_: Throwable) {}
            }, "scev-mlterm-shutdown"))
        }
    }
}
