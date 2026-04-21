/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.mixin.creative_tab;

import lekkit.scev.client.sections.CreativeModeInventoryAccess;
import lekkit.scev.client.sections.ScevCreativeTab;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.item.CreativeModeTab;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws SCEv's section banners on top of the creative tab when the SCEv
 * tab is selected, and exposes layout coordinates to the renderer via the
 * {@link CreativeModeInventoryAccess} duck interface.
 */
@Mixin(CreativeModeInventoryScreen.class)
public abstract class CreativeModeInventoryScreenMixin extends AbstractContainerScreen<CreativeModeInventoryScreen.ItemPickerMenu>
        implements CreativeModeInventoryAccess {

    // Never instantiated — abstract + mixin target never calls this.
    private CreativeModeInventoryScreenMixin() { super(null, null, null); }

    @Shadow private static CreativeModeTab selectedTab;

    @Override
    public int scev$getLeftPos() { return this.leftPos; }

    @Override
    public int scev$getTopPos() { return this.topPos; }

    @Inject(method = "render", at = @At("TAIL"))
    private void scev$render(final GuiGraphics guiGraphics, final int mouseX, final int mouseY, final float partialTick, final CallbackInfo ci) {
        if (ScevCreativeTab.isScevMainTab(selectedTab)) {
            ScevCreativeTab.renderBanners((CreativeModeInventoryScreen) (Object) this, guiGraphics, mouseX, mouseY);
        }
    }
}
