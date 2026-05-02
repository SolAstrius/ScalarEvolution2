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

/** "Kores Ribbon Impregnator" — woven cloth + ink → ribbon spool.
 *  Kores (Austria, 1887-) made the typewriter ribbons that taped
 *  every office in the 20th century to the click-clack soundtrack. */
class RibbonImpregnatorBlockEntity(pos: BlockPos, state: BlockState) :
    ProcessingMachineBlockEntity(
        ScevRegistry.RIBBON_IMPREGNATOR_BE.get(), pos, state,
        recipeType = MachineRecipes.RIBBONING_TYPE.get(),
        expansionSlotCount = 2,
    )
