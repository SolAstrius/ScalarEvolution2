/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.rpc;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import lekkit.scev.rpc.Cobs;
import lekkit.scev.rpc.FrameStream;
import org.junit.jupiter.api.Test;

/**
 * Exercises partial reads, multiple frames per chunk, oversize drops, and
 * corrupted-frame recovery on the {@link FrameStream} decoder.
 */
final class FrameStreamTest {
    @Test void singleFrameInOneChunk() {
        byte[] plain = "hello".getBytes();
        byte[] wire = cobs(plain);
        FrameStream s = new FrameStream(1024);
        List<byte[]> frames = s.feed(wire, 0, wire.length);
        assertEquals(1, frames.size());
        assertArrayEquals(plain, frames.get(0));
    }

    @Test void singleFrameSplitAcrossChunks() {
        byte[] plain = "hello world this is a longer message".getBytes();
        byte[] wire = cobs(plain);
        FrameStream s = new FrameStream(1024);
        // Feed byte-by-byte — absolute worst case for the accumulator.
        List<byte[]> all = new ArrayList<>();
        for (int i = 0; i < wire.length; i++) {
            all.addAll(s.feed(wire, i, 1));
        }
        assertEquals(1, all.size());
        assertArrayEquals(plain, all.get(0));
    }

    @Test void twoFramesInOneChunk() {
        byte[] a = "one".getBytes();
        byte[] b = "two".getBytes();
        byte[] wireA = cobs(a);
        byte[] wireB = cobs(b);
        byte[] combined = new byte[wireA.length + wireB.length];
        System.arraycopy(wireA, 0, combined, 0, wireA.length);
        System.arraycopy(wireB, 0, combined, wireA.length, wireB.length);

        FrameStream s = new FrameStream(1024);
        List<byte[]> frames = s.feed(combined, 0, combined.length);
        assertEquals(2, frames.size());
        assertArrayEquals(a, frames.get(0));
        assertArrayEquals(b, frames.get(1));
    }

    @Test void recoversAfterCorruptedFrame() {
        // Send a partial/garbled "frame" followed by a good one. Decoder
        // should drop the bad one and emit the good one cleanly.
        FrameStream s = new FrameStream(1024);
        // Bad frame: code byte promising 5 bytes, only 3 follow, then delimiter.
        byte[] bad = {0x06, 'a', 'b', 'c', 0};
        s.feed(bad, 0, bad.length);          // emits nothing, bumps droppedFrames
        byte[] good = cobs("ok".getBytes());
        List<byte[]> frames = s.feed(good, 0, good.length);
        assertEquals(1, frames.size());
        assertArrayEquals("ok".getBytes(), frames.get(0));
        assertTrue(s.droppedFrames() >= 1);
    }

    @Test void dropsOversizeFrames() {
        FrameStream s = new FrameStream(16);
        // A frame whose encoded length exceeds the cap.
        byte[] huge = new byte[32];
        for (int i = 0; i < huge.length; i++) huge[i] = (byte) (i + 1);  // no zeros inside
        byte[] wire = cobs(huge);
        List<byte[]> frames = s.feed(wire, 0, wire.length);
        assertEquals(0, frames.size());
        assertTrue(s.droppedFrames() >= 1);

        // Still recovers: a small frame after the giant one parses fine.
        byte[] small = cobs("ok".getBytes());
        frames = s.feed(small, 0, small.length);
        assertEquals(1, frames.size());
    }

    @Test void recoversValidFrameAfterCookedModeEchoTrash() {
        // Simulates the guest TTY's brief cooked-mode window on open:
        // the host's outbound bytes get echoed back to it with ECHOCTL
        // substitution (every 0x00 → "^@" = 0x5e 0x40, every 0x02 →
        // "^B" = 0x5e 0x42, …). The original 0x00 frame delimiters
        // disappear, so the host accumulates [echo-trash]+[real frame]
        // as one COBS block before seeing the first real 0x00. Without
        // recovery the whole thing would be dropped, taking the real
        // frame with it.
        byte[] trash = new byte[] {
            // Caret-encoded representation of ^Y (^@ ^B …): just bytes
            // that don't contain a 0x00 and don't COBS-decode cleanly
            // when prefixed onto a real frame.
            0x5e, 0x59, (byte) 0x93, 0x5e, 0x42, (byte) 0xaa,
            'p','e','r','i','p','h','e','r','a','l',
            0x5e, 0x40, 0x5e, 0x58,
        };
        // Real RPC frame: msgpack array(4) [TAG_REQ=0, id=1, "ping", []]
        byte[] real = new byte[] {
            (byte) 0x94, 0x00, 0x01, (byte) 0xa4, 'p','i','n','g', (byte) 0x90,
        };
        byte[] realCobs = cobs(real);

        // Wire: trash bytes (no 0x00s) immediately followed by real
        // COBS frame ending in its own 0x00 delimiter.
        byte[] wire = new byte[trash.length + realCobs.length];
        System.arraycopy(trash, 0, wire, 0, trash.length);
        System.arraycopy(realCobs, 0, wire, trash.length, realCobs.length);

        FrameStream s = new FrameStream(1024, (plain, len) -> {
            if (len < 2) return false;
            int hdr = plain[0] & 0xff;
            if (hdr != 0x93 && hdr != 0x94) return false;
            int tag = plain[1] & 0xff;
            return tag <= 2;
        });
        List<byte[]> frames = s.feed(wire, 0, wire.length);
        assertEquals(1, frames.size(), "real frame should be recovered from inside trash");
        assertArrayEquals(real, frames.get(0));
        assertEquals(1L, s.recoveredFrames());
    }

    @Test void recoveryDisabledByDefaultStillDrops() {
        // Same shape as above, but no validator → original behavior:
        // failed COBS decode is dropped, frame lost.
        byte[] trash = new byte[] { 0x5e, 0x59, (byte) 0x93, 0x5e, 0x42 };
        byte[] real = new byte[] { (byte) 0x94, 0x00, 0x01, (byte) 0xa4, 'p','i','n','g', (byte) 0x90 };
        byte[] realCobs = cobs(real);
        byte[] wire = new byte[trash.length + realCobs.length];
        System.arraycopy(trash, 0, wire, 0, trash.length);
        System.arraycopy(realCobs, 0, wire, trash.length, realCobs.length);

        FrameStream s = new FrameStream(1024);
        List<byte[]> frames = s.feed(wire, 0, wire.length);
        assertEquals(0, frames.size());
        assertTrue(s.droppedFrames() >= 1);
    }

    private static byte[] cobs(byte[] plain) {
        byte[] out = new byte[Cobs.maxEncodedSize(plain.length)];
        int n = Cobs.encode(plain, 0, plain.length, out, 0);
        byte[] ret = new byte[n];
        System.arraycopy(out, 0, ret, 0, n);
        return ret;
    }
}
