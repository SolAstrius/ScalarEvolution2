/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.terminal

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.logging.LogUtils
import java.util.UUID
import lekkit.scev.blockentity.TerminalKind
import lekkit.scev.main.ScalarEvolution
import lekkit.scev.network.SerialAmbientSubscribePayload
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.ResourceLocation
import net.neoforged.neoforge.network.PacketDistributor
import org.lwjgl.system.MemoryUtil

/**
 * Single-slot client-side terminal host: owns at most one
 * [MltermBackend] + texture per JVM (the embed buffer hard-limits us
 * to one mlterm at a time anyway), keyed by machine UUID.
 *
 * Two consumers want a live terminal texture client-side:
 *  1. [lekkit.scev.client.screen.TerminalScreen] when its GUI is open.
 *  2. [lekkit.scev.client.render.blockentity.TerminalRenderer] for the
 *     in-world block face.
 *
 * Without this host they'd race to construct the singleton
 * `MltermBackend` and tear each other down; with it the GUI's open
 * lifetime + the in-world render share the same backend and the same
 * texture.
 *
 * **Lifecycle:**
 *  - [acquire] (uuid) builds a backend if there isn't one or the UUID
 *    differs, subscribes to server-side serial bytes, sends an
 *    ambient-subscribe to the server so kernel TX flows to us even
 *    when no menu is open. Returns the existing [Handle] if already
 *    acquired for the same UUID.
 *  - [release] decrements an internal refcount. When the count drops
 *    to zero the backend stays alive (last-frame frozen), so the
 *    block face remains visible after the GUI closes.
 *  - Acquiring a different UUID tears down the old backend, drops
 *    its ambient subscription, and builds a fresh one for the new
 *    UUID. Means at most one VT100 in the world is "live" at a time
 *    — the most-recently-opened one. Multi-block-live is a Stage 2
 *    feature once we lift mlterm's one-per-process limit.
 *
 * **Threading:** all calls happen on the MC client/render thread.
 * The backend's input ring is SPSC so concurrent feed from the
 * SerialDispatcher receiver (which already hops to the render
 * thread via Minecraft.execute) is safe.
 */
object TerminalActiveHost {

    private val LOG = LogUtils.getLogger()

