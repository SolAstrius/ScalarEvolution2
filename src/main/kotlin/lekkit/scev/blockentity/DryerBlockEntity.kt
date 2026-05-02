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

/** "Valmet Yankee Dryer" — wet sheet → dry sheet. Yankee dryer is the
 *  industry term for the steam-heated drum that finishes paper drying;
 *  Valmet (Finnish) is one of the dominant modern manufacturers. */
class DryerBlockEntity(pos: BlockPos, state: BlockState) :
    ProcessingMachineBlockEntity(
        ScevRegistry.DRYER_BE.get(), pos, state,
        recipeType = MachineRecipes.DRYING_TYPE.get(),
        expansionSlotCount = 2,
    )
