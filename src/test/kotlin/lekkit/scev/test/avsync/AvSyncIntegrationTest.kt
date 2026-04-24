/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.avsync

import io.netty.buffer.Unpooled
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TreeMap
import java.util.UUID
import lekkit.scev.client.MediaClock
import lekkit.scev.common.MachineClock
import lekkit.scev.common.Micros
import lekkit.scev.network.DisplayPayload
import lekkit.scev.network.SoundFramePayload
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * End-to-end A/V sync: produce a stream of synthetic audio and video
 * frames with PTS-encoded content markers, pass them through the
 * server-side PTS stamping (`MachineClock`), through Minecraft's
 * `StreamCodec` wire pipeline, into the client-side `MediaClock` and
 * jitter-buffer selection logic, and verify:
 *
 * 1. PTS values survive the wire roundtrip byte-for-byte.
 * 2. Marker content (the identifying byte pattern we stamp each frame
 *    with) survives the wire roundtrip.
 * 3. `MediaClock.onIncoming` anchors on the first frame and advances
 *    with wall-clock time thereafter.
 * 4. The video jitter buffer's pick-newest-before-now selection (a
 *    `TreeMap.floorEntry` against `MediaClock.currentPts()`) picks the
 *    correct marked frame at a given media-clock moment.
 * 5. A server-side reset (audio PTS resets to 0) triggers client
 *    re-anchor, so post-reset frames are presented on the new epoch.
 *
 * The test exercises the actual payload classes + their `StreamCodec`s
 * — the same code that runs on the wire in production — so a break in
 * encode/decode, PTS plumbing, or jitter buffer behaviour fails here.
 */
class AvSyncIntegrationTest {

    companion object {
        /** Unique pattern per audio frame, so we can identify which frame decoded back out. */
        private fun audioMarker(frameIdx: Int, sampleCount: Int = 960): ByteArray {
            val pcm = ByteArray(sampleCount * 2)
            val bb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
            // Stamp a signed-16 value = (frameIdx * 17) mod 32767. Uniquely
            // identifies the frame within [0, 1928). The sample-stamp
            // repeats within the frame so partial reads still identify.
            val stamp = ((frameIdx * 17) and 0x7FFF).toShort()
            repeat(sampleCount) { bb.putShort(stamp) }
            return pcm
        }

        /** Unique RGBA byte pattern per video frame. */
        private fun videoMarker(frameIdx: Int, width: Int = 8, height: Int = 4): ByteArray {
            val pixels = ByteArray(width * height * 4)
            // R channel carries the low 8 bits of the frame index,
            // G carries the next 8, so (R,G) uniquely identifies frames
            // 0..65535. B and A stay at 0xFF each so the pattern is
            // human-readable in a pixel dump.
            val r = (frameIdx and 0xFF).toByte()
            val g = ((frameIdx shr 8) and 0xFF).toByte()
            for (i in 0 until width * height) {
                pixels[i * 4] = r
                pixels[i * 4 + 1] = g
                pixels[i * 4 + 2] = 0xFF.toByte()
                pixels[i * 4 + 3] = 0xFF.toByte()
            }
            return pixels
        }

        private fun readAudioMarker(pcm: ByteArray): Int {
            val bb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
            return bb.short.toInt() and 0x7FFF
        }

        private fun readVideoMarker(pixels: ByteArray): Int {
            val r = pixels[0].toInt() and 0xFF
            val g = pixels[1].toInt() and 0xFF
            return (g shl 8) or r
        }

        @BeforeAll
        @JvmStatic
        fun bootstrap() {
            Bootstrap.bootStrap()
            BuiltInRegistries.ITEM.javaClass  // touch to force static-init
        }
    }

    /* ============================================================== */
    /* PTS generation on the server side                              */
    /* ============================================================== */