    /** A live terminal session — one per active UUID. */
    class Handle internal constructor(
        val machineUuid: UUID,
        /** Era / capability profile this backend was constructed for.
         *  Frozen for the handle's lifetime; if a different kind acquires
         *  the slot we tear down + rebuild rather than mutate. */
        val kind: TerminalKind,
        val backend: MltermBackend,
        val nativeImage: NativeImage,
        val texLocation: ResourceLocation,
        internal val texture: DynamicTexture,
        internal val nativeImagePixelsPtr: Long,
    ) {
        val pixelW: Int get() = backend.pixelW
        val pixelH: Int get() = backend.pixelH

        /**
         * Rolling history of every byte fed to the backend's parser
         * since this handle was acquired. Used to repaint the live
         * screen when exiting Setup mode (Setup pages are painted
         * over the same backend, clobbering its parser state — we
         * re-feed the history so mlterm rebuilds the live view).
         *
         * Capped to keep memory bounded: when the buffer grows past
         * [HISTORY_CAP_BYTES] we drop the leading half. The dropped
         * bytes are typically early kernel boot output that's already
         * scrolled off-screen anyway, so the visual loss on Setup-exit
         * replay is negligible compared to the saved memory.
         */
        internal val liveHistory: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()

        /** True while a Setup overlay is being painted into the
         *  backend. While set, [liveHistory] still accumulates new
         *  bytes but the backend isn't fed — so the Setup pages don't
         *  get clobbered by kernel output during display. */
        @Volatile internal var liveFeedPaused: Boolean = false

        internal fun appendLive(bytes: ByteArray) {
            // Cap to avoid unbounded growth. Drop the older half on
            // overflow (common case is steady-state appends near the
            // tail; the truncation hurts only the very-early boot log
            // on Setup-exit replay).
            if (liveHistory.size() + bytes.size > HISTORY_CAP_BYTES) {
                val full = liveHistory.toByteArray()
                liveHistory.reset()
                val keepFrom = (full.size - HISTORY_CAP_BYTES / 2).coerceAtLeast(0)
                liveHistory.write(full, keepFrom, full.size - keepFrom)
            }
            liveHistory.write(bytes, 0, bytes.size)
        }

        /** Previous-frame pixel buffer for the phosphor-persistence
         *  effect. Lazy-allocated when the first
         *  [applyPersistence] call with pct > 0 lands; freed back to
         *  null when pct=0 so a player who turns persistence off
         *  immediately reclaims the ~600 KB. */
        private var prevFrame: IntArray? = null

        /**
         * Phosphor decay / motion-blur effect. Reads the freshly-rendered
         * pixels at [nativeImagePixelsPtr], blends them with the previous
         * frame's blended output (kept in [prevFrame]), and writes the
         * result back to the same buffer for upload.
         *
         * Math per channel: `out = current × (1 - decay) + prev × decay`
         * where `decay = persistencePct / 100`. So persistence=50 means
         * the screen takes ~2 frames to "catch up" with the input
         * (each frame the residue halves), giving the soft phosphor
         * smear real CRTs had at slow scan rates. Capped at 90 to keep
         * the screen from going totally static — a true 100% would
         * never decay.
         *
         * Done CPU-side rather than via a shader ping-pong because:
         * (a) the source resolution is tiny (480×312 = 150K pixels),
         * (b) we already touch the buffer right before
         * `texture.upload()`, (c) shader ping-pong needs an off-screen
         * RenderTarget which the BlockEntityRenderer pipeline doesn't
         * own.
         *
         * Idempotent on subsequent calls with the same persistence
         * value; safe to call every frame.
         */
        internal fun applyPersistence(persistencePct: Int) {
            if (persistencePct <= 0) {
                prevFrame = null                       // free the buffer
                return
            }
            val pct = persistencePct.coerceIn(0, 90)
            // Fixed-point: decay × 256, so the inner loop avoids float
            // division. invDecay × cur + decay × prev all in ints, then
            // shift right 8.
            val decay = pct * 256 / 100
            val invDecay = 256 - decay
            val nPixels = backend.pixelW * backend.pixelH
            val current = MemoryUtil.memIntBuffer(nativeImagePixelsPtr, nPixels)

            var prev = prevFrame
            if (prev == null || prev.size != nPixels) {
                // First frame in this persistence session — just snapshot
                // the current pixels into prev and return. Without this,
                // the very first frame would blend with whatever was in
                // the freshly-allocated IntArray (zero = black) and
                // produce a half-faded boot screen.
                prev = IntArray(nPixels)
                for (i in 0 until nPixels) prev[i] = current.get(i)
                prevFrame = prev
                return
            }

            for (i in 0 until nPixels) {
                val cur = current.get(i)
                val prv = prev[i]
                val r = ((cur          and 0xFF) * invDecay + (prv          and 0xFF) * decay) ushr 8
                val g = ((cur ushr  8  and 0xFF) * invDecay + (prv ushr  8  and 0xFF) * decay) ushr 8
                val b = ((cur ushr 16  and 0xFF) * invDecay + (prv ushr 16  and 0xFF) * decay) ushr 8
                val a =   cur ushr 24
                val blended = (a shl 24) or (b shl 16) or (g shl 8) or r
                current.put(i, blended)
                prev[i] = blended                      // history for next frame
            }
        }

        companion object {
            /** ~256 KB. An Alpine boot dumps ~80 KB; this leaves headroom
             *  for a few hours of steady-state shell output. Bumping is
             *  cheap if needed. */
            const val HISTORY_CAP_BYTES: Int = 256 * 1024
        }
    }

    private var current: Handle? = null
    private var refcount: Int = 0

    /** Snapshot the active handle if its UUID matches [uuid]. Used by
     *  the BE renderer to test "is this block the active terminal?"
     *  without triggering a swap. Read-only. */
    @JvmStatic
    fun peek(uuid: UUID): Handle? {
        val cur = current ?: return null
        return if (cur.machineUuid == uuid) cur else null
    }

