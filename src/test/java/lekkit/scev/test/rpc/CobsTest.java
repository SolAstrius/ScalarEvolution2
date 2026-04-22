/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.rpc;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import lekkit.scev.rpc.Cobs;
import org.junit.jupiter.api.Test;

/**
 * COBS encode/decode round-trip plus the tricky boundary cases: an input
 * of all zeros, an input without any zeros, an input exactly 254 bytes
 * long (the max a single code byte can carry), and one 255 bytes long
 * (forcing the code-byte split).
 */
final class CobsTest {
    @Test void roundTripHello() { roundTrip("hello world".getBytes()); }

    @Test void roundTripEmpty() { roundTrip(new byte[0]); }

    @Test void roundTripAllZeros() {
        byte[] in = new byte[16];
        roundTrip(in);
    }

    @Test void roundTripNoZeros() {
        byte[] in = new byte[32];
        Arrays.fill(in, (byte) 0x55);
        roundTrip(in);
    }

    @Test void roundTripExactly254NonZero() {
        byte[] in = new byte[254];
        Arrays.fill(in, (byte) 0xAA);
        roundTrip(in);
    }

    @Test void roundTripExactly255NonZero() {
        byte[] in = new byte[255];
        Arrays.fill(in, (byte) 0xAA);
        roundTrip(in);
    }

    @Test void roundTripMixedPattern() {
        byte[] in = new byte[300];
        for (int i = 0; i < in.length; i++) in[i] = (byte) (i % 7 == 0 ? 0 : i);
        roundTrip(in);
    }

    @Test void encodedEndsInDelimiter() {
        byte[] in = {1, 2, 3};
        byte[] out = new byte[Cobs.maxEncodedSize(in.length)];
        int n = Cobs.encode(in, 0, in.length, out, 0);
        assertEquals(0, out[n - 1], "frame must end with delimiter 0x00");
    }

    @Test void decodeRejectsStrayZero() {
        // A valid encoder never emits an interior 0x00 (that's the whole
        // point of COBS). Hand-craft a frame where the second code byte
        // is 0x00: 0x02 says "1 data byte then expect a code". Read the
        // 'a', arrive at position 2, find a 0x00 in the code slot, bail.
        byte[] bad = {0x02, (byte) 'a', 0x00, (byte) 'z'};
        byte[] out = new byte[8];
        int n = Cobs.decode(bad, 0, bad.length, out, 0);
        assertEquals(-1, n, "stray zero at a code position must yield -1");
    }

    private void roundTrip(byte[] in) {
        byte[] enc = new byte[Cobs.maxEncodedSize(in.length)];
        int encLen = Cobs.encode(in, 0, in.length, enc, 0);
        assertTrue(encLen > 0);
        assertEquals(0, enc[encLen - 1], "trailing delimiter");

        byte[] dec = new byte[in.length];
        int decLen = Cobs.decode(enc, 0, encLen - 1, dec, 0);   // strip delimiter
        assertEquals(in.length, decLen, "decoded length");
        byte[] got = Arrays.copyOf(dec, decLen);
        assertArrayEquals(in, got);
    }
}
