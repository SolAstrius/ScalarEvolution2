/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.items;

import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

public class SolderingIronItem extends Item {
    public SolderingIronItem(Properties props) {
        super(props);
    }

    @Override
    public boolean hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        if (stack.getDamageValue() < stack.getMaxDamage()) {
            target.igniteForSeconds(4);
            stack.hurtAndBreak(1, attacker, EntityType.PLAYER.equals(attacker.getType())
                    ? net.minecraft.world.entity.EquipmentSlot.MAINHAND
                    : net.minecraft.world.entity.EquipmentSlot.MAINHAND);
            return true;
        }
        return false;
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        // Two hints: the tool's combat gimmick (4s fire on hit) and its
        // durability remaining. Durability is surfaced here explicitly
        // instead of relying purely on the vanilla durability bar because
        // motherboard recipes consume the whole iron in one craft — players
        // need to see "1 use left" before burning their last iron on a
        // tier-3 motherboard attempt.
        ScevTooltips.desc(tooltip, "text.scev.soldering_iron.hint");
        int remaining = stack.getMaxDamage() - stack.getDamageValue();
        ScevTooltips.kv(tooltip, "text.scev.uses_remaining", Integer.toString(remaining));
        super.appendHoverText(stack, ctx, tooltip, flag);
    }
}
