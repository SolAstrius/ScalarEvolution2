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
 * - **Audio**: [nextAudioPts] counts emitted samples within a session
 *   and derives PTS from the known 48 kHz rate. Drift-free within the
 *   session. The session's anchor is re-established to the current
 *   wall-clock offset whenever the gap between emissions exceeds
 *   the session-gap threshold (e.g. between two `aplay` invocations
 *   separated by a silent shell prompt), so a resumed audio stream
 *   lands at its real-time wall-clock moment rather than continuing
 *   from the previous session's sample counter.
 * - **Video**: [nowPts] reads `System.nanoTime()` at frame capture and
 *   subtracts the clock's origin. Wall-clock-bound because video
 *   capture happens whenever the tick thread runs; there's no
 *   underlying "sample rate" equivalent.
 *
 * They agree at the machine origin [originNs]: video PTS is always
 * `wall-µs since origin`, and audio PTS is `session_anchor_µs +
 * session_samples × 1e6 / rate`, where `session_anchor_µs` is itself
 * a wall-µs-since-origin value captured at session start. Concurrent
 * frames of the two streams therefore carry PTS values drawn from
 * the same axis.
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

    /**
     * Wall-clock offset from [originNs] (in µs) at which the CURRENT
     * audio stream session started. `-1` is the "no audio yet" sentinel.
     *
     * This is the anchor that pins audio PTS onto the same time axis as
     * video. Without it, the first audio emission after the clock has
     * been running (e.g. video has been producing frames for 30 s, then
     * `aplay music.wav` starts) would return PTS 0 — which on the client
     * side looks like a 30 s backward jump against the media clock's
     * video-driven anchor, tripping the [lekkit.scev.client.MediaClock]
     * re-anchor threshold (500 ms) and orphaning every buffered video
     * frame whose PTS is now "in the future" relative to the re-anchor.
     * The orphaned frames break the H.264 decoder's reference chain,
     * producing a sustained `dsError*` cascade that presents as "the
     * VM display froze when I pressed Ctrl+C on aplay" (the cascade
     * actually starts when aplay FIRST emits; Ctrl+C is only when the
     * player notices, because typing still works under the hood).
     *
     * **Session semantics.** A single anchor-for-life would handle one
     * aplay invocation, but a second aplay after Ctrl+C has the same
     * problem in reverse: during the silent gap, video keeps advancing
     * wall-clock PTS, and the resumed audio's sample counter picks up
     * from where it left off — stale, behind wall clock. A re-anchor
     * at session boundaries (gap > [AUDIO_SESSION_GAP_NS]) ensures
     * each new burst of audio lands at its actual wall-clock moment,
     * not at the end of the previous session's sample counter.
     *
     * Within a session, audio PTS still advances by sample count, so
     * the drift-free property against its own rate is preserved for
     * any run of contiguous emissions.
     */
    @Volatile
    private var audioAnchorMicros: Long = -1L

    /**
     * `System.nanoTime()` of the last [nextAudioPts] call. Used to
     * detect session boundaries — a long gap means the stream has
     * paused and any subsequent emission starts a new session that
     * must re-anchor to wall clock. `-1` before the first call.
     */
    @Volatile
    private var lastAudioEmitNs: Long = -1L

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
     * Audio PTS that shares a time axis with [nowPts]. Call once per
     * emitted frame, passing the number of samples in that frame
     * (typically [SoundStreamManager.FRAME_SAMPLES] = 960 for 20 ms
     * at 48 kHz).
     *
     * Returned PTS is the *start-of-frame* time: the first sample of
     * this frame will be audible at the returned PTS.
     *
     * The method distinguishes two cases:
     *
     * 1. **New session** — this is the first emission ever, or the gap
     *    since the last emission exceeds [AUDIO_SESSION_GAP_NS]. The
     *    anchor moves to the current wall-clock offset from origin
     *    and the sample counter resets to zero, so the first frame of
     *    the new session carries a PTS matching a concurrent video
     *    emission.
     * 2. **Same session** — the call follows a recent emission. The
     *    anchor stays put and the frame's PTS is the anchor plus the
     *    session's accumulated sample-count × 1e6 / rate. This keeps
     *    the stream drift-free against its own sample rate within a
     *    contiguous run.
     */
    fun nextAudioPts(samplesInFrame: Int): Micros {
        startIfUnstarted()
        val nowNs = System.nanoTime()
        val isNewSession = audioAnchorMicros < 0L
                || (lastAudioEmitNs >= 0L && nowNs - lastAudioEmitNs > AUDIO_SESSION_GAP_NS)
        if (isNewSession) {
            audioAnchorMicros = (nowNs - originNs) / 1_000L
            audioSamplesEmitted = 0L
        }
        val pts = Micros(audioAnchorMicros + audioSamplesEmitted * 1_000_000L / sampleRateHz)
        audioSamplesEmitted += samplesInFrame.toLong()
        lastAudioEmitNs = nowNs
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
        audioAnchorMicros = -1L
        lastAudioEmitNs = -1L
    }

    companion object {
        /** Standard audio rate for `SoundStreamManager`-fed clocks. */
        const val DEFAULT_SAMPLE_RATE_HZ: Int = 48_000

        /**
         * Inter-emission gap (ns) that closes one audio session and
         * starts a new one on next emission. 500 ms matches the
         * client-side [lekkit.scev.client.MediaClock] re-anchor
         * threshold: anything we allow past this would have tripped
         * the client's own re-anchor anyway, so the server-side fix
         * eliminates the mismatch before it reaches the wire.
         *
         * 500 ms is also comfortably above the worst realistic
         * inter-tick gap during active playback (server tick is 50 ms,
         * one empty tick during GC or load spike takes it to ~150 ms),
         * so no normal playback jitter spuriously triggers a new
         * session.
         */
        private const val AUDIO_SESSION_GAP_NS: Long = 500_000_000L
    }
}
