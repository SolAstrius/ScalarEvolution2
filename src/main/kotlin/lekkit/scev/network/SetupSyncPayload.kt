/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.network

import io.netty.buffer.ByteBuf
import java.util.UUID
import lekkit.scev.client.terminal.setup.SetupModel
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

/**
 * Server → Client: the persistent half of a terminal block's
 * [SetupModel] state, broadcast to every player viewing that block.
 * Triggered when:
 *  - A player commits a Setup-page edit on the server side, or
 *  - A player opens the terminal menu / ambient-subscribes (initial
 *    push so the screen reflects the BE's NBT state immediately).
 *
 * Targeting is identical to [SerialOutPayload]: addressed by machine
 * UUID, fanned out to viewers with a matching [TerminalMenu] open
 * plus ambient subscribers.
 *
 * View state (page, focus, cursor, answerback edit-in-progress) is
 * NOT carried — that's per-player UI state and lives only in each
 * client's open screen instance. Multiple players viewing the same
 * block can be on different Setup pages simultaneously.
 */
data class SetupSyncPayload(
    @get:JvmName("machineUuid") val machineUuid: UUID,
    @get:JvmName("state")       val state: SetupModel.PersistentState,
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<SetupSyncPayload> = TYPE

    companion object {
        @JvmField
        val TYPE: CustomPacketPayload.Type<SetupSyncPayload> = payloadType("setup_sync")

        @JvmField
        val STREAM_CODEC: StreamCodec<ByteBuf, SetupSyncPayload> =
            StreamCodec.of(::encode, ::decode)

        private fun encode(buf: ByteBuf, p: SetupSyncPayload) {
            buf.writeUuid(p.machineUuid)
            p.state.writeTo(buf)
        }

        private fun decode(buf: ByteBuf): SetupSyncPayload {
            val uuid = buf.readUuid()
            // readFrom returns null on wire-version mismatch — translate
            // to a PersistentState() default so the packet still
            // round-trips cleanly and the screen falls back to the
            // initial defaults rather than crashing.
            val state = SetupModel.PersistentState.readFrom(buf)
                ?: SetupModel.PersistentState()
            return SetupSyncPayload(uuid, state)
        }
    }
}
