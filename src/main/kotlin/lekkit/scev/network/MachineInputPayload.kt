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
 * Client → Server: keyboard / mouse input for an open
 * [lekkit.scev.menu.MachineMenu].
 *
 * Wire format: one byte of [Kind], then either a single key/button byte
 * or two shorts for mouse coordinates.
 */
data class MachineInputPayload(
    @get:JvmName("kind")    val kind: Kind,
    @get:JvmName("keyByte") val keyByte: Byte,
    @get:JvmName("mouseX")  val mouseX: Short,
    @get:JvmName("mouseY")  val mouseY: Short,
) : CustomPacketPayload {

    enum class Kind { KEY_PRESS, KEY_RELEASE, MOUSE_MOVE, MOUSE_PLACE, MOUSE_SCROLL, MOUSE_PRESS, MOUSE_RELEASE }

    override fun type(): CustomPacketPayload.Type<MachineInputPayload> = TYPE

    companion object {
        @JvmField
        val TYPE: CustomPacketPayload.Type<MachineInputPayload> = payloadType("machine_input")

        @JvmField
        val STREAM_CODEC: StreamCodec<ByteBuf, MachineInputPayload> =
            StreamCodec.of(::encode, ::decode)

        @JvmStatic fun keyPress(key: Byte): MachineInputPayload     = MachineInputPayload(Kind.KEY_PRESS,     key, 0, 0)
        @JvmStatic fun keyRelease(key: Byte): MachineInputPayload   = MachineInputPayload(Kind.KEY_RELEASE,   key, 0, 0)
        @JvmStatic fun mousePress(btn: Byte): MachineInputPayload   = MachineInputPayload(Kind.MOUSE_PRESS,   btn, 0, 0)
        @JvmStatic fun mouseRelease(btn: Byte): MachineInputPayload = MachineInputPayload(Kind.MOUSE_RELEASE, btn, 0, 0)
        @JvmStatic fun mouseScroll(delta: Byte): MachineInputPayload = MachineInputPayload(Kind.MOUSE_SCROLL, delta, 0, 0)
        @JvmStatic fun mouseMove(x: Short, y: Short): MachineInputPayload  = MachineInputPayload(Kind.MOUSE_MOVE,  0, x, y)
        @JvmStatic fun mousePlace(x: Short, y: Short): MachineInputPayload = MachineInputPayload(Kind.MOUSE_PLACE, 0, x, y)

        private fun encode(buf: ByteBuf, p: MachineInputPayload) {
            buf.writeByte(p.kind.ordinal)
            when (p.kind) {
                Kind.KEY_PRESS, Kind.KEY_RELEASE,
                Kind.MOUSE_PRESS, Kind.MOUSE_RELEASE, Kind.MOUSE_SCROLL ->
                    buf.writeByte(p.keyByte.toInt())
                Kind.MOUSE_MOVE, Kind.MOUSE_PLACE -> {
                    buf.writeShort(p.mouseX.toInt())
                    buf.writeShort(p.mouseY.toInt())
                }
            }
        }

        private fun decode(buf: ByteBuf): MachineInputPayload {
            // Clamp unknown ordinals to KEY_PRESS so a malformed packet can't
            // throw AIOOBE and drop the connection mid-game.
            val kind = Kind.entries.getOrNull(buf.readByte().toInt() and 0xFF) ?: Kind.entries[0]
            return when (kind) {
                Kind.KEY_PRESS, Kind.KEY_RELEASE,
                Kind.MOUSE_PRESS, Kind.MOUSE_RELEASE, Kind.MOUSE_SCROLL ->
                    MachineInputPayload(kind, buf.readByte(), 0, 0)
                Kind.MOUSE_MOVE, Kind.MOUSE_PLACE ->
                    MachineInputPayload(kind, 0, buf.readShort(), buf.readShort())
            }
        }
    }
}
