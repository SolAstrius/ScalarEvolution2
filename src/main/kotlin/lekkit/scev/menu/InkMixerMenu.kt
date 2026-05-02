/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.menu

import lekkit.scev.blockentity.InkMixerBlockEntity
import lekkit.scev.main.ScevRegistry
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.world.entity.player.Inventory

class InkMixerMenu(containerId: Int, inv: Inventory, be: InkMixerBlockEntity) :
    ProcessingMachineMenu(ScevRegistry.INK_MIXER_MENU.get(), containerId, inv, be) {
    companion object {
        @JvmStatic
        fun fromNetwork(id: Int, inv: Inventory, buf: RegistryFriendlyByteBuf): InkMixerMenu {
            val pos = buf.readBlockPos()
            val be = inv.player.level().getBlockEntity(pos)
            if (be is InkMixerBlockEntity) return InkMixerMenu(id, inv, be)
            throw IllegalStateException("No InkMixerBlockEntity at $pos")
        }
    }
}
