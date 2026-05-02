/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.compat.jei

import java.util.Collections
import lekkit.scev.client.screen.MachineScreen
import lekkit.scev.main.ScalarEvolution
import lekkit.scev.main.ScevRegistry
import lekkit.scev.recipe.MachineRecipe
import lekkit.scev.recipe.MachineRecipes
import mezz.jei.api.IModPlugin
import mezz.jei.api.JeiPlugin
import mezz.jei.api.gui.handlers.IGuiContainerHandler
import mezz.jei.api.recipe.RecipeType
import mezz.jei.api.registration.IGuiHandlerRegistration
import mezz.jei.api.registration.IRecipeCatalystRegistration
import mezz.jei.api.registration.IRecipeCategoryRegistration
import mezz.jei.api.registration.IRecipeRegistration
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.Rect2i
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.RecipeHolder

/**
 * JEI plugin. Two responsibilities:
 *   1. Hide JEI's overlay on [MachineScreen] (the framebuffer view) —
 *      exclusion rect that covers the whole screen so JEI has no
 *      sidebar room.
 *   2. Surface every [MachineRecipe] via per-RecipeType categories
 *      so players can browse the paper / ink / ribbon chain.
 *
 * Loaded only when JEI is installed (@JeiPlugin instances are
 * instantiated by JEI itself; missing-JEI builds skip this class).
 */
@JeiPlugin
class ScevJeiPlugin : IModPlugin {
    override fun getPluginUid(): ResourceLocation = ScalarEvolution.rl("jei_plugin")

    override fun registerCategories(registration: IRecipeCategoryRegistration) {
        val helper = registration.jeiHelpers.guiHelper
        registration.addRecipeCategories(
            ProcessingRecipeCategory(JEI_PULPING,
                Component.translatable("jei.scev.category.pulping"),
                ItemStack(ScevRegistry.PULPER.get()), helper),
            ProcessingRecipeCategory(JEI_SHEET_FORMING,
                Component.translatable("jei.scev.category.sheet_forming"),
                ItemStack(ScevRegistry.SHEET_FORMER.get()), helper),
            ProcessingRecipeCategory(JEI_DRYING,
                Component.translatable("jei.scev.category.drying"),
                ItemStack(ScevRegistry.DRYER.get()), helper),
            ProcessingRecipeCategory(JEI_WINDING,
                Component.translatable("jei.scev.category.winding"),
                ItemStack(ScevRegistry.WINDER.get()), helper),
            ProcessingRecipeCategory(JEI_INK_MIXING,
                Component.translatable("jei.scev.category.ink_mixing"),
                ItemStack(ScevRegistry.INK_MIXER.get()), helper),
            ProcessingRecipeCategory(JEI_RIBBONING,
                Component.translatable("jei.scev.category.ribboning"),
                ItemStack(ScevRegistry.RIBBON_IMPREGNATOR.get()), helper),
        )
    }

    override fun registerRecipes(registration: IRecipeRegistration) {
        val rm = Minecraft.getInstance().level?.recipeManager ?: return
        addRecipes(registration, rm, MachineRecipes.PULPING_TYPE.get(),       JEI_PULPING)
        addRecipes(registration, rm, MachineRecipes.SHEET_FORMING_TYPE.get(), JEI_SHEET_FORMING)
        addRecipes(registration, rm, MachineRecipes.DRYING_TYPE.get(),        JEI_DRYING)
        addRecipes(registration, rm, MachineRecipes.WINDING_TYPE.get(),       JEI_WINDING)
        addRecipes(registration, rm, MachineRecipes.INK_MIXING_TYPE.get(),    JEI_INK_MIXING)
        addRecipes(registration, rm, MachineRecipes.RIBBONING_TYPE.get(),     JEI_RIBBONING)
    }

    private fun addRecipes(
        reg: IRecipeRegistration,
        rm: net.minecraft.world.item.crafting.RecipeManager,
        mcType: net.minecraft.world.item.crafting.RecipeType<MachineRecipe>,
        jeiType: RecipeType<RecipeHolder<MachineRecipe>>,
    ) {
        reg.addRecipes(jeiType, rm.getAllRecipesFor(mcType))
    }

    override fun registerRecipeCatalysts(registration: IRecipeCatalystRegistration) {
        registration.addRecipeCatalyst(ItemStack(ScevRegistry.PULPER.get()),             JEI_PULPING)
        registration.addRecipeCatalyst(ItemStack(ScevRegistry.SHEET_FORMER.get()),       JEI_SHEET_FORMING)
        registration.addRecipeCatalyst(ItemStack(ScevRegistry.DRYER.get()),              JEI_DRYING)
        registration.addRecipeCatalyst(ItemStack(ScevRegistry.WINDER.get()),             JEI_WINDING)
        registration.addRecipeCatalyst(ItemStack(ScevRegistry.INK_MIXER.get()),          JEI_INK_MIXING)
        registration.addRecipeCatalyst(ItemStack(ScevRegistry.RIBBON_IMPREGNATOR.get()), JEI_RIBBONING)
    }

    override fun registerGuiHandlers(registration: IGuiHandlerRegistration) {
        registration.addGuiContainerHandler(MachineScreen::class.java, FullScreenExclusion)
    }

    private object FullScreenExclusion : IGuiContainerHandler<MachineScreen> {
        override fun getGuiExtraAreas(screen: MachineScreen): MutableList<Rect2i> =
            Collections.singletonList(Rect2i(0, 0, screen.width, screen.height))
    }

    companion object {
        @JvmField val JEI_PULPING:       RecipeType<RecipeHolder<MachineRecipe>> = jeiType("pulping")
        @JvmField val JEI_SHEET_FORMING: RecipeType<RecipeHolder<MachineRecipe>> = jeiType("sheet_forming")
        @JvmField val JEI_DRYING:        RecipeType<RecipeHolder<MachineRecipe>> = jeiType("drying")
        @JvmField val JEI_WINDING:       RecipeType<RecipeHolder<MachineRecipe>> = jeiType("winding")
        @JvmField val JEI_INK_MIXING:    RecipeType<RecipeHolder<MachineRecipe>> = jeiType("ink_mixing")
        @JvmField val JEI_RIBBONING:     RecipeType<RecipeHolder<MachineRecipe>> = jeiType("ribboning")

        @Suppress("UNCHECKED_CAST")
        private fun jeiType(name: String): RecipeType<RecipeHolder<MachineRecipe>> =
            RecipeType.create(ScalarEvolution.MODID, name,
                RecipeHolder::class.java as Class<RecipeHolder<MachineRecipe>>)
    }
}
