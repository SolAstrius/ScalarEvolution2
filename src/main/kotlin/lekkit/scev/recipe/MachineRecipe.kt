/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.recipe

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.HolderLookup
import net.minecraft.core.NonNullList
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.world.SimpleContainer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.Ingredient
import net.minecraft.world.item.crafting.Recipe
import net.minecraft.world.item.crafting.RecipeInput
import net.minecraft.world.item.crafting.RecipeSerializer
import net.minecraft.world.item.crafting.RecipeType
import net.minecraft.world.level.Level

/**
 * Multi-input processing recipe with a per-recipe tick budget. The
 * recipe accepts an *ordered* list of [Ingredient]s; the matching
 * machine BE pairs them positionally with its input slots.
 *
 * Single-input machines (Pulper, SheetFormer, Dryer, Winder,
 * RibbonImpregnator) ship recipes with `ingredients` of length 1.
 * Multi-input machines like [lekkit.scev.blockentity.InkMixerBlockEntity]
 * (pigment + binder) ship recipes with length ≥ 2.
 *
 * JSON shape:
 * ```json
 * {
 *   "type": "scev:ink_mixing",
 *   "ingredients": [ {"item": "scev:pigment"}, {"tag": "scev:binder"} ],
 *   "result": { "id": "scev:ink_jar", "count": 1 },
 *   "time": 120
 * }
 * ```
 *
 * Backward-compat: single-ingredient recipes can use `"ingredient":
 * ...` (singular) instead of an array. The codec accepts both forms.
 */
data class MachineRecipe(
    val ingredients: List<Ingredient>,
    val result: ItemStack,
    val processingTime: Int,
    private val type: RecipeType<MachineRecipe>,
    private val serializer: RecipeSerializer<MachineRecipe>,
) : Recipe<MachineRecipeInput> {

    override fun matches(input: MachineRecipeInput, level: Level): Boolean {
        if (input.size() < ingredients.size) return false
        for (i in ingredients.indices) {
            if (!ingredients[i].test(input.getItem(i))) return false
        }
        return true
    }

    override fun assemble(input: MachineRecipeInput, registries: HolderLookup.Provider): ItemStack =
        result.copy()

    override fun canCraftInDimensions(width: Int, height: Int): Boolean = true

    override fun getResultItem(registries: HolderLookup.Provider): ItemStack = result

    override fun getIngredients(): NonNullList<Ingredient> {
        val list = NonNullList.create<Ingredient>()
        list.addAll(ingredients)
        return list
    }

    override fun getSerializer(): RecipeSerializer<*> = serializer
    override fun getType(): RecipeType<*> = type

    /** First ingredient — convenience for single-input cases (every
     *  machine but the InkMixer at present). */
    val ingredient: Ingredient
        get() = ingredients.firstOrNull() ?: Ingredient.EMPTY

    companion object {
        @JvmStatic
        fun serializer(typeSupplier: () -> RecipeType<MachineRecipe>): RecipeSerializer<MachineRecipe> {
            return object : RecipeSerializer<MachineRecipe> {
                /**
                 * Codec accepts either:
                 *   - `"ingredient": <Ingredient>` — singular, always
                 *     length 1
                 *   - `"ingredients": [<Ingredient>, …]` — vararg
                 * If both are present, `ingredients` wins.
                 */
                private val ingredientsCodec: Codec<List<Ingredient>> = Codec.either(
                    Codec.list(Ingredient.CODEC),
                    Ingredient.CODEC,
                ).xmap(
                    { either -> either.map({ it }, { listOf(it) }) },
                    { list -> if (list.size == 1) com.mojang.datafixers.util.Either.right(list[0])
                              else com.mojang.datafixers.util.Either.left(list) },
                )

                private val codec: MapCodec<MachineRecipe> = RecordCodecBuilder.mapCodec { i ->
                    i.group(
                        ingredientsCodec.optionalFieldOf("ingredients", emptyList())
                            .forGetter { it.ingredients },
                        Ingredient.CODEC.optionalFieldOf("ingredient")
                            .forGetter { java.util.Optional.empty<Ingredient>() },
                        ItemStack.CODEC.fieldOf("result").forGetter { it.result },
                        Codec.INT.optionalFieldOf("time", 200).forGetter { it.processingTime },
                    ).apply(i) { ings, single, res, t ->
                        // Merge: prefer non-empty `ingredients`; fall back
                        // to wrapping `ingredient` (singular) if present.
                        val merged = when {
                            ings.isNotEmpty() -> ings
                            single.isPresent -> listOf(single.get())
                            else -> emptyList()
                        }
                        MachineRecipe(merged, res, t, typeSupplier(), this)
                    }
                }

                /** Wire format: u8 ingredient count, then each Ingredient,
                 *  then result + time. Always uses the array form on the
                 *  wire — singular shorthand is JSON-only. */
                private val outerSerializer = this
                private val streamCodec: StreamCodec<RegistryFriendlyByteBuf, MachineRecipe> =
                    object : StreamCodec<RegistryFriendlyByteBuf, MachineRecipe> {
                        override fun decode(buf: RegistryFriendlyByteBuf): MachineRecipe {
                            val n = buf.readVarInt()
                            val ings = ArrayList<Ingredient>(n)
                            repeat(n) { ings.add(Ingredient.CONTENTS_STREAM_CODEC.decode(buf)) }
                            val res = ItemStack.STREAM_CODEC.decode(buf)
                            val t = buf.readVarInt()
                            return MachineRecipe(ings, res, t, typeSupplier(), outerSerializer)
                        }
                        override fun encode(buf: RegistryFriendlyByteBuf, recipe: MachineRecipe) {
                            buf.writeVarInt(recipe.ingredients.size)
                            for (ing in recipe.ingredients) {
                                Ingredient.CONTENTS_STREAM_CODEC.encode(buf, ing)
                            }
                            ItemStack.STREAM_CODEC.encode(buf, recipe.result)
                            buf.writeVarInt(recipe.processingTime)
                        }
                    }

                override fun codec(): MapCodec<MachineRecipe> = codec
                override fun streamCodec(): StreamCodec<RegistryFriendlyByteBuf, MachineRecipe> = streamCodec
            }
        }

        /** Build a [MachineRecipeInput] from N stacks. Convenience for
         *  the BE's per-tick recipe lookup. */
        @JvmStatic
        fun inputFor(vararg stacks: ItemStack): MachineRecipeInput =
            MachineRecipeInput(stacks.toList())

        @JvmField
        val EMPTY_CONTAINER: SimpleContainer = SimpleContainer(0)
    }
}

/**
 * Wraps the input slots of a [lekkit.scev.blockentity.ProcessingMachineBlockEntity]
 * for recipe matching. Index 0 is the primary input; subsequent
 * indices are secondary (e.g. binder for the InkMixer).
 */
class MachineRecipeInput(private val items: List<ItemStack>) : RecipeInput {
    override fun getItem(index: Int): ItemStack =
        if (index in items.indices) items[index] else ItemStack.EMPTY
    override fun size(): Int = items.size
}
