/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.items;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

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
}
