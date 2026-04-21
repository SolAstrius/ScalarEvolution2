/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.items;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Arbitrary byte payload carried by a {@link FlashItem} stack for
 * player-authored (or mod-authored) custom firmware content.
 *
 * <p>When a flash chip has this blob attached, the backend loads the bytes
 * directly at the reset vector via a temp-file round-trip, bypassing
 * {@link lekkit.scev.machine.firmware.FirmwareRegistry} entirely. This is
 * how players will deliver firmware they wrote themselves (assembled on an
 * NVMe-backed Linux guest, flashed into a chip via the future Programmer
 * block, carried in inventory or chests like any other item).
 *
 * <p>Persistence is via the {@code FIRMWARE_BYTES} data component — ItemStack
 * data components save with the world automatically. No separate registry,
 * no server-side file storage: the bytes travel with the item.
 *
 * <h2>Size cap</h2>
 *
 * <p>{@link #MAX_SIZE} = 1 MiB. Minecraft's per-packet NBT ceiling is 2 MB
 * compressed; a 1 MiB cap leaves comfortable room for the rest of the
 * inventory packet. Realistic custom programs are orders of magnitude
 * smaller (blinky is 64 bytes) — the cap exists to catch accidents, not
 * to accommodate a workload.
 *
 * <h2>Serialization</h2>
 *
 * <p>Persistence codec uses Base64 over a string, giving a ~33% size
 * overhead but producing a JSON form that's legible in recipe files and
 * diffable in PRs. Network codec is raw bytes (length-prefixed), so
 * over-the-wire cost is just the actual bytes plus a VarInt.
 *
 * <h2>Equality</h2>
 *
 * <p>Overrides {@code equals} / {@code hashCode} / {@code toString} —
 * records with {@code byte[]} fields would otherwise compare by reference,
 * which is wrong for component-based equality (two chips with the same
 * flashed content should be considered equal for stacking purposes).
 */
public record FirmwareBlob(byte[] bytes) {
    /** Hard cap on stored blob length. Enforced on construction. */
    public static final int MAX_SIZE = 1 << 20;

    public static final Codec<FirmwareBlob> CODEC = Codec.STRING.xmap(
            s -> new FirmwareBlob(Base64.getDecoder().decode(s)),
            b -> Base64.getEncoder().encodeToString(b.bytes));

    public static final StreamCodec<ByteBuf, FirmwareBlob> STREAM_CODEC =
            ByteBufCodecs.byteArray(MAX_SIZE).map(FirmwareBlob::new, FirmwareBlob::bytes);

    public FirmwareBlob {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length > MAX_SIZE) {
            throw new IllegalArgumentException(
                    "firmware blob too large: " + bytes.length + " > " + MAX_SIZE);
        }
    }

    /** Convenience: is this blob empty (nothing to load)? */
    public boolean isEmpty() { return bytes.length == 0; }

    /** Defensive copy — callers shouldn't mutate the backing array. */
    public byte[] copyBytes() { return Arrays.copyOf(bytes, bytes.length); }

    @Override
    public boolean equals(Object o) {
        return o instanceof FirmwareBlob other && Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode() { return Arrays.hashCode(bytes); }

    @Override
    public String toString() { return "FirmwareBlob[" + bytes.length + " bytes]"; }
}
