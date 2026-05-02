/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.network

import io.netty.buffer.ByteBuf
import lekkit.scev.client.terminal.setup.SetupModel
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

/**
 * Client → Server: a terminal block's persistent Setup state, freshly
 * mutated by the player's keystroke and submitted for the server to
 * authoritatively apply.
 *
 * No UUID on the wire — the server resolves the target block from the
 * sending player's currently-open [TerminalMenu], the same trick
 * [SerialInPayload] uses. Trusting a client-supplied UUID here would
 * let any client overwrite Setup state on any terminal in the world by
 * UUID guess; menu-resolution caps the blast radius to "the block I'm
 * actively interacting with".
 *
 * Server's responsibilities on receipt:
 *  - Validate the player has a [TerminalMenu] open whose machineUuid
 *    matches a [TerminalBlockEntity] in their level.
 *  - Apply the new persistent state to that BE.
 *  - Broadcast a [SetupSyncPayload] to every viewer (including the
 *    sender — clients should NOT optimistically apply, they wait for
 *    the echo so multi-viewer rooms see consistent state).
 */
data class SetupEditPayload(
    @get:JvmName("state") val state: SetupModel.PersistentState,
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<SetupEditPayload> = TYPE

    companion object {
        @JvmField
        val TYPE: CustomPacketPayload.Type<SetupEditPayload> = payloadType("setup_edit")

        @JvmField
        val STREAM_CODEC: StreamCodec<ByteBuf, SetupEditPayload> =
            StreamCodec.of(::encode, ::decode)

        private fun encode(buf: ByteBuf, p: SetupEditPayload) {
            p.state.writeTo(buf)
        }

        private fun decode(buf: ByteBuf): SetupEditPayload {
            val state = SetupModel.PersistentState.readFrom(buf)
                ?: SetupModel.PersistentState()
            return SetupEditPayload(state)
        }
    }
}
