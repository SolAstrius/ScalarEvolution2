/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.mixin.creative_tab;

import lekkit.scev.client.sections.ScevCreativeTab;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tracks the currently-scrolled row of the creative-tab item list so the
 * banner renderer can offset banner Y positions in sync with scroll.
 */
@Mixin(CreativeModeInventoryScreen.ItemPickerMenu.class)
public abstract class ItemPickerMenuMixin {

    @Shadow protected abstract int getRowIndexForScroll(float f);

    @Inject(method = "scrollTo", at = @At("HEAD"))
    private void scev$scrollTo(final float f, final CallbackInfo ci) {
        ScevCreativeTab.CURRENT_ROW = this.getRowIndexForScroll(f);
    }
}
