/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.rpc;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lekkit.scev.rpc.MsgPack;
import lekkit.scev.core.rpc.MsgValue;
import org.junit.jupiter.api.Test;

final class MsgPackTest {
    @Test void roundTripPrimitives() {
        assertRoundTrip(MsgValue.NIL);
        assertRoundTrip(MsgValue.TRUE);
        assertRoundTrip(MsgValue.FALSE);
        assertRoundTrip(MsgValue.of(0L));
        assertRoundTrip(MsgValue.of(127L));         // positive fixint boundary
        assertRoundTrip(MsgValue.of(128L));         // uint8
        assertRoundTrip(MsgValue.of(-32L));         // negative fixint boundary
        assertRoundTrip(MsgValue.of(-33L));         // int8
        assertRoundTrip(MsgValue.of(65535L));       // uint16
        assertRoundTrip(MsgValue.of(65536L));       // uint32
        assertRoundTrip(MsgValue.of(Long.MAX_VALUE));
        assertRoundTrip(MsgValue.of(Long.MIN_VALUE));
        assertRoundTrip(MsgValue.of(3.14159));
        assertRoundTrip(MsgValue.of("hello"));
        assertRoundTrip(MsgValue.of(""));
    }

    @Test void roundTripString32Bytes() {
        // Exactly at fixstr/str8 boundary (fixstr holds up to 31).
        assertRoundTrip(MsgValue.of("a".repeat(31)));
        assertRoundTrip(MsgValue.of("a".repeat(32)));
        assertRoundTrip(MsgValue.of("a".repeat(300)));
    }

    @Test void roundTripBytes() {
        byte[] small = {1, 2, 3, 4};
        MsgValue v = MsgValue.of(small);
        MsgValue dec = MsgPack.decode(MsgPack.encode(v));
        assertTrue(dec.isBytes());
        assertArrayEquals(small, dec.asBytes());
    }

    @Test void roundTripArray() {
        MsgValue v = MsgValue.ofArray(List.of(
                MsgValue.of(1L),
                MsgValue.of("two"),
                MsgValue.TRUE));
        MsgValue dec = MsgPack.decode(MsgPack.encode(v));
        assertEquals(3, dec.asArray().size());
        assertEquals(1L, dec.asArray().get(0).asInt());
        assertEquals("two", dec.asArray().get(1).asString());
        assertEquals(Boolean.TRUE, dec.asArray().get(2).asBool());
    }

    @Test void roundTripNestedMap() {
        Map<MsgValue, MsgValue> inner = new LinkedHashMap<>();
        inner.put(MsgValue.of("n"), MsgValue.of(42L));
        Map<MsgValue, MsgValue> outer = new LinkedHashMap<>();
        outer.put(MsgValue.of("inner"), MsgValue.ofMap(inner));
        outer.put(MsgValue.of("ok"), MsgValue.TRUE);

        MsgValue v = MsgValue.ofMap(outer);
        MsgValue dec = MsgPack.decode(MsgPack.encode(v));
        assertTrue(dec.isMap());
        assertEquals(2, dec.asMap().size());
    }

    @Test void decodesInteropBytes() {
        // Hand-encoded "positive fixint 5" = 0x05
        assertEquals(5L, MsgPack.decode(new byte[] {0x05}).asInt());
        // uint16 256 = 0xCD 0x01 0x00
        assertEquals(256L, MsgPack.decode(new byte[] {(byte) 0xCD, 0x01, 0x00}).asInt());
        // fixstr "hi" = 0xA2 'h' 'i'
        assertEquals("hi", MsgPack.decode(new byte[] {(byte) 0xA2, 'h', 'i'}).asString());
    }

    private void assertRoundTrip(MsgValue v) {
        byte[] enc = MsgPack.encode(v);
        MsgValue dec = MsgPack.decode(enc);
        assertEquals(v.getKind(), dec.getKind());
        switch (v.getKind()) {
            case INT -> assertEquals(v.asInt(), dec.asInt());
            case DOUBLE -> assertEquals((double) v.raw(), (double) dec.raw(), 1e-12);
            case STRING -> assertEquals(v.asString(), dec.asString());
            case BOOL -> assertEquals(v.asBool(), dec.asBool());
            default -> { /* NIL etc. — kind equality suffices */ }
        }
    }
}
