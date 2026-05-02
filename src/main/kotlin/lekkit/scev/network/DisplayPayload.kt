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
 * Server → Client: framebuffer blit broadcast from a running machine.
 * `pixels` is the encoded H.264 NAL byte stream for the captured frame
 * (decoded shape: `width * height` BGRA pixels).
 *
 * [ptsMicros] is the capture-time PTS in the per-machine clock shared
 * with [SoundFramePayload]. The client's `MediaClock` keys the jitter
 * buffer by PTS so video presentations stay locked to audio playback.
 *
 * Stream end is signalled by [DisplayDisposePayload], not by an
 * in-band size-0 sentinel — see that class for the rationale.
 */
data class DisplayPayload(
    @get:JvmName("machineUuid") val machineUuid: UUID,
    @get:JvmName("ptsMicros")   val ptsMicros: Micros,
    @get:JvmName("width")       val width: Short,
    @get:JvmName("height")      val height: Short,
    @get:JvmName("pixels")      val pixels: ByteArray,
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<DisplayPayload> = TYPE

    companion object {
        @JvmField
        val TYPE: CustomPacketPayload.Type<DisplayPayload> = payloadType("display")

        @JvmField
        val STREAM_CODEC: StreamCodec<ByteBuf, DisplayPayload> = StreamCodec.of(::encode, ::decode)

        /**
         * Java-friendly factory taking a raw `Long` PTS. Kotlin callers
         * use the primary constructor directly with [Micros].
         */
        @JvmStatic
        fun create(
            machineUuid: UUID,
            ptsMicros: Long,
            width: Short,
            height: Short,
            pixels: ByteArray,
        ): DisplayPayload = DisplayPayload(machineUuid, Micros(ptsMicros), width, height, pixels)

        private fun encode(buf: ByteBuf, p: DisplayPayload) {
            buf.writeUuid(p.machineUuid)
            buf.writeLong(p.ptsMicros.value)
            buf.writeShort(p.width.toInt())
            buf.writeShort(p.height.toInt())
            buf.writeSizedBytes(p.pixels)
        }

        private fun decode(buf: ByteBuf): DisplayPayload = DisplayPayload(
            machineUuid = buf.readUuid(),
            ptsMicros   = Micros(buf.readLong()),
            width       = buf.readShort(),
            height      = buf.readShort(),
            pixels      = buf.readSizedBytes(),
        )
    }
}
