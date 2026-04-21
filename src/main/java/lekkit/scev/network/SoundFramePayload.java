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
 * Server -&gt; Client: one Opus-encoded 20 ms frame from a machine's
 * emulated HDA output.
 *
 * <p>Dispatched by {@link lekkit.scev.server.SoundStreamManager} on each
 * server tick to every player within the machine's audible radius. The
 * client buffers frames on an OpenAL streaming source keyed by
 * {@code machineUuid} so several machines in the same area don't share
 * buffers.
 *
 * <p>Payload sizing: Opus frame at 64 kbps / 20 ms = ~160 bytes. UUID +
 * length header adds 20 bytes. Frame rate is ~50 Hz (one Opus frame per
 * 20 ms of guest audio), so per-listener bandwidth is ~9 KB/s — 11×
 * smaller than the equivalent raw PCM stream would be.
 *
 * <p>The {@code pcm()} accessor name is historical; the payload is
 * Opus-compressed bytes that the client decodes before OpenAL upload.
 */
public record SoundFramePayload(UUID machineUuid, byte[] pcm)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SoundFramePayload> TYPE =
            new CustomPacketPayload.Type<>(ScalarEvolution.rl("sound_frame"));

    public static final StreamCodec<ByteBuf, SoundFramePayload> STREAM_CODEC =
            StreamCodec.of(SoundFramePayload::encode, SoundFramePayload::decode);

    private static void encode(ByteBuf buf, SoundFramePayload p) {
        buf.writeLong(p.machineUuid.getMostSignificantBits());
        buf.writeLong(p.machineUuid.getLeastSignificantBits());
        buf.writeInt(p.pcm.length);
        buf.writeBytes(p.pcm);
    }

    private static SoundFramePayload decode(ByteBuf buf) {
        long mb = buf.readLong();
        long lb = buf.readLong();
        int len = buf.readInt();
        byte[] pcm = new byte[Math.max(0, len)];
        buf.readBytes(pcm);
        return new SoundFramePayload(new UUID(mb, lb), pcm);
    }

    @Override public Type<SoundFramePayload> type() { return TYPE; }
}
