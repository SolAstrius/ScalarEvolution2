/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.machine;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;
import lekkit.scev.server.SoundStreamManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exercises the downsample + framing pipeline without touching Minecraft
 * or RVVM. The sink is called directly with synthetic 192 kHz PCM and we
 * pull frames out via {@link SoundStreamManager#pollFrame()}.
 */
class SoundStreamManagerTest {

    private final UUID uuid = UUID.randomUUID();

    @AfterEach
    void tearDown() {
        SoundStreamManager.unregister(uuid);
    }

    /* -------------------- downsample4to1 pure function -------------------- */

    @Test
    @DisplayName("downsample4to1 averages four consecutive 16-bit LE samples")
    void downsampleAverages() {
        // Input: four int16 LE samples [100, 200, 300, 400] → average = 250.
        byte[] in = new byte[8];
        putLE16(in, 0, 100);
        putLE16(in, 2, 200);
        putLE16(in, 4, 300);
        putLE16(in, 6, 400);
        byte[] out = SoundStreamManager.downsample4to1(in, 0, 8);
        assertEquals(2, out.length);
        assertEquals(250, readLE16(out, 0));
    }

    @Test
    @DisplayName("downsample4to1 preserves signedness (negatives average correctly)")
    void downsampleSigned() {
        // Four negatives averaged ⇒ still negative.
        byte[] in = new byte[8];
        putLE16(in, 0, -1000);
        putLE16(in, 2, -2000);
        putLE16(in, 4, -3000);
        putLE16(in, 6, -4000);
        byte[] out = SoundStreamManager.downsample4to1(in, 0, 8);
        assertEquals(-2500, readLE16(out, 0));
    }

    @Test
    @DisplayName("downsample4to1 handles multi-group input (32 bytes → 8 bytes)")
    void downsampleMultiGroup() {
        byte[] in = new byte[32];
        // Four groups of four samples. Group N has all samples == N * 1000.
        for (int g = 0; g < 4; g++) {
            for (int i = 0; i < 4; i++) {
                putLE16(in, g * 8 + i * 2, g * 1000);
            }
        }
        byte[] out = SoundStreamManager.downsample4to1(in, 0, 32);
        assertEquals(8, out.length);
        for (int g = 0; g < 4; g++) {
            assertEquals(g * 1000, readLE16(out, g * 2),
                    "group " + g + " average should equal constant input");
        }
    }

    @Test
    @DisplayName("downsample4to1 rejects non-multiple-of-8 lengths")
    void downsampleRejectsMisaligned() {
        byte[] in = new byte[10];
        assertThrows(IllegalArgumentException.class,
                () -> SoundStreamManager.downsample4to1(in, 0, 10));
    }

    /* -------------------- end-to-end via onAudio + pollFrame -------------------- */

    @Test
    @DisplayName("onAudio with exactly one frame's worth of input produces one frame")
    void oneFrameIn_oneFrameOut() {
        SoundStreamManager mgr = SoundStreamManager.create(uuid);
        // No downsample — input and output sizes are the same.
        byte[] input = new byte[SoundStreamManager.FRAME_BYTES];
        for (int i = 0; i < input.length / 2; i++) {
            putLE16(input, i * 2, i & 0x7FFF);
        }
        mgr.onAudio(input);
        assertEquals(1, mgr.pendingFrameCount(),
                "one frame's worth of input should yield exactly one frame");
        byte[] frame = mgr.pollFrame();
        assertNotNull(frame);
        assertEquals(SoundStreamManager.FRAME_BYTES, frame.length);
        assertEquals(0, mgr.pendingFrameCount(), "pollFrame should consume the frame");
    }

    @Test
    @DisplayName("onAudio with partial frame buffers until the next call completes it")
    void partialFramesBuffer() {
        SoundStreamManager mgr = SoundStreamManager.create(uuid);
        byte[] half = new byte[SoundStreamManager.FRAME_BYTES / 2];
        for (int i = 0; i < half.length; i++) half[i] = (byte) i;

        mgr.onAudio(half);
        assertEquals(0, mgr.pendingFrameCount(),
                "half a frame's worth of input should not produce a frame yet");
        mgr.onAudio(half);
        assertEquals(1, mgr.pendingFrameCount(),
                "completing the frame via a second call should emit exactly one frame");
    }

    @Test
    @DisplayName("Sub-frame chunks accumulate across many small calls")
    void smallChunksAccumulate() {
        SoundStreamManager mgr = SoundStreamManager.create(uuid);
        // Feed data in small 9-byte chunks. None alone is enough to cross
        // a frame boundary, but cumulative input eventually does.
        byte[] chunk = new byte[9];
        for (int i = 0; i < 9; i++) chunk[i] = (byte) i;
        int chunksNeeded = (SoundStreamManager.FRAME_BYTES / 9) + 2;
        for (int i = 0; i < chunksNeeded; i++) mgr.onAudio(chunk);
        assertTrue(mgr.pendingFrameCount() >= 1,
                "chunks of arbitrary size should accumulate until frame size is reached");
    }

    @Test
    @DisplayName("Queue overflow drops oldest frames (bounded)")
    void queueOverflowDropsOldest() {
        SoundStreamManager mgr = SoundStreamManager.create(uuid);
        // Feed enough input to generate more than MAX_QUEUED_FRAMES frames.
        int framesToGenerate = SoundStreamManager.MAX_QUEUED_FRAMES + 10;
        byte[] bulk = new byte[SoundStreamManager.FRAME_BYTES * framesToGenerate];
        mgr.onAudio(bulk);
        assertEquals(SoundStreamManager.MAX_QUEUED_FRAMES, mgr.pendingFrameCount(),
                "queue must be capped at MAX_QUEUED_FRAMES regardless of input size");
        assertEquals(10, mgr.droppedFrames(),
                "the 10 frames that overflowed the cap must be counted as dropped");
    }

    @Test
    @DisplayName("Null/empty input is a no-op (doesn't NPE or produce frames)")
    void emptyInputNoOp() {
        SoundStreamManager mgr = SoundStreamManager.create(uuid);
        mgr.onAudio(null);
        mgr.onAudio(new byte[0]);
        assertEquals(0, mgr.pendingFrameCount());
        assertEquals(0, mgr.totalPcmBytesIn());
    }

    @Test
    @DisplayName("liveManagerCount reflects registry state")
    void registryLifecycle() {
        int before = SoundStreamManager.liveManagerCount();
        SoundStreamManager mgr = SoundStreamManager.create(uuid);
        assertEquals(before + 1, SoundStreamManager.liveManagerCount());
        assertSame(mgr, SoundStreamManager.get(uuid));
        SoundStreamManager.unregister(uuid);
        assertEquals(before, SoundStreamManager.liveManagerCount());
        assertNull(SoundStreamManager.get(uuid));
    }

    /* -------------------- helpers -------------------- */

private static void putLE16(byte[] buf, int off, int value) {
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
        bb.position(off).putShort((short) value);
    }

    private static int readLE16(byte[] buf, int off) {
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
        return bb.position(off).getShort();
    }
}
