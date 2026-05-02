/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.blockentity

import lekkit.scev.main.ScevRegistry
import lekkit.scev.recipe.MachineRecipes
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState

/** "Pelikan Ink Vat" — pigment + binder → ink jar. Pelikan (Hannover,
 *  1838-) is the German ink + ribbon house whose blue-tinned ink
 *  bottles are still in pencil cases all over Europe. */
class InkMixerBlockEntity(pos: BlockPos, state: BlockState) :
    ProcessingMachineBlockEntity(
        ScevRegistry.INK_MIXER_BE.get(), pos, state,
        recipeType = MachineRecipes.INK_MIXING_TYPE.get(),
        // Two input slots: pigment + binder. Recipe gates on both.
        inputSlotCount = 2,
        expansionSlotCount = 2,
    )
