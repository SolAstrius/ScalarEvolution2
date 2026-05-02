/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.mlterm;

/**
 * JNI declarations for the mlterm-fb-embed wrapper.
 *
 * <p>Architecture: a per-{@code scev_term_t} worker thread owns
 * mlterm. Java methods on the render thread go through a lock-free
 * SPSC ring (write side) and an atomic-published double-buffer
 * (read side). No Java method calls into mlterm directly.
 *
 * <p>Threading:
 * <ul>
 *   <li>{@link #nativeInit} — must run before any {@link #nativeNew},
 *       safe from any thread (idempotent + guarded internally).
 *   <li>{@link #nativeNew} / {@link #nativeDestroy} — use them from
 *       a single JVM-side coordinator (the screen open/close path).
 *       New spawns the worker; Destroy joins it.
 *   <li>{@link #nativeWrite} — non-blocking ring enqueue. Safe from
 *       any thread; producer-of-one is the only safety constraint
 *       (don't call concurrently from two threads on the same
 *       handle).
 *   <li>{@link #nativeRender} / {@link #nativeRenderToPtr} —
 *       atomic-load of the publish front + memcpy. Safe from any
 *       thread.
 *   <li>{@link #nativeShutdown} — call once at JVM exit. Frees
 *       process-global mlterm state; idempotent.
 *   <li>{@link #nativePixelW} / {@link #nativePixelH} — pure reads
 *       of immutable handle state.
 * </ul>
 */
public final class Mlterm {

    private Mlterm() {}

    /** One-time process init. Locks mlterm out of all filesystem
     *  reads, injects {@code fontPath} as the bundled font for every
     *  charset mlterm asks for, sets up term + color managers.
     *  Idempotent. Returns true on success. */
    public static native boolean nativeInit(String fontPath);

    /** Build a term sized for {@code cols × rows} text cells, with
     *  the given {@code termType} (e.g. "vt100", "vt220", "vt340",
     *  "vt340"). Term type drives mlterm's DA reply, which terminfo
     *  entry the guest should match against, and which escape
     *  sequences are accepted. Spawns a worker thread that owns
     *  mlterm. Returns a native handle (cast from a uintptr_t
     *  pointer) or 0 on failure. */
    public static native long nativeNew(String termType, int cols, int rows);

    /** Tear down a term. Signals the worker, joins it, frees all
     *  associated state. No-op on handle == 0. */
    public static native void nativeDestroy(long handle);

    /** Final mlterm process-global teardown. Idempotent. Intended
     *  for the JVM shutdown hook. */
    public static native void nativeShutdown();

    /** Pixel width of the rendered surface. */
    public static native int nativePixelW(long handle);

    /** Pixel height of the rendered surface. */
    public static native int nativePixelH(long handle);

    /** Push raw bytes into the worker's input ring. Returns the
     *  number actually accepted (== len in the common case; less
     *  if the ring is briefly full, which would mean the host is
     *  pushing far faster than mlterm can parse — terminal input
     *  shouldn't ever hit this). */
    public static native int nativeWrite(long handle, byte[] data, int off, int len);

    /** Drain pending reply bytes (e.g. DA / DSR responses mlterm
     *  generated in response to host queries it parsed in the
     *  guest TX stream). Caller is expected to forward these back
     *  toward the guest — same destination as typed keystrokes.
     *  Returns the number of bytes copied into {@code out[off..off+cap)}. */
    public static native int nativePollReply(long handle, byte[] out, int off, int cap);

    /** Snapshot the latest published frame into {@code out} as
     *  ARGB8888 ints. {@code out} must hold at least
     *  {@code pixelH * stridePx} ints. */
    public static native void nativeRender(long handle, int[] out, int stridePx);

    /** Snapshot the latest published frame into the buffer at
     *  {@code outPtr} as RGBA8888 byte order (R at byte 0).
     *  {@code stridePx} is in pixels (4 bytes each). The pointer
     *  must remain valid for the duration of the call; OOB writes
     *  corrupt the JVM heap. */
    public static native void nativeRenderToPtr(long handle, long outPtr, int stridePx);
}
