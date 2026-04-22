/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.network

import io.netty.buffer.ByteBuf
import java.util.UUID
import lekkit.scev.main.ScalarEvolution
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

// UUIDUtil.STREAM_CODEC isn't used here because the payloads serialise the
// UUID inline with other fields, not as a top-level codec stream. These
// helpers reproduce the primitive 16-byte layout so wire formats remain
// byte-identical across payloads.

internal fun ByteBuf.writeUuid(u: UUID) {
    writeLong(u.mostSignificantBits)
    writeLong(u.leastSignificantBits)
}

internal fun ByteBuf.readUuid(): UUID = UUID(readLong(), readLong())

internal fun ByteBuf.writeSizedBytes(bytes: ByteArray) {
    writeInt(bytes.size)
    writeBytes(bytes)
}

// Clamps a negative length to zero so a malformed packet can't trigger a
// huge allocation or NegativeArraySizeException.
internal fun ByteBuf.readSizedBytes(): ByteArray {
    val len = readInt().coerceAtLeast(0)
    val out = ByteArray(len)
    readBytes(out)
    return out
}

// Factory for the `CustomPacketPayload.Type<T>` + `scev:<path>` pair every
// payload declares. Collapses three lines of ceremony per payload into one.
internal fun <T : CustomPacketPayload> payloadType(path: String): CustomPacketPayload.Type<T> =
    CustomPacketPayload.Type(ScalarEvolution.rl(path))
