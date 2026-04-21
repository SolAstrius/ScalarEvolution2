/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.sections;

/**
 * Duck interface applied to {@link net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen}
 * by {@code CreativeModeInventoryScreenMixin}. Exposes the protected
 * {@code leftPos} / {@code topPos} fields inherited from
 * {@code AbstractContainerScreen} without a separate accessor mixin.
 *
 * <p>Lives <em>outside</em> the {@code lekkit.scev.mixin.*} package on
 * purpose: Mixin reserves mixin-declared packages for mixin classes only
 * and throws {@code IllegalClassLoadError} at runtime when code outside
 * the transformer tries to reference them (e.g. the {@code (IFace) screen}
 * cast in {@link ScevCreativeTab#renderBanners}).
 *
 * <p>Method names are prefixed with {@code scev$} to avoid clashes with
 * any other mod adding similar ducks to the same target.
 */
public interface CreativeModeInventoryAccess {
    int scev$getLeftPos();
    int scev$getTopPos();
}
