/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.items

import com.mojang.serialization.Codec
import io.netty.buffer.ByteBuf
import java.util.Base64
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec

/**
 * Arbitrary byte payload carried by a [FlashItem] stack for player-
 * authored (or mod-authored) custom firmware content.
 *
 * When a flash chip has this blob attached, the backend loads the bytes
 * directly at the reset vector via a temp-file round-trip, bypassing
 * [lekkit.scev.machine.firmware.FirmwareRegistry] entirely. This is how
 * players will deliver firmware they wrote themselves (assembled on an
 * NVMe-backed Linux guest, flashed into a chip via the future Programmer
 * block, carried in inventory or chests like any other item).
 *
 * Persistence is via the `FIRMWARE_BYTES` data component — ItemStack data
 * components save with the world automatically. No separate registry, no
 * server-side file storage: the bytes travel with the item.
 *
 * ## Size cap
 *
 * [MAX_SIZE] = 1 MiB. Minecraft's per-packet NBT ceiling is 2 MB
 * compressed; a 1 MiB cap leaves comfortable room for the rest of the
 * inventory packet. Realistic custom programs are orders of magnitude
 * smaller (blinky is 64 bytes) — the cap exists to catch accidents, not
 * to accommodate a workload.
 *
 * ## Serialization
 *
 * Persistence codec uses Base64 over a string, giving a ~33% size
 * overhead but producing a JSON form that's legible in recipe files and
 * diffable in PRs. Network codec is raw bytes (length-prefixed), so
 * over-the-wire cost is just the actual bytes plus a VarInt.
 *
 * ## Equality
 *
 * Custom `equals` / `hashCode` / `toString` — a Kotlin data class with a
 * `ByteArray` field would otherwise compare by reference, which is wrong
 * for component-based equality (two chips with the same flashed content
 * should be considered equal for stacking purposes).
 */
class FirmwareBlob(@get:JvmName("bytes") val bytes: ByteArray) {
    init {
        require(bytes.size <= MAX_SIZE) { "firmware blob too large: ${bytes.size} > $MAX_SIZE" }
    }

    /** Convenience: is this blob empty (nothing to load)? */
    fun isEmpty(): Boolean = bytes.isEmpty()

    /** Defensive copy — callers shouldn't mutate the backing array. */
    fun copyBytes(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is FirmwareBlob && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "FirmwareBlob[${bytes.size} bytes]"

    companion object {
        /** Hard cap on stored blob length. Enforced on construction. */
        const val MAX_SIZE: Int = 1 shl 20

        @JvmField
        val CODEC: Codec<FirmwareBlob> = Codec.STRING.xmap(
            { s -> FirmwareBlob(Base64.getDecoder().decode(s)) },
            { b: FirmwareBlob -> Base64.getEncoder().encodeToString(b.bytes) },
        )

        @JvmField
        val STREAM_CODEC: StreamCodec<ByteBuf, FirmwareBlob> =
            ByteBufCodecs.byteArray(MAX_SIZE).map(::FirmwareBlob, FirmwareBlob::bytes)
    }
}