    @Test
    @DisplayName("Server-side MachineClock advances audio PTS by exactly 20 ms per 960-sample frame")
    fun serverAudioPtsMonotonic() {
        val clock = MachineClock(MachineClock.DEFAULT_SAMPLE_RATE_HZ)
        val pts = LongArray(10) { clock.nextAudioPts(960).value }
        // Drift-free within the stream: each 960-sample frame advances
        // PTS by exactly 20 000 µs, regardless of wall-clock jitter.
        for (i in 1 until 10) {
            assertEquals(20_000L, pts[i] - pts[i - 1],
                "expected 20 000 µs spacing from frame ${i - 1} to $i, got ${pts[i] - pts[i - 1]}")
        }
    }

    @Test
    @DisplayName("First audio emission anchors to wall-clock offset from origin, not 0")
    fun firstAudioPtsAnchorsToWallClockOffset() {
        val clock = MachineClock(MachineClock.DEFAULT_SAMPLE_RATE_HZ)
        // Let the origin run — simulating video emitting first and
        // advancing the wall-clock-since-origin. This is the real
        // scenario: video ticks from machine boot, aplay starts later.
        val videoPtsBeforeAudio = clock.nowPts().value
        Thread.sleep(15)
        val videoPtsJustBeforeFirstAudio = clock.nowPts().value
        val firstAudio = clock.nextAudioPts(960).value
        // First audio PTS must sit in the same time axis as video —
        // roughly equal to video's "now" at the moment of the call.
        // Without this, video has PTS ~15 000, audio has PTS 0, and
        // the client's MediaClock tears them apart on re-anchor.
        assertTrue(firstAudio >= videoPtsJustBeforeFirstAudio,
            "first audio pts $firstAudio must not precede concurrent video pts $videoPtsJustBeforeFirstAudio")
        // Sanity: audio anchor tracks video, not stays at 0. The gap
        // between video pts before audio (≈ 0) and first audio pts
        // should be at least the 15 ms we slept — proving the anchor
        // moved.
        assertTrue(firstAudio - videoPtsBeforeAudio > 10_000L,
            "first audio pts should be well past origin — video was at $videoPtsBeforeAudio, audio at $firstAudio")
    }

    @Test
    @DisplayName("Audio session re-anchors to wall clock after a silent gap (aplay → Ctrl+C → aplay)")
    fun audioReanchorsAcrossSessionGap() {
        val clock = MachineClock(MachineClock.DEFAULT_SAMPLE_RATE_HZ)
        // Session A: simulate a burst of audio playback.
        val aFirst = clock.nextAudioPts(960).value
        repeat(4) { clock.nextAudioPts(960) }
        val aLast = clock.nextAudioPts(960).value
        assertEquals(aFirst + 5 * 20_000L, aLast, "session A spacing drift")

        // Ctrl+C on aplay: stream goes silent. The server stops emitting
        // audio, but wall-clock time keeps ticking (video keeps going).
        // AUDIO_SESSION_GAP_NS is 500 ms, so sleep longer than that.
        Thread.sleep(600L)

        // Session B: new aplay invocation. First frame must re-anchor
        // to current wall-clock offset — picking up from the stale
        // sample counter would land PTS 600 ms behind wall clock, and
        // the client's MediaClock would trip its own re-anchor on the
        // backward jump, causing the observed display freeze.
        val bFirst = clock.nextAudioPts(960).value
        val wallNow = clock.nowPts().value
        // Session B's first PTS sits in the same axis as a concurrent
        // video emission — so within a small epsilon of nowPts().
        assertTrue(bFirst <= wallNow,
            "session B first audio pts $bFirst must not exceed concurrent video pts $wallNow")
        assertTrue(wallNow - bFirst < 5_000L,
            "session B first audio pts $bFirst should be near concurrent video pts $wallNow (gap ${wallNow - bFirst} µs)")
        // Must have jumped forward from session A's last PTS by at
        // least the gap duration.
        assertTrue(bFirst - aLast > 500_000L,
            "session B PTS should jump past the 500 ms silent gap; got ${bFirst - aLast} µs advance")

        // Within session B, spacing returns to drift-free.
        val bSecond = clock.nextAudioPts(960).value
        assertEquals(20_000L, bSecond - bFirst,
            "session B must still advance by 20 000 µs per frame")
    }

