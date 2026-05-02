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

/** "Black Clawson Winder" — dry sheet + cardboard core → paper roll.
 *  Black Clawson (American, founded 1895) made the converting + winder
 *  machinery that took the Fourdrinier output and turned it into
 *  consumer-shippable rolls. */
class WinderBlockEntity(pos: BlockPos, state: BlockState) :
    ProcessingMachineBlockEntity(
        ScevRegistry.WINDER_BE.get(), pos, state,
        recipeType = MachineRecipes.WINDING_TYPE.get(),
        expansionSlotCount = 2,
    )
