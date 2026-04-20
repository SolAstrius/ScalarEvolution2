/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.network;

import io.netty.buffer.ByteBuf;
import lekkit.scev.main.ScalarEvolution;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client -> Server: click the power or reset button on a machine menu.
 *
 * {@code reset=false} means "toggle power".
 */
public record MachineResetPayload(boolean reset) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<MachineResetPayload> TYPE =
            new CustomPacketPayload.Type<>(ScalarEvolution.rl("machine_reset"));

    public static final StreamCodec<ByteBuf, MachineResetPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, MachineResetPayload::reset,
                    MachineResetPayload::new);

    @Override public Type<MachineResetPayload> type() { return TYPE; }
}
