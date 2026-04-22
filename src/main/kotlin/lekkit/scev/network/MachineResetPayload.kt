/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.network

import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

/**
 * Client → Server: click the power or reset button on a machine menu.
 * `reset=false` means "toggle power".
 */
data class MachineResetPayload(@get:JvmName("reset") val reset: Boolean) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<MachineResetPayload> = TYPE

    companion object {
        @JvmField
        val TYPE: CustomPacketPayload.Type<MachineResetPayload> = payloadType("machine_reset")

        @JvmField
        val STREAM_CODEC: StreamCodec<ByteBuf, MachineResetPayload> =
            StreamCodec.composite(
                ByteBufCodecs.BOOL, MachineResetPayload::reset,
                ::MachineResetPayload,
            )
    }
}