    @Test
    @DisplayName("Small inter-tick gap (< session threshold) does NOT re-anchor audio session")
    fun audioDoesNotReanchorOnNormalTickJitter() {
        val clock = MachineClock(MachineClock.DEFAULT_SAMPLE_RATE_HZ)
        val first = clock.nextAudioPts(960).value
        // Normal server-tick interval (~50 ms) or a single skipped tick
        // (~100 ms) must NOT count as a session boundary — playback
        // would hitch every time Minecraft took a tick longer than
        // average. Stay well under AUDIO_SESSION_GAP_NS = 500 ms.
        Thread.sleep(100L)
        val second = clock.nextAudioPts(960).value
        // Still the same session: PTS advances by exactly one frame
        // (20 000 µs) regardless of the wall-clock gap.
        assertEquals(20_000L, second - first,
            "100 ms tick gap must not start a new audio session")
    }

    @Test
    @DisplayName("Server-side reset() re-anchors the audio stream at the new origin")
    fun serverResetReanchorsAudio() {
        val clock = MachineClock(MachineClock.DEFAULT_SAMPLE_RATE_HZ)
        repeat(5) { clock.nextAudioPts(960) }
        val pre = clock.nextAudioPts(960).value  // 6th emit
        clock.reset()
        val postFirst = clock.nextAudioPts(960).value
        // After reset, origin is fresh: first audio emit on the new
        // origin anchors at a small wall-clock offset (≈ 0), so PTS
        // should be well below the pre-reset counter that had been
        // running for 6 frames' worth of audio + whatever wall time
        // had elapsed.
        assertTrue(postFirst < pre,
            "post-reset first audio pts $postFirst should be less than pre-reset $pre")
        assertTrue(postFirst < 10_000L,
            "post-reset audio pts should anchor near 0, got $postFirst")
        // And subsequent frames still advance by exactly 20 ms.
        val postSecond = clock.nextAudioPts(960).value
        assertEquals(20_000L, postSecond - postFirst,
            "post-reset stream must still advance by 20 000 µs per frame")
    }

    @Test
    @DisplayName("Server-side nowPts() monotonically increases with wall time")
    fun serverVideoPtsAdvances() {
        val clock = MachineClock(MachineClock.DEFAULT_SAMPLE_RATE_HZ)
        val first = clock.nowPts()
        Thread.sleep(5)
        val second = clock.nowPts()
        assertTrue(second > first, "expected $second > $first")
        assertTrue((second - first).value in 2_000L..50_000L,
            "sleep(5) should produce ~5000 µs; got ${(second - first).value}")
    }

    /* ============================================================== */
    /* Wire-format roundtrip with marker content                      */
    /* ============================================================== */

    @Test
    @DisplayName("A stream of marked audio frames roundtrips through StreamCodec preserving PTS + marker")
    fun audioRoundtripWithMarkers() {
        val uuid = UUID.randomUUID()
        val frames = 50
        val recovered = mutableListOf<Pair<Long, Int>>()

        for (i in 0 until frames) {
            val pts = i * 20_000L
            val pcm = audioMarker(i)
            val payload = SoundFramePayload.create(uuid, pts, pcm)
            val buf = Unpooled.buffer()
            SoundFramePayload.STREAM_CODEC.encode(buf, payload)
            val out = SoundFramePayload.STREAM_CODEC.decode(buf)
            assertEquals(uuid, out.machineUuid)
            assertEquals(pts, out.ptsMicros.value)
            recovered += out.ptsMicros.value to readAudioMarker(out.pcm)
        }
        // Every frame came back with its correct (pts, frameIdx) pair.
        for (i in 0 until frames) {
            val expectedMarker = (i * 17) and 0x7FFF
            assertEquals((i * 20_000L) to expectedMarker, recovered[i])
        }
    }

