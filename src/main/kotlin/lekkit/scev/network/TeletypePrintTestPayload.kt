/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.network

import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

/** Client → Server: player clicked the "Print Test Page" button on a
 *  Teletype GUI. Server resolves the BE via `containerMenu` (same
 *  authority pattern as the other input payloads). */
object TeletypePrintTestPayload : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<TeletypePrintTestPayload> = TYPE

    @JvmField
    val TYPE: CustomPacketPayload.Type<TeletypePrintTestPayload> = payloadType("teletype_print_test")

    @JvmField
    val STREAM_CODEC: StreamCodec<ByteBuf, TeletypePrintTestPayload> =
        StreamCodec.unit(TeletypePrintTestPayload)
}
