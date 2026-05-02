/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.compat.jei

import lekkit.scev.recipe.MachineRecipe
import mezz.jei.api.constants.VanillaTypes
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder
import mezz.jei.api.gui.drawable.IDrawable
import mezz.jei.api.helpers.IGuiHelper
import mezz.jei.api.recipe.IFocusGroup
import mezz.jei.api.recipe.RecipeIngredientRole
import mezz.jei.api.recipe.RecipeType
import mezz.jei.api.recipe.category.IRecipeCategory
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.RecipeHolder

/**
 * Generic JEI category for any [MachineRecipe]-bearing
 * [net.minecraft.world.item.crafting.RecipeType] — Pulping,
 * SheetForming, Drying, Winding, InkMixing, Ribboning. One concrete
 * instance per type, all sharing the same draw + setRecipe code.
 *
 * Layout (110×54):
 * ```
 *   [in0]
 *   [in1?]   →   [out]   processing time on the right
 *   [in2?]
 * ```
 *
 * The icon comes from the machine block that processes this recipe
 * type — clicking the icon in JEI navigates to that block's
 * recipes / usage.
 */
class ProcessingRecipeCategory(
    private val jeiType: RecipeType<RecipeHolder<MachineRecipe>>,
    private val titleText: Component,
    private val iconStack: ItemStack,
    helper: IGuiHelper,
) : IRecipeCategory<RecipeHolder<MachineRecipe>> {

    private val background: IDrawable =
        helper.createBlankDrawable(WIDTH, HEIGHT)

    private val icon: IDrawable =
        helper.createDrawableItemStack(iconStack)

    override fun getRecipeType(): RecipeType<RecipeHolder<MachineRecipe>> = jeiType
    override fun getTitle(): Component = titleText
    override fun getBackground(): IDrawable = background
    override fun getIcon(): IDrawable = icon

    override fun setRecipe(
        builder: IRecipeLayoutBuilder,
        holder: RecipeHolder<MachineRecipe>,
        focuses: IFocusGroup,
    ) {
        val recipe = holder.value()
        // Stack input slots vertically on the left.
        for ((i, ing) in recipe.ingredients.withIndex()) {
            builder.addSlot(RecipeIngredientRole.INPUT,
                INPUT_X + 1, INPUT_Y_BASE + i * SLOT_PITCH + 1)
                .addIngredients(ing)
        }
        builder.addSlot(RecipeIngredientRole.OUTPUT, OUTPUT_X + 1, OUTPUT_Y + 1)
            .addItemStack(recipe.result)
    }

    override fun draw(
        recipe: RecipeHolder<MachineRecipe>,
        recipeSlotsView: mezz.jei.api.gui.ingredient.IRecipeSlotsView,
        graphics: GuiGraphics,
        mouseX: Double, mouseY: Double,
    ) {
        // Slot frames — single 1-px outline on each input + output.
        val recipe = recipe.value()
        for (i in recipe.ingredients.indices) {
            slotOutline(graphics, INPUT_X, INPUT_Y_BASE + i * SLOT_PITCH)
        }
        slotOutline(graphics, OUTPUT_X, OUTPUT_Y)

        // Arrow between input column and output, vertically centered
        // against the input column's mid-height.
        val midY = INPUT_Y_BASE + (recipe.ingredients.size * SLOT_PITCH) / 2
        arrow(graphics, ARROW_X, midY - 4)

        // Processing-time label to the right of the output.
        val secs = "%.1f s".format(recipe.processingTime / 20.0)
        val font = net.minecraft.client.Minecraft.getInstance().font
        graphics.drawString(font, secs, OUTPUT_X + 22, OUTPUT_Y + 5,
            0x404040, false)
    }

    private fun slotOutline(g: GuiGraphics, x: Int, y: Int) {
        // 18×18 inset frame matching the in-game GUI well style:
        // dark top+left, light bottom+right.
        g.fill(x, y, x + 18, y + 1, 0xFF373737.toInt())
        g.fill(x, y, x + 1, y + 18, 0xFF373737.toInt())
        g.fill(x, y + 17, x + 18, y + 18, 0xFF808080.toInt())
        g.fill(x + 17, y, x + 18, y + 18, 0xFF808080.toInt())
    }

    private fun arrow(g: GuiGraphics, x: Int, y: Int) {
        // Stubby right-pointing arrow, ~24×8 bg + amber tip.
        g.fill(x, y, x + 24, y + 8, 0xFF202020.toInt())
        // Tip
        g.fill(x + 20, y + 1, x + 24, y + 7, 0xFFE0C040.toInt())
        g.fill(x + 18, y + 2, x + 22, y + 6, 0xFFE0C040.toInt())
        g.fill(x + 16, y + 3, x + 20, y + 5, 0xFFE0C040.toInt())
    }

    companion object {
        const val WIDTH: Int = 110
        const val HEIGHT: Int = 54
        const val INPUT_X: Int = 0
        const val INPUT_Y_BASE: Int = 0
        const val SLOT_PITCH: Int = 18
        const val ARROW_X: Int = 24
        const val OUTPUT_X: Int = 60
        const val OUTPUT_Y: Int = 18
    }
}
