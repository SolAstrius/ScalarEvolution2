/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.compat.jade

import lekkit.scev.blockentity.CRTBlockEntity
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import snownee.jade.api.BlockAccessor
import snownee.jade.api.IBlockComponentProvider
import snownee.jade.api.IServerDataProvider
import snownee.jade.api.ITooltip
import snownee.jade.api.config.IPluginConfig

/**
 * Jade provider for [CRTBlockEntity]. CRT shares the
 * [lekkit.scev.blocks.DirectionalBlock] superclass with VT100 terminals,
 * but its BE class and linking model are different: CRT is a pure
 * peripheral-bus display and only learns its owning machine through the
 * bus scan (there's no proximity auto-discovery like VT100).
 *
 * Surfacing the bus-bound UUID on peek means players can quickly tell
 * whether a CRT has latched onto the computer they wired to — a common
 * "why is this screen black" question before this provider existed was
 * "is the bus even reaching the monitor?".
 */
class CrtMonitorProvider private constructor() :
    IBlockComponentProvider, IServerDataProvider<BlockAccessor> {

    override fun getUid(): ResourceLocation = ScevJadeIds.CRT_MONITOR

    override fun appendServerData(data: CompoundTag, acc: BlockAccessor) {
        val be = acc.blockEntity as? CRTBlockEntity ?: return
        be.boundMachineUuid()?.let { data.putUUID("linked", it) }
    }

    override fun shouldRequestData(acc: BlockAccessor): Boolean =
        acc.blockEntity is CRTBlockEntity

    override fun appendTooltip(tooltip: ITooltip, acc: BlockAccessor, cfg: IPluginConfig) {
        if (acc.blockEntity !is CRTBlockEntity) return
        val data = acc.serverData ?: return
        JadeCommon.appendLinkedTo(tooltip, data)
    }

    companion object {
        @JvmField val INSTANCE: CrtMonitorProvider = CrtMonitorProvider()
    }
}
