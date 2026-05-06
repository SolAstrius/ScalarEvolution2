/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.items

import lekkit.scev.machine.MachineSpec
import lekkit.scev.machine.MachineSpecParser
import lekkit.scev.main.ScevDataComponents
import lekkit.scev.menu.MachineMenu
import lekkit.scev.server.ItemStackMachineHandle
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.MenuProvider
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import java.util.UUID

/**
 * Open the handheld framebuffer menu against [stack]'s MACHINE_UUID.
 * Reused by every handheld kind's right-click handler — the use() flow
 * is the same regardless of which Item parent class the handheld
 * extends. The UUID may be absent on the very first interaction (the
 * tick host hasn't allocated yet); pass a placeholder and the screen
 * will paint black until the cache fills in next tick.
 */
internal fun openHandheldMenu(sp: ServerPlayer, stack: ItemStack) {
    val uuid = stack.get(ScevDataComponents.MACHINE_UUID.get()) ?: UUID.randomUUID()
    sp.openMenu(object : MenuProvider {
        override fun getDisplayName(): Component = Component.translatable("container.scev.machine")
        override fun createMenu(id: Int, inv: Inventory, p: Player): AbstractContainerMenu =
            MachineMenu(id, inv, uuid, ItemStackMachineHandle(uuid))
    }) { buf ->
        buf.writeByte(MachineMenu.SOURCE_HANDHELD.toInt())
        buf.writeUUID(uuid)
    }
}

/**
 * Handheld backed by a Tinkerpad-style motherboard inventory. Extends
 * [MotherboardItem] so [MachineSpecParser.fromMotherboard] accepts the
 * stack directly — the stack IS the motherboard, with components
 * (CPU/RAM/flash/NVMe/PCI) living in its `MOTHERBOARD_INVENTORY` data
 * component, same shape as a motherboard placed in a desktop case's
 * slot 0. `forceDisplay = true` so the built-in screen is always
 * present regardless of whether a VGA card is installed.
 *
 * Right-click → open `MachineMenu`/`MachineScreen` (same screen the
 * desktop case uses), keyed on the stack's MACHINE_UUID.
 */
open class TinkerpadHandheldItem(props: Properties, motherboardLevel: Int) :
    MotherboardItem(props, motherboardLevel),
    IHandheldComputer {

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        // Shift-right-click opens the motherboard editor (the parent class's
        // behaviour) — lets the player swap CPU/RAM/NVMe etc. without placing
        // the device anywhere. Plain right-click opens the screen menu.
        if (player.isShiftKeyDown) return super.use(level, player, hand)
        val held = player.getItemInHand(hand)
        if (level.isClientSide) return InteractionResultHolder.success(held)
        val sp = player as? ServerPlayer ?: return InteractionResultHolder.pass(held)
        openHandheldMenu(sp, held)
        return InteractionResultHolder.consume(held)
    }

    override fun buildSpec(uuid: UUID, stack: ItemStack): MachineSpec? =
        MachineSpecParser.fromMotherboard(uuid, stack, forceDisplay = true)
}
