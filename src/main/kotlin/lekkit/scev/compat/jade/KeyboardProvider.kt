/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.compat.jade

import lekkit.scev.blockentity.KeyboardBlockEntity
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
 * Jade provider for [KeyboardBlockEntity]. Surfaces the machine the
 * keyboard is currently bound to — same "did the bus scan reach this
 * peripheral" diagnostic as CRT / VT100, but critical here because
 * keyboard right-click silently falls back to an action-bar error
 * message when unbound, which is easy to miss.
 *
 * The "has mouse" variant is shown on a separate row so players can
 * tell the two keyboard SKUs apart on peek.
 */
class KeyboardProvider private constructor() :
    IBlockComponentProvider, IServerDataProvider<BlockAccessor> {

    override fun getUid(): ResourceLocation = ScevJadeIds.KEYBOARD

    override fun appendServerData(data: CompoundTag, acc: BlockAccessor) {
        val be = acc.blockEntity as? KeyboardBlockEntity ?: return
        data.putBoolean("mouse", be.hasMouse())
        be.boundMachineUuid()?.let { data.putUUID("linked", it) }
    }

    override fun shouldRequestData(acc: BlockAccessor): Boolean =
        acc.blockEntity is KeyboardBlockEntity

    override fun appendTooltip(tooltip: ITooltip, acc: BlockAccessor, cfg: IPluginConfig) {
        if (acc.blockEntity !is KeyboardBlockEntity) return
        val data = acc.serverData ?: return

        if (data.getBoolean("mouse")) {
            tooltip.add(Component.translatable("jade.scev.keyboard.has_mouse")
                .withStyle(ChatFormatting.GRAY))
        }
        JadeCommon.appendLinkedTo(tooltip, data)
    }

    companion object {
        @JvmField val INSTANCE: KeyboardProvider = KeyboardProvider()
    }
}
