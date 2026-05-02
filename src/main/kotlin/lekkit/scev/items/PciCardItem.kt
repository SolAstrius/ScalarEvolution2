/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.items

import java.util.Locale
import net.minecraft.network.chat.Component
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag

/**
 * Generic PCIe expansion card. [Kind] identifies the device kind to attach
 * at machine boot.
 *
 * The tooltip surfaces the kind so two visually similar cards (VGA / Sound
 * look alike at a glance) stay distinguishable in the player's inventory
 * without having to read the item name — the coloured "Kind: VGA" row is
 * the same shape as the other SCEv component tooltips.
 */
open class PciCardItem(props: Properties, val kind: Kind) : Item(props) {

    enum class Kind { NET, VGA, GPIO, SOUND }

    override fun appendHoverText(stack: ItemStack, ctx: TooltipContext, tooltip: MutableList<Component>, flag: TooltipFlag) {
        ScevTooltips.kv(tooltip, "text.scev.pci_kind",
            Component.translatable("text.scev.pci_kind." + kind.name.lowercase(Locale.ROOT)))
        super.appendHoverText(stack, ctx, tooltip, flag)
    }
}
