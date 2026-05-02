/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.menu

import lekkit.scev.blockentity.WinderBlockEntity
import lekkit.scev.main.ScevRegistry
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.world.entity.player.Inventory

class WinderMenu(containerId: Int, inv: Inventory, be: WinderBlockEntity) :
    ProcessingMachineMenu(ScevRegistry.WINDER_MENU.get(), containerId, inv, be) {
    companion object {
        @JvmStatic
        fun fromNetwork(id: Int, inv: Inventory, buf: RegistryFriendlyByteBuf): WinderMenu {
            val pos = buf.readBlockPos()
            val be = inv.player.level().getBlockEntity(pos)
            if (be is WinderBlockEntity) return WinderMenu(id, inv, be)
            throw IllegalStateException("No WinderBlockEntity at $pos")
        }
    }
}
