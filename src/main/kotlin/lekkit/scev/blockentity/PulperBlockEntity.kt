/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.blockentity

import lekkit.scev.main.ScevRegistry
import lekkit.scev.recipe.MachineRecipe
import lekkit.scev.recipe.MachineRecipes
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.Fluids
import net.neoforged.neoforge.fluids.FluidStack
import net.neoforged.neoforge.fluids.capability.templates.FluidTank

/**
 * "Beloit Mark II Pulper" — turns sugar cane / wood into pulp slurry.
 * The pulper needs water to operate; without it the recipe pauses
 * mid-tick rather than resetting (player refills, recipe resumes).
 *
 * Tank: 4000 mB (= 4 buckets) of water. Each completed craft drains
 * [WATER_PER_CRAFT] mB. Players load water via right-clicking the
 * pulper with a water bucket (handled by NeoForge's
 * Capabilities.FluidHandler.BLOCK + the vanilla bucket-on-fluid-
 * handler interaction).
 *
 * Future: when other mods pipe water in via their own machinery
 * (Create's pumps, Mekanism's mechanical pipes), they hit the same
 * capability and Just Work.
 */
class PulperBlockEntity(pos: BlockPos, state: BlockState) :
    ProcessingMachineBlockEntity(
        ScevRegistry.PULPER_BE.get(), pos, state,
        recipeType = MachineRecipes.PULPING_TYPE.get(),
        expansionSlotCount = 2,
    ) {

    /** Water tank — 4 bucket capacity, water-only. Public so the
     *  capability provider can hand it to NeoForge's
     *  Capabilities.FluidHandler.BLOCK without awkward accessor
     *  ceremony. */
    @JvmField
    val waterTank: FluidTank = object : FluidTank(TANK_CAPACITY_MB) {
        override fun isFluidValid(stack: FluidStack): Boolean =
            stack.fluid === Fluids.WATER || stack.fluid === Fluids.FLOWING_WATER
        override fun onContentsChanged() { setChanged() }
    }

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        if (tag.contains("water_tank")) {
            waterTank.readFromNBT(registries, tag.getCompound("water_tank"))
        }
    }

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        tag.put("water_tank", waterTank.writeToNBT(registries, CompoundTag()))
    }

    /** Pulper-specific gate: enough water for one craft to complete. */
    override fun canTickRecipe(recipe: MachineRecipe): Boolean =
        waterTank.fluidAmount >= WATER_PER_CRAFT

    /** Drain water alongside the input consume on craft completion. */
    override fun onCraftComplete(recipe: MachineRecipe) {
        waterTank.drain(WATER_PER_CRAFT,
            net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE)
    }

    companion object {
        /** Tank capacity in mB. 4000 mB = 4 buckets — comfortable for
         *  a session of pulping without constant refilling. */
        const val TANK_CAPACITY_MB: Int = 4000

        /** Water consumed per pulped batch. 250 mB = ¼ bucket — one
         *  full tank yields 16 crafts before needing a refill. */
        const val WATER_PER_CRAFT: Int = 250
    }
}
