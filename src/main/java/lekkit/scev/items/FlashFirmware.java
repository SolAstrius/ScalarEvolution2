/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.items;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import java.util.Locale;
import lekkit.scev.machine.firmware.FirmwareRegistry;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringRepresentable;
import org.jetbrains.annotations.Nullable;

/**
 * Closed enumeration of built-in firmware kinds a {@link FlashItem} can
 * carry. Each entry maps (or explicitly does not map) to an id in
 * {@link FirmwareRegistry}.
 *
 * <p>This is the "typed layer" sitting on top of {@link FirmwareRegistry}'s
 * free-form ResourceLocation keys. Stamping a chip with {@code BLINKY} is
 * equivalent to pointing it at {@link FirmwareRegistry#BLINKY}, but the enum
 * form gives us:
 *
 * <ul>
 *   <li>Compile-time exhaustiveness — new chip variants force every
 *       switch() to be updated.</li>
 *   <li>Clean serialization — {@link StringRepresentable} yields short
 *       lowercase keys ("blinky", "opensbi") that read well in recipe JSON.</li>
 *   <li>Robust migration — adding a new enum value never breaks old saves,
 *       and removing one can be handled with an alias in the codec.</li>
 * </ul>
 *
 * <p>For <b>third-party</b> firmwares registered by other mods, use the
 * {@code FIRMWARE_ID_OVERRIDE} data component instead — it carries an
 * arbitrary {@link ResourceLocation}. Precedence is documented on
 * {@link lekkit.scev.main.ScevDataComponents}.
 *
 * <p>{@link #BLANK} resolves to {@code null} and means "don't load any
 * firmware" — CPU hits zero-initialized RAM on reset and traps. Useful as
 * a test shape or a reset target; not the same as "no component set",
 * which falls back to {@link #LINUX} (see parser).
 */
public enum FlashFirmware implements StringRepresentable {
    /** Explicitly no firmware. CPU traps on first fetch. */
    BLANK(null),

    /**
     * OpenSBI + Linux kernel. The historical default for any flash chip
     * and what the parser falls back to when no data component is set.
     */
    LINUX(FirmwareRegistry.LINUX),

    /**
     * OpenSBI only ({@code fw_jump.bin}). BYO kernel (MachineSpec.KernelSpec
     * or a bootable disk).
     */
    OPENSBI(FirmwareRegistry.OPENSBI_ONLY),

    /** OpenSBI + U-Boot. Power-user firmware with a boot shell. */
    OPEN_FW(FirmwareRegistry.OPEN_FIRMWARE),

    /**
     * Bare-metal RV32IM demo. 64 bytes, toggles FRONT pin at 0.5 Hz.
     * Introduced with the MCU tier; the "hello world" firmware.
     */
    BLINKY(FirmwareRegistry.BLINKY);

    public static final Codec<FlashFirmware> CODEC = StringRepresentable.fromEnum(FlashFirmware::values);

    /**
     * Network codec — VarInt ordinal. Enum evolution caveat: reordering
     * existing values between releases would misroute bits over the wire
     * even though persistence (via CODEC) would still be correct, so the
     * ordering of declarations above is load-bearing. Adding new entries
     * at the end is safe.
     */
    public static final StreamCodec<ByteBuf, FlashFirmware> STREAM_CODEC =
            ByteBufCodecs.idMapper(i -> values()[i], Enum::ordinal);

    private final @Nullable ResourceLocation id;

    FlashFirmware(@Nullable ResourceLocation id) { this.id = id; }

    /**
     * Registry id this kind resolves to, or {@code null} for {@link #BLANK}.
     * Callers use {@link FirmwareRegistry#get} against the returned id to
     * fetch the live {@link lekkit.scev.machine.firmware.ScevFirmware}.
     */
    public @Nullable ResourceLocation id() { return id; }

    @Override
    public String getSerializedName() { return name().toLowerCase(Locale.ROOT); }
}
