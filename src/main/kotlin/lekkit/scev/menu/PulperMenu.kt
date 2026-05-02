/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.menu

import lekkit.scev.blockentity.PulperBlockEntity
import lekkit.scev.main.ScevRegistry
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.world.entity.player.Inventory

class PulperMenu(
    containerId: Int, inv: Inventory, be: PulperBlockEntity,
) : ProcessingMachineMenu(ScevRegistry.PULPER_MENU.get(), containerId, inv, be) {

    companion object {
        @JvmStatic
        fun fromNetwork(containerId: Int, inv: Inventory, buf: RegistryFriendlyByteBuf): PulperMenu {
            val pos = buf.readBlockPos()
            val be = inv.player.level().getBlockEntity(pos)
            if (be is PulperBlockEntity) return PulperMenu(containerId, inv, be)
            throw IllegalStateException("No PulperBlockEntity at $pos")
        }
    }
}
