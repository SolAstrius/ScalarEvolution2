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
 * Client → Server: register / deregister this player as an "ambient
 * viewer" of a given machine's serial console — they want kernel TX
 * bytes even though they don't have a `TerminalMenu` open. Used by the
 * client-side [lekkit.scev.client.terminal.TerminalActiveHost] to keep
 * the in-world block face live after the GUI closes.
 *
 * Authorization: server checks the player has line-of-sight on a
 * VT100 block bound to this machine within their interaction range,
 * same way the menu-open path does it implicitly. Without that check
 * a malicious client could subscribe to any machine's UART output by
 * UUID and watch the boot logs from across the map.
 *
 * Server keeps an in-memory `Map<UUID, Set<ServerPlayer>>` of ambient
 * subscribers; on player disconnect the set entries get cleaned up
 * via the [PlayerEvent.PlayerLoggedOutEvent] hook in
 * [lekkit.scev.blockentity.TerminalBlockEntity].
 */
data class SerialAmbientSubscribePayload(
    @get:JvmName("machineUuid") val machineUuid: UUID,
    @get:JvmName("subscribe")   val subscribe: Boolean,
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<SerialAmbientSubscribePayload> = TYPE

    companion object {
        @JvmField
        val TYPE: CustomPacketPayload.Type<SerialAmbientSubscribePayload> =
            payloadType("serial_ambient_sub")

        @JvmField
        val STREAM_CODEC: StreamCodec<ByteBuf, SerialAmbientSubscribePayload> =
            StreamCodec.of(::encode, ::decode)

        private fun encode(buf: ByteBuf, p: SerialAmbientSubscribePayload) {
            buf.writeUuid(p.machineUuid)
            buf.writeBoolean(p.subscribe)
        }

        private fun decode(buf: ByteBuf): SerialAmbientSubscribePayload {
            val uuid = buf.readUuid()
            val sub = buf.readBoolean()
            return SerialAmbientSubscribePayload(uuid, sub)
        }
    }
}
