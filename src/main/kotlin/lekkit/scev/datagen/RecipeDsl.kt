/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.datagen

import net.minecraft.advancements.Criterion
import net.minecraft.advancements.critereon.InventoryChangeTrigger
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.data.recipes.RecipeCategory
import net.minecraft.data.recipes.RecipeOutput
import net.minecraft.data.recipes.ShapedRecipeBuilder
import net.minecraft.data.recipes.ShapelessRecipeBuilder
import net.minecraft.data.recipes.SimpleCookingRecipeBuilder
import net.minecraft.tags.TagKey
import net.minecraft.world.item.Item
import net.minecraft.world.item.crafting.Ingredient
import net.minecraft.world.level.ItemLike

/**
 * Compact recipe DSL on top of vanilla's `*RecipeBuilder` chain. Keeps the
 * underlying behaviour identical — the JSON these emit is byte-identical
 * to the long-form builder calls in vanilla — while letting recipe
 * definitions read like the patterns they describe.
 *
 * Auto-derived advancement criterion name: when [Scope.unlockBy] is given
 * an [ItemLike] without an explicit trigger name, it uses
 * `"has_" + ItemLike.path`, mirroring vanilla's [RecipeProvider.getHasName].
 *
 * This file deliberately does NOT subclass [RecipeProvider] — the helpers
 * we'd inherit (`has`, `getHasName`) are reimplemented inline so the DSL
 * is callable from any datagen context, not just a recipe provider.
 */

@DslMarker
private annotation class RecipeDsl

private fun itemPath(item: ItemLike): String =
    BuiltInRegistries.ITEM.getKey(item.asItem()).path

private fun has(item: ItemLike): Criterion<InventoryChangeTrigger.TriggerInstance> =
    InventoryChangeTrigger.TriggerInstance.hasItems(item.asItem())

private fun has(tag: TagKey<Item>): Criterion<InventoryChangeTrigger.TriggerInstance> =
    InventoryChangeTrigger.TriggerInstance.hasItems(
        net.minecraft.advancements.critereon.ItemPredicate.Builder.item().of(tag).build())

@RecipeDsl
sealed class Scope {
    internal var unlock: Pair<String, Criterion<*>>? = null

    /** Auto-named trigger: `"has_<itemPath>"`. */
    fun unlockBy(item: ItemLike) { unlock = "has_${itemPath(item)}" to has(item) }
    /** Explicit trigger name (matches the original JSON when names differ from the auto rule). */
    fun unlockBy(name: String, item: ItemLike) { unlock = name to has(item) }
    fun unlockBy(name: String, tag: TagKey<Item>) { unlock = name to has(tag) }
    fun unlockBy(name: String, criterion: Criterion<*>) { unlock = name to criterion }

    internal fun requireUnlock(): Pair<String, Criterion<*>> =
        unlock ?: error("recipe is missing unlockBy(...) — at least one trigger is required")
}

@RecipeDsl
class ShapedScope internal constructor(
    private val builder: ShapedRecipeBuilder,
) : Scope() {
    /** Add 1–3 pattern rows in order. */
    fun rows(vararg patterns: String) { patterns.forEach { builder.pattern(it) } }
    infix fun Char.to(item: ItemLike)        { builder.define(this, item) }
    infix fun Char.to(ingredient: Ingredient) { builder.define(this, ingredient) }
    infix fun Char.to(tag: TagKey<Item>)      { builder.define(this, tag) }

    internal fun finish(out: RecipeOutput, saveId: String?) {
        val (n, c) = requireUnlock()
        builder.unlockedBy(n, c)
        if (saveId != null) builder.save(out, saveId) else builder.save(out)
    }
}

@RecipeDsl
class ShapelessScope internal constructor(
    private val builder: ShapelessRecipeBuilder,
) : Scope() {
    /** `+ITEM` adds a single-count ingredient. */
    operator fun ItemLike.unaryPlus() { builder.requires(this) }
    /** `ITEM * 3` adds three of the ingredient. */
    operator fun ItemLike.times(count: Int) { builder.requires(this, count) }
    fun ingredient(item: ItemLike, count: Int = 1) { builder.requires(item, count) }
    fun ingredient(tag: TagKey<Item>) { builder.requires(tag) }
    fun ingredient(ingredient: Ingredient) { builder.requires(ingredient) }

    internal fun finish(out: RecipeOutput, saveId: String?) {
        val (n, c) = requireUnlock()
        builder.unlockedBy(n, c)
        if (saveId != null) builder.save(out, saveId) else builder.save(out)
    }
}

@RecipeDsl
class CookingScope internal constructor(
    private val builder: SimpleCookingRecipeBuilder,
) : Scope() {
    internal fun finish(out: RecipeOutput, saveId: String?) {
        val (n, c) = requireUnlock()
        builder.unlockedBy(n, c)
        if (saveId != null) builder.save(out, saveId) else builder.save(out)
    }
}

/**
 * Recipe-emission scope: holds the [RecipeOutput] so individual recipe
 * calls don't have to plumb it through. Use as
 * `recipes(out) { shaped(...) {...}; smelt(...) {...} }` from a
 * [RecipeProvider.buildRecipes] override.
 */
@RecipeDsl
class RecipesScope internal constructor(internal val out: RecipeOutput) {
    /** Shaped recipe: 9-grid, pattern + key. */
    fun shaped(
        result: ItemLike,
        count: Int = 1,
        category: RecipeCategory = RecipeCategory.MISC,
        saveId: String? = null,
        block: ShapedScope.() -> Unit,
    ) {
        ShapedScope(ShapedRecipeBuilder.shaped(category, result, count))
            .apply(block).finish(out, saveId)
    }

    /** Shapeless recipe: ingredient list, no pattern. */
    fun shapeless(
        result: ItemLike,
        count: Int = 1,
        category: RecipeCategory = RecipeCategory.MISC,
        saveId: String? = null,
        block: ShapelessScope.() -> Unit,
    ) {
        ShapelessScope(ShapelessRecipeBuilder.shapeless(category, result, count))
            .apply(block).finish(out, saveId)
    }

    /** Smelting (200 ticks default). [saveId] disambiguates when multiple recipes share an output. */
    fun smelt(
        input: ItemLike,
        result: ItemLike,
        experience: Float = 0f,
        cookingTime: Int = 200,
        category: RecipeCategory = RecipeCategory.MISC,
        saveId: String? = null,
        block: CookingScope.() -> Unit,
    ) {
        CookingScope(SimpleCookingRecipeBuilder.smelting(
            Ingredient.of(input), category, result, experience, cookingTime))
            .apply(block).finish(out, saveId)
    }
}

/** Entry point — opens a [RecipesScope] backed by [out]. */
fun recipes(out: RecipeOutput, block: RecipesScope.() -> Unit) {
    RecipesScope(out).block()
}
