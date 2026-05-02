/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.network

import io.netty.buffer.ByteBuf
import java.util.UUID
import lekkit.scev.core.time.Micros
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

/**
 * Server → Client: one Opus-encoded 20 ms frame from a machine's emulated
 * HDA output, broadcast to every player within audible radius.
 *
 * Sizing: Opus frame at 128 kbps VBR / 20 ms = ~250 bytes + UUID + PTS +
 * length header. At ~50 Hz (one frame per 20 ms of guest audio),
 * per-listener bandwidth is ~16 KB/s — 6× smaller than raw 48 kHz mono
 * 16-bit PCM.
 *
 * [pcm] is a historical name: the payload carries Opus-compressed bytes
 * that the client decodes to PCM before OpenAL upload.
 *
 * [ptsMicros] is the start-of-frame PTS in the per-machine clock used
 * by `MediaClock` on the client side. See `MachineClock` for the
 * audio-side derivation and the shared timebase with [DisplayPayload].
 */
data class SoundFramePayload(
    @get:JvmName("machineUuid") val machineUuid: UUID,
    @get:JvmName("ptsMicros")   val ptsMicros: Micros,
    @get:JvmName("pcm")         val pcm: ByteArray,
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<SoundFramePayload> = TYPE

    companion object {
        @JvmField
        val TYPE: CustomPacketPayload.Type<SoundFramePayload> = payloadType("sound_frame")

        @JvmField
        val STREAM_CODEC: StreamCodec<ByteBuf, SoundFramePayload> = StreamCodec.of(::encode, ::decode)

        private fun encode(buf: ByteBuf, p: SoundFramePayload) {
            buf.writeUuid(p.machineUuid)
            buf.writeLong(p.ptsMicros.value)
            buf.writeSizedBytes(p.pcm)
        }

        private fun decode(buf: ByteBuf): SoundFramePayload = SoundFramePayload(
            machineUuid = buf.readUuid(),
            ptsMicros   = Micros(buf.readLong()),
            pcm         = buf.readSizedBytes(),
        )

        /**
         * Java-friendly factory taking a raw `Long` PTS. Kotlin callers
         * use the primary constructor directly with [Micros].
         */
        @JvmStatic
        fun create(machineUuid: UUID, ptsMicros: Long, pcm: ByteArray): SoundFramePayload =
            SoundFramePayload(machineUuid, Micros(ptsMicros), pcm)
    }
}
