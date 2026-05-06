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
import lekkit.scev.server.ItemStackMachineHandle
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
        /** Discriminator for the network buf: opens against a placed BE. */
        const val SOURCE_BLOCK: Byte = 0
        /** Discriminator for the network buf: opens against a held handheld. */
        const val SOURCE_HANDHELD: Byte = 1

        @JvmStatic
        fun fromNetwork(containerId: Int, inv: Inventory, buf: RegistryFriendlyByteBuf): MachineMenu {
            val source = buf.readByte()
            return when (source) {
                SOURCE_BLOCK -> {
                    val pos = buf.readBlockPos()
                    when (val be = inv.player.level().getBlockEntity(pos)) {
                        is TinkerpadBlockEntity -> MachineMenu(containerId, inv, be.getMachineUUID(), be)
                        is ComputerCaseBlockEntity -> MachineMenu(containerId, inv, be.getMachineUUID(), be)
                        else -> MachineMenu(containerId, inv, UUID.randomUUID(), null)
                    }
                }
                SOURCE_HANDHELD -> {
                    val uuid = buf.readUUID()
                    // Client side only needs the UUID to look up DisplayManager;
                    // the IMachineHandle is server-authoritative and used for
                    // stillValid + power/reset. ItemStackMachineHandle has no
                    // dependency on world state (no level/pos), so it's safe
                    // to construct on either side.
                    MachineMenu(containerId, inv, uuid, ItemStackMachineHandle(uuid))
                }
                else -> MachineMenu(containerId, inv, UUID.randomUUID(), null)
            }
        }
    }
}
