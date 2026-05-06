/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.compat.jade

import lekkit.scev.blockentity.ProcessingMachineBlockEntity
import lekkit.scev.expansion.ExpansionCardKind
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
 * Jade provider shared across every [ProcessingMachineBlockEntity]
 * subclass — one UID covers all of them since the surface is uniform
 * (slot 0 = input, 1 = output, 2+ = expansion).
 *
 * Surfaces:
 *  - Input slot: "● item.name" / "○" if empty
 *  - Output slot: same
 *  - Progress: "[████░░░░] 50%" when running, suppressed otherwise
 *  - Installed expansion cards: "Cards: Serial, I²C" / "—" if none
 */
class ProcessingMachineProvider private constructor() :
    IBlockComponentProvider, IServerDataProvider<BlockAccessor> {

    override fun getUid(): ResourceLocation = ScevJadeIds.PROCESSING_MACHINE

    override fun appendServerData(data: CompoundTag, acc: BlockAccessor) {
        val be = acc.blockEntity as? ProcessingMachineBlockEntity ?: return
        val input = be.getItem(ProcessingMachineBlockEntity.SLOT_INPUT)
        val output = be.getItem(ProcessingMachineBlockEntity.SLOT_OUTPUT)
        data.putString("input_name", if (input.isEmpty) "" else input.hoverName.string)
        data.putString("output_name", if (output.isEmpty) "" else output.hoverName.string)
        data.putInt("progress", be.progressForDisplay())
        data.putInt("progress_max", be.progressMax())
        // Pack installed kinds as a comma-separated list — small,
        // human-readable, doesn't need a custom NBT codec.
        val kinds = be.expansion.installedKinds()
            .joinToString(",") { it.name }
        data.putString("cards", kinds)
    }

    override fun shouldRequestData(acc: BlockAccessor): Boolean =
        acc.blockEntity is ProcessingMachineBlockEntity

    override fun appendTooltip(tooltip: ITooltip, acc: BlockAccessor, cfg: IPluginConfig) {
        if (acc.blockEntity !is ProcessingMachineBlockEntity) return
        val data = acc.serverData ?: return
        if (data.isEmpty) return

        val inputName = data.getString("input_name")
        val outputName = data.getString("output_name")
        tooltip.add(Component.translatable("jade.scev.machine.input")
            .append(": ")
            .append(slotIndicator(inputName)))
        tooltip.add(Component.translatable("jade.scev.machine.output")
            .append(": ")
            .append(slotIndicator(outputName)))

        val progress = data.getInt("progress")
        val progressMax = data.getInt("progress_max").coerceAtLeast(1)
        if (progress > 0 && progressMax > 0) {
            val pct = (progress * 100) / progressMax
            tooltip.add(Component.translatable("jade.scev.machine.progress")
                .append(": ")
                .append(Component.literal("$pct%")
                    .withStyle(ChatFormatting.YELLOW)))
        }

        val cards = data.getString("cards")
        if (cards.isNotEmpty()) {
            val labels = cards.split(",").map { name ->
                runCatching { ExpansionCardKind.valueOf(name) }
                    .map { Component.translatable(it.langKey).string }
                    .getOrElse { name }
            }
            tooltip.add(Component.translatable("jade.scev.machine.cards")
                .append(": ")
                .append(Component.literal(labels.joinToString(", "))
                    .withStyle(ChatFormatting.AQUA)))
        }
    }

    private fun slotIndicator(name: String): Component =
        if (name.isEmpty())
            Component.literal("○").withStyle(ChatFormatting.GRAY)
        else
            Component.literal("● $name").withStyle(ChatFormatting.GREEN)

    companion object {
        @JvmField val INSTANCE: ProcessingMachineProvider = ProcessingMachineProvider()
    }
}
