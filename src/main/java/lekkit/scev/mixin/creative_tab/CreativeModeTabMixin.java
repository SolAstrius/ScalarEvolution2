/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.mixin.creative_tab;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import lekkit.scev.client.sections.ScevCreativeTab;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Replaces the vanilla item-list build on SCEv's main tab with our
 * sectioned layout. Non-SCEv tabs fall through to the original
 * implementation via {@link Operation#call}.
 */
@Mixin(CreativeModeTab.class)
public class CreativeModeTabMixin {

    @Shadow private Collection<ItemStack> displayItems;
    @Shadow private Set<ItemStack> displayItemsSearchTab;

    @WrapMethod(method = "buildContents")
    private void scev$buildContents(final CreativeModeTab.ItemDisplayParameters parameters, final Operation<Void> original) {
        final CreativeModeTab self = (CreativeModeTab) (Object) this;
        if (ScevCreativeTab.isScevMainTab(self)) {
            final List<ItemStack> items = new LinkedList<>();
            final Set<ItemStack> search = new LinkedHashSet<>();
            ScevCreativeTab.processItems(items::add, search::add);
            this.displayItems = items;
            this.displayItemsSearchTab = search;
            return;
        }
        original.call(parameters);
    }
}
