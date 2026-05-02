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
 * Client → Server: a chunk of bytes the player typed into a VT100
 * screen. The target machine is implicit — the server reads it from
 * the player's currently-open `TerminalMenu`, the same trick
 * [MachineInputPayload] uses to address the workstation HID stream.
 *
 * No UUID on the wire on purpose: trusting a client-supplied UUID
 * would let a malicious client write to any kernel UART by guessing
 * the ID. Resolving via `containerMenu` server-side means the only
 * machine you can target is one whose menu the server believes you've
 * opened (you had to right-click the block, you had to be in
 * interaction range, the menu's still open). Walk away → menu closes
 * → packets drop. Same model the workstation uses.
 *
 * Replies (DA / DSR / mouse-report) ride this same payload on the way
 * back from the screen to the kernel — they're conceptually identical
 * to keystrokes (terminal → guest UART RX), so the wire shape is
 * shared.
 */
data class SerialInPayload(
    @get:JvmName("bytes") val bytes: ByteArray,
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<SerialInPayload> = TYPE

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SerialInPayload) return false
        return bytes.contentEquals(other.bytes)
    }
    override fun hashCode(): Int = bytes.contentHashCode()

    companion object {
        @JvmField
        val TYPE: CustomPacketPayload.Type<SerialInPayload> = payloadType("serial_in")

        @JvmField
        val STREAM_CODEC: StreamCodec<ByteBuf, SerialInPayload> =
            StreamCodec.of(::encode, ::decode)

        private fun encode(buf: ByteBuf, p: SerialInPayload) {
            buf.writeSizedBytes(p.bytes)
        }

        private fun decode(buf: ByteBuf): SerialInPayload {
            return SerialInPayload(buf.readSizedBytes())
        }
    }
}
