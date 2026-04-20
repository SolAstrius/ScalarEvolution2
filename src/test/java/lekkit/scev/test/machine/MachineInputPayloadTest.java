/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.machine;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lekkit.rvvm.HIDKeyboard;
import lekkit.scev.network.MachineInputPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Encode / decode round-trip checks for {@link MachineInputPayload}. Every
 * {@link MachineInputPayload.Kind} must survive the wire unchanged or input
 * silently disappears between client and server.
 */
class MachineInputPayloadTest {

    private static MachineInputPayload roundtrip(MachineInputPayload in) {
        ByteBuf buf = Unpooled.buffer();
        MachineInputPayload.STREAM_CODEC.encode(buf, in);
        MachineInputPayload out = MachineInputPayload.STREAM_CODEC.decode(buf);
        assertFalse(buf.isReadable(), "unexpected trailing bytes on the wire: " + buf.readableBytes());
        return out;
    }

    @Test
    @DisplayName("KEY_PRESS round-trip preserves scancode")
    void keyPressRoundtrip() {
        MachineInputPayload p = MachineInputPayload.keyPress(HIDKeyboard.HID_KEY_A);
        MachineInputPayload after = roundtrip(p);
        assertEquals(MachineInputPayload.Kind.KEY_PRESS, after.kind());
        assertEquals(HIDKeyboard.HID_KEY_A, after.keyByte());
    }

    @Test
    @DisplayName("KEY_RELEASE round-trip preserves scancode")
    void keyReleaseRoundtrip() {
        MachineInputPayload after = roundtrip(MachineInputPayload.keyRelease(HIDKeyboard.HID_KEY_Z));
        assertEquals(MachineInputPayload.Kind.KEY_RELEASE, after.kind());
        assertEquals(HIDKeyboard.HID_KEY_Z, after.keyByte());
    }

    @Test
    @DisplayName("MOUSE_PRESS round-trip preserves button mask")
    void mousePressRoundtrip() {
        MachineInputPayload after = roundtrip(MachineInputPayload.mousePress((byte) 2));
        assertEquals(MachineInputPayload.Kind.MOUSE_PRESS, after.kind());
        assertEquals(2, after.keyByte());
    }

    @Test
    @DisplayName("MOUSE_RELEASE round-trip preserves button mask")
    void mouseReleaseRoundtrip() {
        MachineInputPayload after = roundtrip(MachineInputPayload.mouseRelease((byte) 4));
        assertEquals(MachineInputPayload.Kind.MOUSE_RELEASE, after.kind());
        assertEquals(4, after.keyByte());
    }

    @Test
    @DisplayName("MOUSE_SCROLL round-trip preserves delta sign")
    void mouseScrollRoundtrip() {
        MachineInputPayload up = roundtrip(MachineInputPayload.mouseScroll((byte) 1));
        assertEquals(1, up.keyByte());
        MachineInputPayload down = roundtrip(MachineInputPayload.mouseScroll((byte) -1));
        assertEquals(-1, down.keyByte());
    }

    @Test
    @DisplayName("MOUSE_MOVE round-trip preserves (dx, dy)")
    void mouseMoveRoundtrip() {
        MachineInputPayload after = roundtrip(MachineInputPayload.mouseMove((short) 100, (short) -42));
        assertEquals(MachineInputPayload.Kind.MOUSE_MOVE, after.kind());
        assertEquals(100, after.mouseX());
        assertEquals(-42, after.mouseY());
    }

    @Test
    @DisplayName("MOUSE_PLACE round-trip preserves (x, y)")
    void mousePlaceRoundtrip() {
        MachineInputPayload after = roundtrip(MachineInputPayload.mousePlace((short) 320, (short) 240));
        assertEquals(MachineInputPayload.Kind.MOUSE_PLACE, after.kind());
        assertEquals(320, after.mouseX());
        assertEquals(240, after.mouseY());
    }

    @Test
    @DisplayName("Decode clamps an out-of-range kind ordinal to 0 instead of ArrayIndexOutOfBounds")
    void decodeClampsBadKind() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(99); // invalid kind ordinal
        buf.writeByte(0);  // payload body
        MachineInputPayload after = MachineInputPayload.STREAM_CODEC.decode(buf);
        assertEquals(MachineInputPayload.Kind.values()[0], after.kind());
    }
}
