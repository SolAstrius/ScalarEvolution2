/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.menu

import java.util.UUID
import lekkit.scev.blockentity.ComputerCaseBlockEntity
import lekkit.scev.blockentity.TinkerpadBlockEntity
import lekkit.scev.main.ScevRegistry
import lekkit.scev.server.IMachineHandle
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

/**
 * Menu opened to view a running machine's screen. Has no inventory
 * slots — just the player inventory so shift-click doesn't crash.
 */
class MachineMenu(
    containerId: Int,
    inv: Inventory,
    val machineUuid: UUID,
    val machineHandle: IMachineHandle?,
) : AbstractContainerMenu(ScevRegistry.MACHINE_MENU.get(), containerId) {

    init {
        for (row in 0 until 3) {
            for (col in 0 until 9) {
                addSlot(Slot(inv, col + row * 9 + 9,
                    8 + col * 18, SlotDef.FAT_PLAYER_INV_Y + row * 18))
            }
        }
        for (col in 0 until 9) {
            addSlot(Slot(inv, col, 8 + col * 18, SlotDef.FAT_HOTBAR_Y))
        }
    }

    override fun quickMoveStack(player: Player, index: Int): ItemStack = ItemStack.EMPTY

    override fun stillValid(player: Player): Boolean =
        machineHandle != null && machineHandle.isValid()

    companion object {
        @JvmStatic
        fun fromNetwork(containerId: Int, inv: Inventory, buf: RegistryFriendlyByteBuf): MachineMenu {
            val pos = buf.readBlockPos()
            return when (val be = inv.player.level().getBlockEntity(pos)) {
                is TinkerpadBlockEntity -> MachineMenu(containerId, inv, be.getMachineUUID(), be)
                is ComputerCaseBlockEntity -> MachineMenu(containerId, inv, be.getMachineUUID(), be)
                else -> MachineMenu(containerId, inv, UUID.randomUUID(), null)
            }
        }
    }
}
