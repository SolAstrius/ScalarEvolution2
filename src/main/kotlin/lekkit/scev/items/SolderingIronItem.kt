/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.items

import net.minecraft.network.chat.Component
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag

class SolderingIronItem(props: Properties) : Item(props) {

    override fun hurtEnemy(stack: ItemStack, target: LivingEntity, attacker: LivingEntity): Boolean {
        if (stack.damageValue < stack.maxDamage) {
            target.igniteForSeconds(4f)
            // Both branches use MAINHAND today — kept the original branch as a placeholder
            // for a future off-hand strike-back path.
            val slot = if (EntityType.PLAYER == attacker.type) EquipmentSlot.MAINHAND else EquipmentSlot.MAINHAND
            stack.hurtAndBreak(1, attacker, slot)
            return true
        }
        return false
    }

    override fun isBarVisible(stack: ItemStack): Boolean = true

    override fun appendHoverText(stack: ItemStack, ctx: TooltipContext, tooltip: MutableList<Component>, flag: TooltipFlag) {
        // Two hints: the tool's combat gimmick (4s fire on hit) and its
        // durability remaining. Durability is surfaced here explicitly
        // instead of relying purely on the vanilla durability bar because
        // motherboard recipes consume the whole iron in one craft — players
        // need to see "1 use left" before burning their last iron on a
        // tier-3 motherboard attempt.
        ScevTooltips.desc(tooltip, "text.scev.soldering_iron.hint")
        val remaining = stack.maxDamage - stack.damageValue
        ScevTooltips.kv(tooltip, "text.scev.uses_remaining", remaining.toString())
        super.appendHoverText(stack, ctx, tooltip, flag)
    }
}
