/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.sections

/**
 * Duck interface applied to `CreativeModeInventoryScreen` by
 * `CreativeModeInventoryScreenMixin`. Exposes the protected `leftPos`/
 * `topPos` fields inherited from `AbstractContainerScreen` without a
 * separate accessor mixin.
 *
 * Lives **outside** the `lekkit.scev.mixin.*` package on purpose: Mixin
 * reserves mixin-declared packages for mixin classes only and throws
 * `IllegalClassLoadError` at runtime when code outside the transformer
 * tries to reference them (e.g. the `(IFace) screen` cast in
 * [ScevCreativeTab.renderBanners]).
 *
 * Method names are prefixed with `scev$` to avoid clashes with any other
 * mod adding similar ducks to the same target.
 */
interface CreativeModeInventoryAccess {
    fun `scev$getLeftPos`(): Int
    fun `scev$getTopPos`(): Int
}
