/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.network;

import io.netty.buffer.ByteBuf;
import java.util.UUID;
import lekkit.scev.main.ScalarEvolution;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server -> Client: framebuffer blit broadcast from a running machine.
 *
 * <p>{@code pixels} is a raw ARGB byte stream ({@code width * height * 4} bytes).
 */
public record DisplayPayload(UUID machineUuid, short width, short height, byte[] pixels)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<DisplayPayload> TYPE =
            new CustomPacketPayload.Type<>(ScalarEvolution.rl("display"));

    public static final StreamCodec<ByteBuf, DisplayPayload> STREAM_CODEC =
            StreamCodec.of(DisplayPayload::encode, DisplayPayload::decode);

    private static void encode(ByteBuf buf, DisplayPayload p) {
        buf.writeLong(p.machineUuid.getMostSignificantBits());
        buf.writeLong(p.machineUuid.getLeastSignificantBits());
        buf.writeShort(p.width);
        buf.writeShort(p.height);
        buf.writeInt(p.pixels.length);
        buf.writeBytes(p.pixels);
    }

    private static DisplayPayload decode(ByteBuf buf) {
        long mb = buf.readLong();
        long lb = buf.readLong();
        short w = buf.readShort();
        short h = buf.readShort();
        int len = buf.readInt();
        byte[] px = new byte[Math.max(0, len)];
        buf.readBytes(px);
        return new DisplayPayload(new UUID(mb, lb), w, h, px);
    }

    @Override public Type<DisplayPayload> type() { return TYPE; }
}
