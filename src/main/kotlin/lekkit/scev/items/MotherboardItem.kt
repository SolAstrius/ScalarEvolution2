/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.items

import lekkit.scev.menu.MotherboardMenu
import lekkit.scev.menu.openScevMenu
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.level.Level

class MotherboardItem(props: Properties, val level: Int) : Item(props) {

    fun ramSlots(): Int = when (level) { 1 -> 2; 2 -> 3; 3 -> 4; else -> 0 }
    fun pciSlots(): Int = when (level) { 1 -> 2; 2 -> 4; 3 -> 6; else -> 0 }
    fun m2Slots(): Int = when (level) { 1 -> 1; 2 -> 1; 3 -> 2; else -> 0 }

    fun isSlotEnabled(index: Int): Boolean = when (index) {
        0, 1, 2, 3 -> level >= 1
        4 -> level >= 2
        5 -> level >= 3
        6 -> level >= 1
        7 -> level >= 3
        8, 9 -> level >= 1
        10, 11 -> level >= 2
        12, 13 -> level >= 3
        else -> false
    }

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        if (!level.isClientSide && player is ServerPlayer) {
            // The motherboard must live at a determinable inventory slot so the
            // client-side menu can follow it. For MAIN_HAND, that's the selected
            // hotbar slot. OFF_HAND isn't used yet — fall through with PASS.
            if (hand != InteractionHand.MAIN_HAND) {
                return InteractionResultHolder.pass(player.getItemInHand(hand))
            }
            val selected = player.inventory.selected
            player.openScevMenu("container.scev.motherboard", { buf -> buf.writeVarInt(selected) }) { id, inv ->
                MotherboardMenu(id, inv, selected)
            }
            return InteractionResultHolder.consume(player.getItemInHand(hand))
        }
        return InteractionResultHolder.success(player.getItemInHand(hand))
    }

    override fun appendHoverText(stack: ItemStack, ctx: TooltipContext, tooltip: MutableList<Component>, flag: TooltipFlag) {
        ScevTooltips.kv(tooltip, "text.scev.tier", level.toString())
        ScevTooltips.kv(tooltip, "text.scev.ram_slots", ramSlots().toString())
        ScevTooltips.kv(tooltip, "text.scev.pci_slots", pciSlots().toString())
        ScevTooltips.kv(tooltip, "text.scev.m2_slots", m2Slots().toString())
        super.appendHoverText(stack, ctx, tooltip, flag)
    }

    companion object {
        const val SLOT_CPU: Int = 0
        const val SLOT_FLASH: Int = 1
        /** Inclusive first RAM slot. */
        const val SLOT_RAM_START: Int = 2
        /** Inclusive last RAM slot. */
        const val SLOT_RAM_END: Int = 5
        /** Inclusive first NVMe (m.2) slot. */
        const val SLOT_NVME_START: Int = 6
        /** Inclusive last NVMe (m.2) slot. */
        const val SLOT_NVME_END: Int = 7
        /** Inclusive first PCIe slot. */
        const val SLOT_PCI_START: Int = 8
        /** Inclusive last PCIe slot. */
        const val SLOT_PCI_END: Int = 13

        const val INVENTORY_SIZE: Int = 14
    }
}
