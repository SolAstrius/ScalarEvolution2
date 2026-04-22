/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.network

import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

/**
 * Client → Server: the player pressed "Write" on the flash programmer menu.
 * Zero-field payload — the target menu is inferred from the player's open
 * container; slot contents come from the BE.
 */
data object FlashProgrammerWritePayload : CustomPacketPayload {
    @JvmField
    val TYPE: CustomPacketPayload.Type<FlashProgrammerWritePayload> = payloadType("flash_programmer_write")

    @JvmField
    val STREAM_CODEC: StreamCodec<ByteBuf, FlashProgrammerWritePayload> = StreamCodec.unit(this)

    override fun type(): CustomPacketPayload.Type<FlashProgrammerWritePayload> = TYPE
}
