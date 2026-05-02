/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.menu

import lekkit.scev.blockentity.SheetFormerBlockEntity
import lekkit.scev.main.ScevRegistry
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.world.entity.player.Inventory

class SheetFormerMenu(containerId: Int, inv: Inventory, be: SheetFormerBlockEntity) :
    ProcessingMachineMenu(ScevRegistry.SHEET_FORMER_MENU.get(), containerId, inv, be) {
    companion object {
        @JvmStatic
        fun fromNetwork(id: Int, inv: Inventory, buf: RegistryFriendlyByteBuf): SheetFormerMenu {
            val pos = buf.readBlockPos()
            val be = inv.player.level().getBlockEntity(pos)
            if (be is SheetFormerBlockEntity) return SheetFormerMenu(id, inv, be)
            throw IllegalStateException("No SheetFormerBlockEntity at $pos")
        }
    }
}