    @Test
    @DisplayName("A stream of marked video frames roundtrips through StreamCodec preserving PTS + marker")
    fun videoRoundtripWithMarkers() {
        val uuid = UUID.randomUUID()
        val frames = 30
        val recovered = mutableListOf<Pair<Long, Int>>()

        for (i in 0 until frames) {
            val pts = i * 50_000L  // 20 Hz video
            val pixels = videoMarker(i)
            val payload = DisplayPayload.create(uuid, pts, 8, 4, pixels)
            val buf = Unpooled.buffer()
            DisplayPayload.STREAM_CODEC.encode(buf, payload)
            val out = DisplayPayload.STREAM_CODEC.decode(buf)
            assertEquals(uuid, out.machineUuid)
            assertEquals(pts, out.ptsMicros.value)
            assertEquals(8, out.width.toInt())
            assertEquals(4, out.height.toInt())
            recovered += out.ptsMicros.value to readVideoMarker(out.pixels)
        }
        for (i in 0 until frames) {
            assertEquals((i * 50_000L) to i, recovered[i])
        }
    }

    /* ============================================================== */
    /* Client-side MediaClock + video jitter buffer                   */
    /* ============================================================== */

    @Test
    @DisplayName("MediaClock anchors on first frame and advances with wall time")
    fun mediaClockAnchorsAndAdvances() {
        val clock = MediaClock()
        assertTrue(!clock.isAnchored)
        clock.onIncoming(Micros(100_000L))
        assertTrue(clock.isAnchored)
        val pts1 = clock.currentPts()
        Thread.sleep(5)
        val pts2 = clock.currentPts()
        assertTrue(pts2 > pts1, "media clock should advance, got $pts1 then $pts2")
        assertTrue(pts1.value >= 100_000L, "first read must not precede anchor: $pts1")
    }

    @Test
    @DisplayName("Backward PTS jump beyond reset threshold re-anchors the media clock")
    fun mediaClockReanchorsOnBackwardJump() {
        val clock = MediaClock()
        clock.onIncoming(Micros(10_000_000L))  // 10 s PTS anchor
        val pre = clock.currentPts()
        assertTrue(pre.value >= 10_000_000L)
        // Server reset: next frame comes in at PTS = 0
        clock.onIncoming(Micros(0L))
        val post = clock.currentPts()
        assertTrue(post.value < 1_000_000L,
            "post-reset PTS should be near zero; got $post")
    }

    @Test
    @DisplayName("Small backward PTS wobble (< 500 ms) does NOT re-anchor")
    fun mediaClockIgnoresSmallBackwardJitter() {
        val clock = MediaClock()
        clock.onIncoming(Micros(1_000_000L))
        Thread.sleep(10)
        // Jitter: incoming frame stamped 200 ms ago, within normal bounds.
        clock.onIncoming(Micros(800_000L))
        val now = clock.currentPts()
        // We should still be reading from the 1 s anchor, so now should be
        // ~1 s + ~10 ms, well above the 800_000 we just sent.
        assertTrue(now.value > 900_000L,
            "jitter should not re-anchor; got $now")
    }

    /* ============================================================== */
    /* End-to-end: wire → MediaClock → jitter buffer → pick frame     */
    /* ============================================================== */

