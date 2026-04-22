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
 * Client → Server: "I'm watching [machineUuid] and my H.264 decoder
 * can't make sense of the current stream — please force an IDR on
 * your next encode." Sent when [lekkit.scev.client.DisplayManager]'s
 * decode path returns `null` (SPS/PPS missing, lost reference frame,
 * late-joiner arriving mid-stream).
 *
 * Handled on the server by flipping the UUID's bit in
 * [lekkit.scev.server.VideoKeyframeRequests]; the next time
 * `ComputerCaseBlockEntity.broadcastFramebuffer` runs for that
 * machine, it consumes the flag and calls `encoder.forceIdr()` before
 * the encode. The client's next received frame will then be a fresh
 * IDR with SPS + PPS, and decoding resumes.
 *
 * The client rate-limits issuance to one request per UUID per second
 * so a genuinely broken stream (corrupt, desynced, etc.) doesn't
 * generate a pathological request storm.
 *
 * Security: no authorisation check on the server side — any connected
 * client can flag any UUID. Abuse cost is bounded (one forced IDR per
 * request, i.e. ~2× a normal frame's bytes), so DoS surface is
 * trivial. Revisit if we ever see abuse.
 */
data class KeyframeRequestPayload(
    @get:JvmName("machineUuid") val machineUuid: UUID,
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<KeyframeRequestPayload> = TYPE

    companion object {
        @JvmField
        val TYPE: CustomPacketPayload.Type<KeyframeRequestPayload> = payloadType("keyframe_request")

        @JvmField
        val STREAM_CODEC: StreamCodec<ByteBuf, KeyframeRequestPayload> =
            StreamCodec.of(::encode, ::decode)

        private fun encode(buf: ByteBuf, p: KeyframeRequestPayload) {
            buf.writeUuid(p.machineUuid)
        }

        private fun decode(buf: ByteBuf): KeyframeRequestPayload = KeyframeRequestPayload(
            machineUuid = buf.readUuid(),
        )
    }
}
