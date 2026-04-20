/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.machine;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.UUID;
import lekkit.scev.network.DisplayPayload;
import lekkit.scev.network.MachineResetPayload;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Round-trip every network payload. Without these, a typo in an encode /
 * decode pair silently breaks the corresponding feature across the wire.
 */
class PayloadRoundtripTest {

    @BeforeAll
    static void bootstrap() {
        Bootstrap.bootStrap();
        BuiltInRegistries.ITEM.getClass();
    }

    @Test
    @DisplayName("MachineResetPayload(reset=false) survives encode/decode")
    void machineResetPowerRoundtrip() {
        MachineResetPayload p = new MachineResetPayload(false);
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), null);
        MachineResetPayload.STREAM_CODEC.encode(buf, p);
        MachineResetPayload out = MachineResetPayload.STREAM_CODEC.decode(buf);
        assertFalse(out.reset());
        assertFalse(buf.isReadable(), "unread trailing bytes: " + buf.readableBytes());
    }

    @Test
    @DisplayName("MachineResetPayload(reset=true) survives encode/decode")
    void machineResetResetRoundtrip() {
        MachineResetPayload p = new MachineResetPayload(true);
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), null);
        MachineResetPayload.STREAM_CODEC.encode(buf, p);
        MachineResetPayload out = MachineResetPayload.STREAM_CODEC.decode(buf);
        assertTrue(out.reset());
    }

    @Test
    @DisplayName("DisplayPayload preserves uuid, size, and pixel bytes")
    void displayPayloadRoundtrip() {
        UUID uuid = UUID.randomUUID();
        short w = 640;
        short h = 480;
        byte[] pixels = new byte[w * h * 4];
        // Fill with a pattern so we can verify byte-exact copy.
        for (int i = 0; i < pixels.length; i++) pixels[i] = (byte) (i & 0xFF);
        DisplayPayload p = new DisplayPayload(uuid, w, h, pixels);

        ByteBuf buf = Unpooled.buffer();
        DisplayPayload.STREAM_CODEC.encode(buf, p);
        DisplayPayload out = DisplayPayload.STREAM_CODEC.decode(buf);

        assertEquals(uuid, out.machineUuid());
        assertEquals(w, out.width());
        assertEquals(h, out.height());
        assertArrayEquals(pixels, out.pixels());
    }

    @Test
    @DisplayName("DisplayPayload with a tiny framebuffer")
    void displayPayloadTiny() {
        DisplayPayload p = new DisplayPayload(UUID.randomUUID(), (short) 4, (short) 4,
                new byte[] {
                        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
                        17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32,
                        33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48,
                        49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, 62, 63, 64
                });
        ByteBuf buf = Unpooled.buffer();
        DisplayPayload.STREAM_CODEC.encode(buf, p);
        DisplayPayload out = DisplayPayload.STREAM_CODEC.decode(buf);
        assertEquals(p.pixels().length, out.pixels().length);
        assertArrayEquals(p.pixels(), out.pixels());
    }
}
