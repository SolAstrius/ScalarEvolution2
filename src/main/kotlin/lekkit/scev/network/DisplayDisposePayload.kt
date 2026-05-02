/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.network

import io.netty.buffer.ByteBuf
import java.util.UUID
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

/**
 * Server → Client: explicit "stream's over" signal for a machine.
 *
 * Sent by [lekkit.scev.server.MachineDisplayStreamer.dispose] on
 * power-off / chunk unload (anything that tears down the per-machine
 * encoder). The client drops its cached `DisplayState` for the UUID and
 * any buffered frames, so the next render of the BlockEntity model and
 * the [lekkit.scev.client.screen.MachineScreen] viewport falls back to
 * "no display" — black.
 *
 * Replaces the previous `DisplayPayload(width=0, height=0, [])` sentinel:
 * a separate event reads cleaner at every protocol layer (no special-
 * casing zero dimensions in encode/decode/jitter buffer/keyframe code)
 * and stops mixing content with control on the same channel.
 */
data class DisplayDisposePayload(
    @get:JvmName("machineUuid") val machineUuid: UUID,
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<DisplayDisposePayload> = TYPE

    companion object {
        @JvmField
        val TYPE: CustomPacketPayload.Type<DisplayDisposePayload> = payloadType("display_dispose")

        @JvmField
        val STREAM_CODEC: StreamCodec<ByteBuf, DisplayDisposePayload> =
            StreamCodec.of(::encode, ::decode)

        private fun encode(buf: ByteBuf, p: DisplayDisposePayload) {
            buf.writeUuid(p.machineUuid)
        }

        private fun decode(buf: ByteBuf): DisplayDisposePayload =
            DisplayDisposePayload(buf.readUuid())
    }
}
