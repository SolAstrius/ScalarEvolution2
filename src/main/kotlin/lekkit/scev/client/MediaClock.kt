/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import lekkit.scev.core.time.Micros
import lekkit.scev.core.time.Nanos

/**
 * Client-side media clock, one per live machine UUID. Anchors
 * server-side PTS to the client's local wall clock so the video
 * renderer can answer "what PTS should be on screen right now?" —
 * which drives the jitter buffer's pick-newest-before-now selection
 * in `DisplayManager` and lets the audio + video renderers agree on
 * a shared timeline.
 *
 * **Operation.**
 *
 * - On the first frame seen (audio or video), [onIncoming] anchors
 *   the clock: record the current [Nanos] and the frame's PTS. From
 *   then on, [currentPts] returns `anchoredPts + (nanoNow − anchoredNanos)`
 *   expressed in [Micros].
 * - Subsequent incoming frames usually just advance the buffers; the
 *   media clock stays put.
 * - If a frame arrives with PTS far below what the current clock
 *   would predict, that's a server-side machine reset (pause →
 *   unpause, power cycle, snapshot load). Re-anchor: the new frame
 *   defines a fresh epoch, both audio and video for that UUID pick
 *   up from there. The A/V sync window does not span epochs, which
 *   is the correct behavior — we don't want video from before the
 *   reset bleeding into audio after.
 *
 * **Threading.** [onIncoming] runs on the Netty network thread (payload
 * handler). [currentPts] runs on the render thread. [anchorNanos] and
 * [anchorPts] are `@Volatile` so both sides see a consistent pair; the
 * re-anchor path is idempotent (same pts twice → no observable
 * effect) so a torn read across a rare concurrent re-anchor is
 * harmless.
 *
 * **State when no frames have ever arrived.** [currentPts] returns
 * [Micros.ZERO]. No renderer should be querying the clock before any
 * frame has arrived, but the behavior is defined rather than
 * throwing.
 */
class MediaClock {

    @Volatile private var anchorNanos: Long = UNSET
    @Volatile private var anchorPtsMicros: Long = 0L

    /**
     * Register an arriving frame's PTS with the clock. Anchors on the
     * first frame; re-anchors when the arriving PTS is below the
     * projected now by more than [RESET_THRESHOLD_MICROS] (machine
     * reset on the server side).
     */
    fun onIncoming(pts: Micros) {
        val nowNs = System.nanoTime()
        val anchor = anchorNanos
        if (anchor == UNSET) {
            anchorNanos = nowNs
            anchorPtsMicros = pts.value
            return
        }
        val elapsedMicros = (nowNs - anchor) / 1_000L
        val expectedPts = anchorPtsMicros + elapsedMicros
        if (pts.value < expectedPts - RESET_THRESHOLD_MICROS) {
            // Server-side epoch reset (pause/unpause, power cycle).
            // New anchor; audio and video for this UUID will rejoin on
            // the new timeline.
            anchorNanos = nowNs
            anchorPtsMicros = pts.value
        }
    }

    /** The PTS the media clock says should currently be on screen. */
    fun currentPts(): Micros {
        val anchor = anchorNanos
        if (anchor == UNSET) return Micros.ZERO
        val elapsedMicros = (System.nanoTime() - anchor) / 1_000L
        return Micros(anchorPtsMicros + elapsedMicros)
    }

    /** `true` once any frame has arrived; [currentPts] advances thereafter. */
    val isAnchored: Boolean
        get() = anchorNanos != UNSET

    /** Forget the anchor. Next [onIncoming] starts fresh. */
    fun reset() {
        anchorNanos = UNSET
        anchorPtsMicros = 0L
    }

    companion object {
        /**
         * PTS more than 500 ms earlier than predicted triggers a
         * re-anchor. This is comfortably larger than any normal
         * network jitter (tens of ms) or audio jitter buffer depth
         * (~320 ms) so we don't trigger on those, but well below the
         * typical pause duration where a reset is warranted.
         */
        private const val RESET_THRESHOLD_MICROS: Long = 500_000L

        private const val UNSET: Long = Long.MIN_VALUE
    }
}

/**
 * Per-UUID registry of [MediaClock]s. Single object so both
 * [lekkit.scev.client.DisplayManager] (video) and
 * [lekkit.scev.client.SoundStreamPlayer] (audio) consult the same
 * clock for any given machine.
 */
object MediaClockRegistry {
    private val clocks = ConcurrentHashMap<UUID, MediaClock>()

    /**
     * The MediaClock for this machine UUID, creating one on first access.
     * Safe to call from any thread.
     */
    @JvmStatic
    fun get(uuid: UUID): MediaClock = clocks.getOrPut(uuid) { MediaClock() }

    /**
     * Drop the MediaClock for [uuid]. Call on the client side when we
     * know a machine has been destroyed (e.g. via the dispose sentinel
     * in [lekkit.scev.network.DisplayPayload]).
     */
    @JvmStatic
    fun remove(uuid: UUID) {
        clocks.remove(uuid)
    }

    /** Drop every clock. Called on client disconnect. */
    @JvmStatic
    fun clear() {
        clocks.clear()
    }

    /**
     * Java-friendly frame-arrival hook. Called by both the sound and
     * display packet handlers on the client side to keep the clock
     * live as audio and video arrive.
     */
    @JvmStatic
    fun onIncoming(uuid: UUID, ptsMicros: Long) {
        get(uuid).onIncoming(Micros(ptsMicros))
    }
}
