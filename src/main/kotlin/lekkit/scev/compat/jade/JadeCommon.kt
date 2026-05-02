/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.compat.jade

import java.util.UUID
import net.minecraft.ChatFormatting
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import snownee.jade.api.ITooltip

/**
 * Shared helpers for the Jade providers — keeps the linked-UUID and
 * "● / ○" presentation identical across the four BE kinds that surface
 * a peripheral-bus link.
 */
internal object JadeCommon {
    /** First 8 hex chars of the UUID + ellipsis. */
    fun shortUuid(u: UUID): String {
        val s = u.toString()
        return s.substring(0, minOf(8, s.length)) + "…"
    }

    /** Append a `linked_to: <short>` row, or `not_linked` if no link present. */
    fun appendLinkedTo(tooltip: ITooltip, data: CompoundTag) {
        if (data.hasUUID("linked")) {
            tooltip.add(
                Component.translatable("jade.scev.linked_to")
                    .append(": ")
                    .append(Component.literal(shortUuid(data.getUUID("linked")))
                        .withStyle(ChatFormatting.AQUA))
            )
        } else {
            tooltip.add(Component.translatable("jade.scev.not_linked")
                .withStyle(ChatFormatting.GRAY))
        }
    }
}