    /** Acquire the slot for [uuid] with the given [kind]. Builds a
     *  backend if needed, swaps if a different (uuid, kind) currently
     *  holds the slot. Caller must call [release] eventually (paired).
     *
     *  Re-acquiring the same UUID with a different kind tears down
     *  and rebuilds — kind drives the underlying `vt_create_term` and
     *  isn't mutable on a live term. */
    @JvmStatic
    @JvmOverloads
    fun acquire(uuid: UUID, kind: TerminalKind = TerminalKind.DEFAULT): Handle {
        check(MltermNative.ensureLoaded()) {
            "libscev_term native isn't loaded — terminal GUI/render can't open"
        }
        val cur = current
        if (cur != null && cur.machineUuid == uuid && cur.kind == kind) {
            refcount++
            return cur
        }
        // UUID swap, kind swap, or first acquire: tear down any
        // previous slot.
        if (cur != null) {
            tearDown(cur)
            current = null
            refcount = 0
        }

        val backend = MltermBackend(kind.termType, kind.cols, kind.rows)
        val nativeImage = NativeImage(backend.pixelW, backend.pixelH, false)
        val texture = DynamicTexture(nativeImage)
        val texLoc = ScalarEvolution.rl("terminal/${UUID.randomUUID()}")
        Minecraft.getInstance().textureManager.register(texLoc, texture)
        val pixelsPtr = NativeImage::class.java
            .getDeclaredField("pixels")
            .apply { isAccessible = true }
            .getLong(nativeImage)
        check(pixelsPtr != 0L) { "NativeImage.pixels is 0 — buffer not allocated?" }

        val h = Handle(uuid, kind, backend, nativeImage, texLoc, texture, pixelsPtr)
        current = h
        refcount = 1

        // Subscribe to bytes for this UUID. The dispatcher's a single-
        // subscriber-per-UUID map so this overwrites any stale callback.
        SerialDispatcher.subscribe(uuid) { bytes ->
            // Network handler runs on the netty thread; hop to the
            // render thread before touching the backend (its input
            // ring is SPSC + the MC mod convention is "stay off
            // netty for any modwork").
            Minecraft.getInstance().execute {
                h.appendLive(bytes)
                if (!h.liveFeedPaused) {
                    backend.feed(bytes)
                }
            }
        }
        // Tell the server we're rendering this UUID even without a
        // menu open, so kernel TX bytes keep flowing for the in-world
        // block face. Server's targeted fan-out otherwise only sends
        // to viewers with a matching containerMenu.
        PacketDistributor.sendToServer(SerialAmbientSubscribePayload(uuid, true))

        LOG.debug("terminal host: acquired {} as {} ({}×{} px)",
            uuid, kind.termType, backend.pixelW, backend.pixelH)
        return h
    }

    /** Release one acquisition. The backend stays alive at refcount 0
     *  so the in-world block face remains live after the GUI closes;
     *  it's only torn down when a different UUID is [acquire]d. */
    @JvmStatic
    fun release() {
        if (refcount > 0) refcount--
        // Intentional: do NOT tear down at refcount 0. Block-face
        // rendering reuses the same handle without an explicit
        // acquire of its own; tearing down would make the block
        // freeze on a stale frame and stop receiving live bytes.
    }

    /**
     * Pause live kernel-TX feed into the backend. New bytes still
     * accumulate in [Handle.liveHistory]; they just don't get parsed
     * into the backend display. Idempotent; safe to call when no
     * handle exists.
     *
     * Pair with [resumeLiveFeed] when exiting Setup mode. The pause/
     * resume window is intentionally per-handle, not per-screen, so
     * the in-world block face also shows the Setup overlay (matches
     * real DEC behavior — the SET-UP key affected the whole terminal,
     * not "just my pane").
     */
    @JvmStatic
    fun pauseLiveFeed() {
        current?.liveFeedPaused = true
    }

    /**
     * Resume live feed and repaint the backend with the entire live
     * history (clear + home + replay). Idempotent. The replay walks
     * mlterm's parser through every byte we've seen, so the end
     * state matches what the backend would have shown if the pause
     * had never happened.
     *
     * Mlterm parses ~1MB/s; the typical 256KB cap means a worst-case
     * ~250ms hitch on Setup exit. Acceptable for a one-shot UI flip.
     */
    @JvmStatic
    fun resumeLiveFeed() {
        val h = current ?: return
        h.liveFeedPaused = false
        // ESC[2J ED-2 (clear screen) + ESC[H CUP home — wipes the
        // backend's current display before replay. Without this, the
        // Setup overlay's reverse-video cells would bleed through
        // the live content at positions the live history never wrote.
        val clear = byteArrayOf(0x1B, '['.code.toByte(), '2'.code.toByte(), 'J'.code.toByte(),
                                0x1B, '['.code.toByte(), 'H'.code.toByte())
        h.backend.feed(clear)
        h.backend.feed(h.liveHistory.toByteArray())
    }

    /** Force teardown regardless of refcount. Called from the
     *  client-disconnect / world-unload paths so we don't keep a
     *  backend (and its server-side ambient subscription) alive
     *  after the player leaves the world. */
    @JvmStatic
    fun shutdown() {
        val cur = current ?: return
        tearDown(cur)
        current = null
        refcount = 0
    }

    private fun tearDown(h: Handle) {
        try {
            // Drop the server's ambient subscription before closing
            // anything client-side, so any in-flight byte from the
            // server's fan-out lands in a still-valid backend.
            PacketDistributor.sendToServer(SerialAmbientSubscribePayload(h.machineUuid, false))
        } catch (t: Throwable) {
            LOG.debug("terminal host: ambient unsubscribe send failed: {}", t.toString())
        }
        SerialDispatcher.unsubscribe(h.machineUuid)
        try { Minecraft.getInstance().textureManager.release(h.texLocation) } catch (_: Throwable) {}
        try { h.backend.close() } catch (_: Throwable) {}
        LOG.debug("terminal host: torn down {}", h.machineUuid)
    }
}
