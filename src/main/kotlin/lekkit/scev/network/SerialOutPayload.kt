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
 * Server → Client: a chunk of bytes drained from a guest's kernel
 * console TX, addressed by the source machine's UUID. Sent each
 * server tick by [lekkit.scev.blockentity.TerminalBlockEntity]'s
 * registered console sink.
 *
 * Wire format: 16-byte UUID, 4-byte length, raw bytes. Receiving
 * clients filter on `machineUuid` against the bound machine of any
 * open VT100 screen and feed matching bytes into the embed
 * terminal via [lekkit.scev.client.terminal.MltermBackend.feed].
 *
 * Broadcast to every player in the level — clients drop packets
 * for UUIDs they have no open screen for. Bandwidth is bounded by
 * the kernel TX rate (~printk velocity, very low except during
 * boot) and the VT100 ticks at the server rate (20 Hz).
 */
data class SerialOutPayload(
    @get:JvmName("machineUuid") val machineUuid: UUID,
    @get:JvmName("bytes")       val bytes: ByteArray,
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<SerialOutPayload> = TYPE

    /* Generated equals/hashCode would compare ByteArray identity, not
     * contents — explicit overrides keep the data class semantics
     * sane if anyone adds tests later. */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SerialOutPayload) return false
        return machineUuid == other.machineUuid && bytes.contentEquals(other.bytes)
    }
    override fun hashCode(): Int = 31 * machineUuid.hashCode() + bytes.contentHashCode()

    companion object {
        @JvmField
        val TYPE: CustomPacketPayload.Type<SerialOutPayload> = payloadType("serial_out")

        @JvmField
        val STREAM_CODEC: StreamCodec<ByteBuf, SerialOutPayload> =
            StreamCodec.of(::encode, ::decode)

        private fun encode(buf: ByteBuf, p: SerialOutPayload) {
            buf.writeUuid(p.machineUuid)
            buf.writeSizedBytes(p.bytes)
        }

        private fun decode(buf: ByteBuf): SerialOutPayload {
            val uuid = buf.readUuid()
            val bytes = buf.readSizedBytes()
            return SerialOutPayload(uuid, bytes)
        }
    }
}
