/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.compat.jade

import lekkit.scev.blockentity.McuBoardBlockEntity
import lekkit.scev.blocks.DirectionalBlock
import lekkit.scev.items.FlashItem
import lekkit.scev.items.SocItem
import lekkit.scev.machine.GpioPinMap
import net.minecraft.ChatFormatting
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import snownee.jade.api.BlockAccessor
import snownee.jade.api.IBlockComponentProvider
import snownee.jade.api.IServerDataProvider
import snownee.jade.api.ITooltip
import snownee.jade.api.config.IPluginConfig

/**
 * Jade provider for [McuBoardBlockEntity] — microcontroller boards
 * carrying just an SoC and a flash chip.
 *
 * The MCU HUD is the biggest single win of the Jade integration: all
 * of the SoC tier / firmware kind / GPIO state has lived only on item
 * tooltips and the installation menu. Having it rendered on
 * block-peek means players can finally tell a running Blinky demo
 * from a blank Linux board at a glance.
 */
class McuBoardProvider private constructor() :
    IBlockComponentProvider, IServerDataProvider<BlockAccessor> {

    override fun getUid(): ResourceLocation = ScevJadeIds.MCU_BOARD

    /* ---------------- Server side ---------------- */

    override fun appendServerData(data: CompoundTag, acc: BlockAccessor) {
        val be = acc.blockEntity as? McuBoardBlockEntity ?: return

        data.putBoolean("powered", be.isPowered())
        data.putByte("facing", facing(be).ordinal.toByte())
        data.putInt("gpio_out", be.outRedstoneSignals)

        (be.getItem(McuBoardBlockEntity.SLOT_SOC).item as? SocItem)?.let { s ->
            data.putInt("soc_tier", s.tier)
            data.putString("soc_isa", s.isa)
            data.putInt("soc_harts", s.hartCount)
            data.putInt("soc_ram_kib", s.embeddedRamKib)
        }

        val flash = be.getItem(McuBoardBlockEntity.SLOT_FLASH)
        if (flash.item is FlashItem) {
            // describeFirmware returns a fully-styled Component. Store the
            // raw string — Jade's server→client pipe serializes CompoundTags,
            // and TextComponents don't travel cleanly through putCompound in
            // every Jade release. The client recomposes styling locally.
            data.putString("firmware", FlashItem.describeFirmware(flash).string)
        }
    }

    override fun shouldRequestData(acc: BlockAccessor): Boolean =
        acc.blockEntity is McuBoardBlockEntity

    /* ---------------- Client side ---------------- */

    override fun appendTooltip(tooltip: ITooltip, acc: BlockAccessor, cfg: IPluginConfig) {
        if (acc.blockEntity !is McuBoardBlockEntity) return
        val data = acc.serverData ?: return
        if (data.isEmpty) return

        val on = data.getBoolean("powered")
        tooltip.add(Component.translatable("jade.scev.power").append(": ").append(
            Component.literal(if (on) "● ON" else "○ OFF")
                .withStyle(if (on) ChatFormatting.GREEN else ChatFormatting.GRAY)))

        if (data.contains("soc_tier")) {
            val harts = data.getInt("soc_harts")
            val summary = data.getString("soc_isa") +
                " · $harts hart" + (if (harts == 1) "" else "s") +
                " · " + SocItem.formatRam(data.getInt("soc_ram_kib"))
            tooltip.add(Component.translatable("jade.scev.soc")
                .append(": ")
                .append(Component.literal("tier ${data.getInt("soc_tier")} ($summary)")
                    .withStyle(ChatFormatting.YELLOW)))
        } else {
            tooltip.add(Component.translatable("jade.scev.no_soc").withStyle(ChatFormatting.RED))
        }

        if (data.contains("firmware")) {
            tooltip.add(Component.translatable("jade.scev.firmware")
                .append(": ")
                .append(Component.literal(data.getString("firmware")).withStyle(ChatFormatting.AQUA)))
        } else {
            tooltip.add(Component.translatable("jade.scev.no_flash").withStyle(ChatFormatting.RED))
        }

        val gpioOut = data.getInt("gpio_out")
        if (gpioOut != 0) {
            val f = Direction.values()[data.getByte("facing").toInt() and 0x7]
            val rel = GpioPinMap.worldToRelative(gpioOut and GpioPinMap.PIN_MASK, f)
            tooltip.add(Component.translatable("jade.scev.gpio")
                .append(": ")
                .append(Component.literal(ComputerCaseProvider.renderPins(rel))
                    .withStyle(ChatFormatting.GOLD)))
        }
    }

    companion object {
        @JvmField val INSTANCE: McuBoardProvider = McuBoardProvider()

        private fun facing(be: McuBoardBlockEntity): Direction {
            val bs = be.blockState
            return if (bs.hasProperty(DirectionalBlock.FACING)) bs.getValue(DirectionalBlock.FACING) else Direction.NORTH
        }
    }
}
