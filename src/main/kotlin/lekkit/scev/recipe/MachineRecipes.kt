/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.recipe

import java.util.function.Supplier
import lekkit.scev.main.ScalarEvolution
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.world.item.crafting.RecipeSerializer
import net.minecraft.world.item.crafting.RecipeType
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister

/**
 * One [RecipeType] per processing-machine kind, plus the matching
 * [RecipeSerializer]. Datapack JSON addresses these as
 * `scev:ink_mixing`, `scev:ribboning`; the BE class binds to one of
 * them and only matches recipes registered against that type.
 *
 * Adding a new machine kind: add a `register("name")` line below and
 * a matching block + BE in [lekkit.scev.main.ScevRegistry] /
 * [lekkit.scev.blockentity.ProcessingMachineBlockEntity] subclass.
 * Recipes for the new kind drop into `data/scev/recipes/<name>/...json`.
 */
object MachineRecipes {
    val RECIPE_TYPES: DeferredRegister<RecipeType<*>> =
        DeferredRegister.create(Registries.RECIPE_TYPE, ScalarEvolution.MODID)
    val RECIPE_SERIALIZERS: DeferredRegister<RecipeSerializer<*>> =
        DeferredRegister.create(Registries.RECIPE_SERIALIZER, ScalarEvolution.MODID)

    /* ---- ink_mixing: pigment → ink jar (binder + water assumed) ---- */
    val INK_MIXING_TYPE: DeferredHolder<RecipeType<*>, RecipeType<MachineRecipe>> =
        registerType("ink_mixing")
    val INK_MIXING_SERIALIZER: DeferredHolder<RecipeSerializer<*>, RecipeSerializer<MachineRecipe>> =
        registerSerializer("ink_mixing") { INK_MIXING_TYPE.get() }

    /* ---- ribboning: cloth (string + ink) → ribbon spool ---- */
    val RIBBONING_TYPE: DeferredHolder<RecipeType<*>, RecipeType<MachineRecipe>> =
        registerType("ribboning")
    val RIBBONING_SERIALIZER: DeferredHolder<RecipeSerializer<*>, RecipeSerializer<MachineRecipe>> =
        registerSerializer("ribboning") { RIBBONING_TYPE.get() }

    @JvmStatic
    fun register(modBus: IEventBus) {
        RECIPE_TYPES.register(modBus)
        RECIPE_SERIALIZERS.register(modBus)
    }

    private fun registerType(name: String): DeferredHolder<RecipeType<*>, RecipeType<MachineRecipe>> =
        RECIPE_TYPES.register(name, Supplier {
            object : RecipeType<MachineRecipe> {
                override fun toString(): String = "scev:$name"
            }
        })

    private fun registerSerializer(
        name: String,
        typeSupplier: () -> RecipeType<MachineRecipe>,
    ): DeferredHolder<RecipeSerializer<*>, RecipeSerializer<MachineRecipe>> =
        RECIPE_SERIALIZERS.register(name, Supplier { MachineRecipe.serializer(typeSupplier) })
}
