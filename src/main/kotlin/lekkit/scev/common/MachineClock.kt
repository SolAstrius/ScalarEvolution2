/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.common

/**
 * Per-machine media clock for A/V sync. One instance per running
 * machine, owned by `MachineState`. Assigns presentation timestamps
 * (PTS, in [Micros]) to outgoing audio and video frames so the client
 * can render them against a common media clock.
 *
 * Two concurrent PTS sources, both rooted at the same origin:
 *
 * - **Audio**: [nextAudioPts] counts emitted samples and derives PTS
 *   from the known 48 kHz rate. Drift-free by construction — the
 *   stream's own sample count IS the time axis, so host clock skew
 *   cannot desync it from itself.
 * - **Video**: [nowPts] reads `System.nanoTime()` at frame capture and
 *   subtracts the clock's origin. Wall-clock-bound because video
 *   capture happens whenever the tick thread runs; there's no
 *   underlying "sample rate" equivalent.
 *
 * They agree at the common origin [originNs]. Audio at PTS `N × 20000`
 * µs and video at PTS `(nanoTime − origin) / 1000` are commensurable
 * on the same timeline.
 *
 * **Lifecycle.** The origin is set lazily on the first frame emission
 * of either stream via [startIfUnstarted]. [reset] wipes the origin
 * and sample counter; the next emitted frame re-establishes the
 * clock. The client's `MediaClock` detects the backward PTS jump
 * caused by a reset and re-anchors itself.
 *
 * **Threading.** Audio and video emit paths both run on the server
 * tick thread (the HDA worker→channel handoff is consumed on tick,
 * the framebuffer broadcast is on tick). [reset] runs from the same
 * thread (machine pause/unpause). [originNs] is `@Volatile` so the
 * rare reader on a non-tick thread sees a consistent snapshot;
 * [audioSamplesEmitted] is not shared and needs no volatile.
 */
class MachineClock(private val sampleRateHz: Int) {

    /**
     * Monotonic clock reading (from `System.nanoTime()`) at which this
     * clock was established. PTS values are relative to this point.
     * `Long.MIN_VALUE` is the sentinel for "not yet started". Volatile
     * so reads from the [nowPts] caller see the most recent write.
     */
    @Volatile
    private var originNs: Long = Long.MIN_VALUE

    /** Samples passed through [nextAudioPts]. Only touched on the emit thread. */
    private var audioSamplesEmitted: Long = 0L

    /** `true` after the first frame has anchored the origin. */
    val isStarted: Boolean get() = originNs != Long.MIN_VALUE

    /**
     * Anchor the origin to the current wall clock if it wasn't already.
     * Called from both emit paths; the first call wins.
     */
    private fun startIfUnstarted() {
        if (originNs == Long.MIN_VALUE) {
            originNs = System.nanoTime()
        }
    }

    /**
     * Audio PTS derived from the cumulative sample count. Call once
     * per emitted frame, passing the number of samples in that frame
     * (typically [SoundStreamManager.FRAME_SAMPLES] = 960 for 20 ms at
     * 48 kHz).
     *
     * Returned PTS is the *start-of-frame* time: the first sample of
     * this frame will be audible at the returned PTS.
     */
    fun nextAudioPts(samplesInFrame: Int): Micros {
        startIfUnstarted()
        val pts = Micros(audioSamplesEmitted * 1_000_000L / sampleRateHz)
        audioSamplesEmitted += samplesInFrame.toLong()
        return pts
    }

    /**
     * Video PTS read from the wall clock. Represents "now, relative to
     * this machine's clock origin." Use at frame-capture time.
     */
    fun nowPts(): Micros {
        startIfUnstarted()
        return Nanos(System.nanoTime() - originNs).toMicros()
    }

    /**
     * Java-friendly companion to [nowPts] — returns the raw `Long`
     * microsecond value instead of the [Micros] value class. Kotlin
     * `@JvmInline value class` return types are mangled with a hash
     * suffix and aren't practically callable from Java; this is the
     * escape hatch for callers like `ComputerCaseBlockEntity` that
     * stay Java. Prefer [nowPts] from Kotlin.
     */
    fun nowPtsMicrosLong(): Long = nowPts().value

    /**
     * Reset the clock. Next emitted frame establishes a new origin.
     * Called on machine pause, unpause, power cycle, or any other
     * "the stream's timeline restarts" event. The client's MediaClock
     * sees the backward PTS jump and re-anchors to match.
     */
    fun reset() {
        originNs = Long.MIN_VALUE
        audioSamplesEmitted = 0L
    }

    companion object {
        /** Standard audio rate for `SoundStreamManager`-fed clocks. */
        const val DEFAULT_SAMPLE_RATE_HZ: Int = 48_000
    }
}
