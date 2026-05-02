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

/** "Voith Sheet Former" — pulp slurry → wet paper sheet. */
class SheetFormerBlockEntity(pos: BlockPos, state: BlockState) :
    ProcessingMachineBlockEntity(
        ScevRegistry.SHEET_FORMER_BE.get(), pos, state,
        recipeType = MachineRecipes.SHEET_FORMING_TYPE.get(),
        expansionSlotCount = 2,
    )
