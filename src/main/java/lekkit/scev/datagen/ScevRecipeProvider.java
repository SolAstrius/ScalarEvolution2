/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.datagen;

import java.util.concurrent.CompletableFuture;
import lekkit.scev.main.ScevRegistry;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.data.recipes.ShapedRecipeBuilder;
import net.minecraft.data.recipes.SimpleCookingRecipeBuilder;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.Blocks;

public class ScevRecipeProvider extends RecipeProvider {
    public ScevRecipeProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookup) {
        super(output, lookup);
    }

    @Override
    protected void buildRecipes(RecipeOutput out) {
        // Epoxy: S R S / R P R / S R S — sugar, sugarcane, potion
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ScevRegistry.EPOXY.get(), 1)
                .pattern("SRS").pattern("RPR").pattern("SRS")
                .define('S', Items.SUGAR)
                .define('R', Items.SUGAR_CANE)
                .define('P', Items.POTION)
                .unlockedBy("has_sugar", has(Items.SUGAR))
                .save(out);

        // Silica compound: sand + obsidian + epoxy
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ScevRegistry.SILICA_COMPOUND.get(), 16)
                .pattern("SOS").pattern("OEO").pattern("SOS")
                .define('S', Blocks.SAND)
                .define('E', ScevRegistry.EPOXY.get())
                .define('O', Blocks.OBSIDIAN)
                .unlockedBy("has_epoxy", has(ScevRegistry.EPOXY.get()))
                .save(out);

        // Silica compound smelts into mold compound
        SimpleCookingRecipeBuilder.smelting(
                        Ingredient.of(ScevRegistry.SILICA_COMPOUND.get()),
                        RecipeCategory.MISC, ScevRegistry.MOLD_COMPOUND.get(), 0f, 200)
                .unlockedBy("has_silica", has(ScevRegistry.SILICA_COMPOUND.get()))
                .save(out, "mold_compound_from_smelting");

        // Fiberglass: glass_pane + epoxy
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ScevRegistry.FIBERGLASS.get(), 1)
                .pattern("GGG").pattern("EEE").pattern("GGG")
                .define('G', Blocks.GLASS_PANE)
                .define('E', ScevRegistry.EPOXY.get())
                .unlockedBy("has_epoxy", has(ScevRegistry.EPOXY.get()))
                .save(out);

        // PCB base
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ScevRegistry.PCB_BASE.get(), 1)
                .pattern("NRN").pattern("FFF").pattern("NRN")
                .define('N', Items.GOLD_NUGGET)
                .define('R', Items.REDSTONE)
                .define('F', ScevRegistry.FIBERGLASS.get())
                .unlockedBy("has_fiberglass", has(ScevRegistry.FIBERGLASS.get()))
                .save(out);

        // Crystal oscillator
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ScevRegistry.CRYSTAL_OSCILLATOR.get(), 1)
                .pattern("III").pattern("MQM").pattern("R R")
                .define('I', Items.IRON_INGOT)
                .define('M', ScevRegistry.MOLD_COMPOUND.get())
                .define('Q', Items.QUARTZ)
                .define('R', Items.REDSTONE)
                .unlockedBy("has_mold", has(ScevRegistry.MOLD_COMPOUND.get()))
                .save(out);

        // D-Sub connector
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ScevRegistry.DSUB_CONNECTOR.get(), 1)
                .pattern("INI").pattern(" M ")
                .define('I', Items.IRON_INGOT)
                .define('N', Items.GOLD_NUGGET)
                .define('M', ScevRegistry.MOLD_COMPOUND.get())
                .unlockedBy("has_mold", has(ScevRegistry.MOLD_COMPOUND.get()))
                .save(out);

        // Character display
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ScevRegistry.CHAR_DISPLAY.get(), 1)
                .pattern("MRM").pattern("GSL").pattern("MNM")
                .define('M', ScevRegistry.MOLD_COMPOUND.get())
                .define('R', Items.REDSTONE)
                .define('G', Blocks.GLASS_PANE)
                .define('S', Items.OAK_SIGN)
                .define('L', Blocks.REDSTONE_LAMP)
                .define('N', Items.GOLD_NUGGET)
                .unlockedBy("has_mold", has(ScevRegistry.MOLD_COMPOUND.get()))
                .save(out);

        // Graphics display
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ScevRegistry.GFX_DISPLAY.get(), 1)
                .pattern("MRM").pattern("GDL").pattern("MNM")
                .define('M', ScevRegistry.MOLD_COMPOUND.get())
                .define('R', Blocks.REDSTONE_BLOCK)
                .define('G', Blocks.GLASS_PANE)
                .define('D', Items.DIAMOND)
                .define('L', Blocks.REDSTONE_LAMP)
                .define('N', Items.GOLD_NUGGET)
                .unlockedBy("has_mold", has(ScevRegistry.MOLD_COMPOUND.get()))
                .save(out);

        // Motherboard (tier 1)
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ScevRegistry.MOTHERBOARD1.get(), 1)
                .pattern(" V ").pattern("DPC").pattern("OES")
                .define('V', ScevRegistry.VOLTAGE_REGULATOR.get())
                .define('D', ScevRegistry.DSUB_CONNECTOR.get())
                .define('P', ScevRegistry.PCB_BASE.get())
                .define('C', ScevRegistry.RTC_MODULE.get())
                .define('O', ScevRegistry.CRYSTAL_OSCILLATOR.get())
                .define('E', ScevRegistry.ELECTRONIC_PARTS.get())
                .define('S', ScevRegistry.SOLDERING_IRON.get())
                .unlockedBy("has_pcb", has(ScevRegistry.PCB_BASE.get()))
                .save(out);
    }
}
