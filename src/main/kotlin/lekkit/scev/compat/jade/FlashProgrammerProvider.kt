/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.compat.jade

import lekkit.scev.blockentity.FlashProgrammerBlockEntity
import lekkit.scev.items.FlashItem
import net.minecraft.ChatFormatting
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import snownee.jade.api.BlockAccessor
import snownee.jade.api.IBlockComponentProvider
import snownee.jade.api.IServerDataProvider
import snownee.jade.api.ITooltip
import snownee.jade.api.config.IPluginConfig

/**
 * Jade provider for [FlashProgrammerBlockEntity]. Exposes the slot
 * state (source inserted / target inserted, target's current firmware)
 * so players can tell at a glance whether they've loaded the slots
 * correctly before opening the GUI to click Write.
 *
 * For the target flash chip we reuse [FlashItem.describeFirmware] —
 * same rendering as the item tooltip — because "what firmware is on
 * the chip I'm about to overwrite" is the most common thing a player
 * wants to double-check.
 */
class FlashProgrammerProvider private constructor() :
    IBlockComponentProvider, IServerDataProvider<BlockAccessor> {

    override fun getUid(): ResourceLocation = ScevJadeIds.FLASH_PROGRAMMER

    override fun appendServerData(data: CompoundTag, acc: BlockAccessor) {
        val be = acc.blockEntity as? FlashProgrammerBlockEntity ?: return

        val source = be.getItem(FlashProgrammerBlockEntity.SLOT_SOURCE)
        val target = be.getItem(FlashProgrammerBlockEntity.SLOT_TARGET)

        data.putBoolean("has_source", !source.isEmpty)
        data.putBoolean("has_target", !target.isEmpty)
        if (target.item is FlashItem) {
            // Client recomposes styling locally — we just ship the string
            // form, same pattern as McuBoardProvider's firmware field.
            data.putString("target_firmware", FlashItem.describeFirmware(target).string)
        }
    }

    override fun shouldRequestData(acc: BlockAccessor): Boolean =
        acc.blockEntity is FlashProgrammerBlockEntity

    override fun appendTooltip(tooltip: ITooltip, acc: BlockAccessor, cfg: IPluginConfig) {
        if (acc.blockEntity !is FlashProgrammerBlockEntity) return
        val data = acc.serverData ?: return
        if (data.isEmpty) return

        val hasSource = data.getBoolean("has_source")
        val hasTarget = data.getBoolean("has_target")

        tooltip.add(Component.translatable("jade.scev.programmer.source")
            .append(": ")
            .append(Component.literal(if (hasSource) "●" else "○")
                .withStyle(if (hasSource) ChatFormatting.GREEN else ChatFormatting.GRAY)))
        tooltip.add(Component.translatable("jade.scev.programmer.target")
            .append(": ")
            .append(Component.literal(if (hasTarget) "●" else "○")
                .withStyle(if (hasTarget) ChatFormatting.GREEN else ChatFormatting.GRAY)))

        if (data.contains("target_firmware")) {
            tooltip.add(Component.translatable("jade.scev.firmware")
                .append(": ")
                .append(Component.literal(data.getString("target_firmware"))
                    .withStyle(ChatFormatting.AQUA)))
        }
    }

    companion object {
        @JvmField val INSTANCE: FlashProgrammerProvider = FlashProgrammerProvider()
    }
}