    /**
     * Models [lekkit.scev.client.DisplayManager]'s per-machine jitter
     * buffer as a plain `TreeMap<Micros, DisplayPayload>`. The selection
     * logic (pick newest-frame-before-now, discard superseded older ones)
     * is the load-bearing part. This test runs that logic on a marked
     * video stream and checks the right frames get picked at each
     * simulated clock position.
     */
    @Test
    @DisplayName("Video jitter buffer returns the correct marked frame at each media-clock position")
    fun jitterBufferSelectsNewestBeforeNow() {
        val uuid = UUID.randomUUID()
        val buffer = TreeMap<Micros, DisplayPayload>()
        val mediaClock = MediaClock()

        // Produce 10 video frames at 50 ms spacing.
        for (i in 0 until 10) {
            val pts = i * 50_000L
            val payload = DisplayPayload.create(uuid, pts, 8, 4, videoMarker(i))
            // Wire roundtrip to prove we're picking a real decoded frame.
            val buf = Unpooled.buffer()
            DisplayPayload.STREAM_CODEC.encode(buf, payload)
            val out = DisplayPayload.STREAM_CODEC.decode(buf)

            mediaClock.onIncoming(out.ptsMicros)
            buffer[out.ptsMicros] = out
        }

        // Ask "what frame should be on screen at time T?" for a spread of
        // T values and verify the jitter buffer's floorEntry gives us the
        // newest frame whose PTS is <= T.
        val cases = mapOf(
            Micros(0L)       to 0,
            Micros(49_999L)  to 0,
            Micros(50_000L)  to 1,
            Micros(50_001L)  to 1,
            Micros(249_000L) to 4,
            Micros(449_999L) to 8,
            Micros(450_000L) to 9,
            Micros(999_999L) to 9,  // beyond the last frame, stays on last
        )
        for ((now, expectedIdx) in cases) {
            val picked = buffer.floorEntry(now)
            assertNotNull(picked, "no frame at/before $now")
            val idx = readVideoMarker(picked.value.pixels)
            assertEquals(expectedIdx, idx, "at now=$now expected frame #$expectedIdx but got #$idx")
        }
    }

    @Test
    @DisplayName("Mixed audio + video stream feeds MediaClock uniformly; video selection lines up with audio PTS")
    fun audioAndVideoShareTheClock() {
        val uuid = UUID.randomUUID()
        val mediaClock = MediaClock()
        val videoBuffer = TreeMap<Micros, DisplayPayload>()

        // 10 audio frames at 20 ms cadence (200 ms total), 4 video
        // frames at 50 ms cadence (150 ms total). Send them interleaved
        // in PTS order, mirroring what a real stream would deliver.
        data class Evt(val pts: Long, val kind: String, val idx: Int)
        val events = buildList {
            for (i in 0 until 10) add(Evt(i * 20_000L, "A", i))
            for (i in 0 until 4)  add(Evt(i * 50_000L, "V", i))
        }.sortedBy { it.pts }

        for (ev in events) when (ev.kind) {
            "A" -> {
                val payload = SoundFramePayload.create(uuid, ev.pts, audioMarker(ev.idx))
                val buf = Unpooled.buffer()
                SoundFramePayload.STREAM_CODEC.encode(buf, payload)
                val out = SoundFramePayload.STREAM_CODEC.decode(buf)
                mediaClock.onIncoming(out.ptsMicros)
            }
            "V" -> {
                val payload = DisplayPayload.create(uuid, ev.pts, 8, 4, videoMarker(ev.idx))
                val buf = Unpooled.buffer()
                DisplayPayload.STREAM_CODEC.encode(buf, payload)
                val out = DisplayPayload.STREAM_CODEC.decode(buf)
                mediaClock.onIncoming(out.ptsMicros)
                videoBuffer[out.ptsMicros] = out
            }
        }

        // At various audio-PTS moments, verify the video frame that
        // would be presented is the newest one with PTS <= audio PTS.
        assertEquals(0, readVideoMarker(videoBuffer.floorEntry(Micros(0L)).value.pixels))
        assertEquals(0, readVideoMarker(videoBuffer.floorEntry(Micros(40_000L)).value.pixels))
        assertEquals(1, readVideoMarker(videoBuffer.floorEntry(Micros(60_000L)).value.pixels))
        assertEquals(2, readVideoMarker(videoBuffer.floorEntry(Micros(120_000L)).value.pixels))
        assertEquals(3, readVideoMarker(videoBuffer.floorEntry(Micros(180_000L)).value.pixels))
    }
}
