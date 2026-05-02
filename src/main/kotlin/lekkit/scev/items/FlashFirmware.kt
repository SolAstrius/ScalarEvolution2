/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.items

import com.mojang.serialization.Codec
import io.netty.buffer.ByteBuf
import java.util.Locale
import lekkit.scev.machine.firmware.FirmwareRegistry
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.StringRepresentable

/**
 * Closed enumeration of built-in firmware kinds a [FlashItem] can carry.
 * Each entry maps (or explicitly does not map) to an id in
 * [FirmwareRegistry].
 *
 * This is the "typed layer" sitting on top of [FirmwareRegistry]'s
 * free-form ResourceLocation keys. Stamping a chip with [BLINKY] is
 * equivalent to pointing it at [FirmwareRegistry.BLINKY], but the enum
 * form gives us:
 *
 * - Compile-time exhaustiveness — new chip variants force every `when`
 *   to be updated.
 * - Clean serialization — [StringRepresentable] yields short lowercase
 *   keys ("blinky", "opensbi") that read well in recipe JSON.
 * - Robust migration — adding a new enum value never breaks old saves,
 *   and removing one can be handled with an alias in the codec.
 *
 * For **third-party** firmwares registered by other mods, use the
 * `FIRMWARE_ID_OVERRIDE` data component instead — it carries an arbitrary
 * [ResourceLocation]. Precedence is documented on
 * [lekkit.scev.main.ScevDataComponents].
 *
 * [BLANK] resolves to `null` and means "don't load any firmware" — CPU
 * hits zero-initialized RAM on reset and traps. Useful as a test shape
 * or a reset target; not the same as "no component set", which falls
 * back to [LINUX] (see parser).
 */
enum class FlashFirmware(private val id: ResourceLocation?) : StringRepresentable {
    /** Explicitly no firmware. CPU traps on first fetch. */
    BLANK(null),

    /**
     * OpenSBI + Linux kernel. The historical default for any flash chip
     * and what the parser falls back to when no data component is set.
     */
    LINUX(FirmwareRegistry.LINUX),

    /**
     * OpenSBI only (`fw_jump.bin`). BYO kernel (MachineSpec.KernelSpec or
     * a bootable disk).
     */
    OPENSBI(FirmwareRegistry.OPENSBI_ONLY),

    /** OpenSBI + U-Boot. Power-user firmware with a boot shell. */
    OPEN_FW(FirmwareRegistry.OPEN_FIRMWARE),

    /**
     * Bare-metal RV32IM demo. 64 bytes, toggles FRONT pin at 0.5 Hz.
     * Introduced with the MCU tier; the "hello world" firmware.
     */
    BLINKY(FirmwareRegistry.BLINKY);

    /**
     * Registry id this kind resolves to, or `null` for [BLANK]. Callers
     * use [FirmwareRegistry.get] against the returned id to fetch the
     * live [lekkit.scev.machine.firmware.ScevFirmware].
     */
    fun id(): ResourceLocation? = id

    override fun getSerializedName(): String = name.lowercase(Locale.ROOT)

    companion object {
        @JvmField
        val CODEC: Codec<FlashFirmware> = StringRepresentable.fromEnum(::values)

        /**
         * Network codec — VarInt ordinal. Enum evolution caveat:
         * reordering existing values between releases would misroute bits
         * over the wire even though persistence (via CODEC) would still
         * be correct, so the ordering of declarations above is
         * load-bearing. Adding new entries at the end is safe.
         */
        @JvmField
        val STREAM_CODEC: StreamCodec<ByteBuf, FlashFirmware> =
            ByteBufCodecs.idMapper({ i -> values()[i] }, Enum<FlashFirmware>::ordinal)
    }
}
