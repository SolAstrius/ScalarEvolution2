/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.blockentity

import lekkit.scev.expansion.ExpansionInventory
import lekkit.scev.recipe.MachineRecipe
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.core.NonNullList
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.Container
import net.minecraft.world.ContainerHelper
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.RecipeType
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState

/**
 * Generic multi-input / single-output processing machine — base for
 * every paper / ink / ribbon machine. Subclasses pick:
 *  - [recipeType] — which [RecipeType] this machine consults
 *  - [inputSlotCount] — number of input slots (1 for most, 2 for InkMixer)
 *  - [expansionSlotCount] — number of expansion-card slots
 *
 * Slot layout:
 * ```
 *   indices 0..(inputSlotCount-1)        : input slots
 *   index   inputSlotCount               : output slot
 *   indices (inputSlotCount+1)..N-1      : expansion-card slots
 * ```
 *
 * Convenience: [SLOT_INPUT] (=0) refers to the primary input;
 * [outputSlotIndex] gives the output slot's index for any
 * configuration; [firstExpansionSlotIndex] gives where expansion
 * slots start.
 *
 * Recipe matching follows the multi-input model — every input slot
 * must match the corresponding [MachineRecipe.ingredients] entry.
 * Single-input recipes naturally still work because the recipe just
 * has one ingredient and the BE has one input slot.
 */
abstract class ProcessingMachineBlockEntity(
    type: BlockEntityType<*>,
    pos: BlockPos,
    state: BlockState,
    val recipeType: RecipeType<MachineRecipe>,
    val inputSlotCount: Int = 1,
    val expansionSlotCount: Int = DEFAULT_EXPANSION_SLOTS,
) : ScevBlockEntity(type, pos, state), Container {

    /** Output slot lives right after the input slots. */
    val outputSlotIndex: Int get() = inputSlotCount

    /** First expansion slot lives right after the output slot. */
    val firstExpansionSlotIndex: Int get() = inputSlotCount + 1

    /** Total slot count = inputs + 1 output + expansion. */
    val ioSlotCount: Int get() = inputSlotCount + 1

    /** Stores all input slots + the single output slot. Expansion
     *  cards live in [expansion]. Sized at construction. */
    private val ioItems: NonNullList<ItemStack> =
        NonNullList.withSize(inputSlotCount + 1, ItemStack.EMPTY)

    @JvmField
    val expansion: ExpansionInventory = ExpansionInventory(expansionSlotCount).also {
        it.onChanged = { setChanged() }
    }

    var progressTicks: Int = 0
        private set

    /** Cached snapshot of the current best matching recipe.
     *  Invalidated when any input slot changes. */
    private var cachedRecipe: MachineRecipe? = null
    private var cacheValid: Boolean = false

    /* ---------------- NBT ---------------- */

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        ContainerHelper.loadAllItems(tag, ioItems, registries)
        expansion.load(tag, registries)
        progressTicks = tag.getInt("progress")
    }

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        ContainerHelper.saveAllItems(tag, ioItems, registries)
        expansion.save(tag, registries)
        if (progressTicks != 0) tag.putInt("progress", progressTicks)
    }

    /* ---------------- Container view ---------------- */

    override fun getContainerSize(): Int = ioSlotCount + expansionSlotCount
    override fun isEmpty(): Boolean = ioItems.all { it.isEmpty } && expansion.isEmpty
    override fun getItem(slot: Int): ItemStack = when {
        slot in 0 until ioSlotCount -> ioItems[slot]
        slot in ioSlotCount until containerSize ->
            expansion.getItem(slot - ioSlotCount)
        else -> ItemStack.EMPTY
    }

    override fun removeItem(slot: Int, amount: Int): ItemStack = when {
        slot in 0 until ioSlotCount -> {
            val out = ContainerHelper.removeItem(ioItems, slot, amount)
            if (!out.isEmpty) {
                if (slot < outputSlotIndex) invalidateCachedRecipe()
                setChanged()
            }
            out
        }
        slot in ioSlotCount until containerSize ->
            expansion.removeItem(slot - ioSlotCount, amount)
        else -> ItemStack.EMPTY
    }

    override fun removeItemNoUpdate(slot: Int): ItemStack = when {
        slot in 0 until ioSlotCount -> ContainerHelper.takeItem(ioItems, slot)
        slot in ioSlotCount until containerSize ->
            expansion.removeItemNoUpdate(slot - ioSlotCount)
        else -> ItemStack.EMPTY
    }

    override fun setItem(slot: Int, stack: ItemStack) {
        when {
            slot in 0 until ioSlotCount -> {
                ioItems[slot] = stack
                if (stack.count > maxStackSize) stack.count = maxStackSize
                if (slot < outputSlotIndex) invalidateCachedRecipe()
                setChanged()
            }
            slot in ioSlotCount until containerSize ->
                expansion.setItem(slot - ioSlotCount, stack)
        }
    }

    override fun stillValid(player: Player): Boolean =
        !isRemoved && level != null && level!!.getBlockEntity(blockPos) === this &&
        player.distanceToSqr(blockPos.x + 0.5, blockPos.y + 0.5, blockPos.z + 0.5) <= 64.0

    override fun clearContent() {
        ioItems.clear()
        expansion.clearContent()
        invalidateCachedRecipe()
        setChanged()
    }

    override fun canPlaceItem(slot: Int, stack: ItemStack): Boolean = when {
        slot < outputSlotIndex -> true                              // any input slot accepts anything
        slot == outputSlotIndex -> false                            // output slot is extract-only
        else -> expansion.canPlaceItem(slot - ioSlotCount, stack)
    }

    /* ---------------- Tick / processing ---------------- */

    override fun serverTick(level: Level, pos: BlockPos, state: BlockState) {
        if (level.isClientSide) return
        val recipe = currentRecipe(level) ?: run {
            if (progressTicks != 0) {
                progressTicks = 0
                setChanged()
            }
            return
        }
        if (!canProduceOutput(recipe)) return
        // Subclass-controlled gate (e.g. Pulper requires water in
        // its tank). Pause the timer here rather than reset, so the
        // player adding water resumes mid-craft.
        if (!canTickRecipe(recipe)) return
        progressTicks++
        if (progressTicks >= recipe.processingTime) {
            consumeAndProduce(recipe)
            onCraftComplete(recipe)
            progressTicks = 0
        }
        setChanged()
    }

    /** Subclass hook — return false to pause the recipe timer
     *  without cancelling progress. Default: always tick. */
    protected open fun canTickRecipe(recipe: MachineRecipe): Boolean = true

    /** Subclass hook — fired after [consumeAndProduce] succeeds.
     *  Pulper drains water here; future power-aware machines drain
     *  energy here. Default: no-op. */
    protected open fun onCraftComplete(recipe: MachineRecipe) {}

    private fun currentRecipe(level: Level): MachineRecipe? {
        if (cacheValid) return cachedRecipe
        // Build a snapshot of all input stacks for the recipe matcher.
        val inputs = Array(inputSlotCount) { ioItems[it] }
        // Empty primary input → no recipe possible (matches vanilla
        // furnace behaviour; secondary inputs alone don't trigger).
        if (inputs[0].isEmpty) {
            invalidateCachedRecipe()
            return null
        }
        val rm = level.recipeManager
        val match = rm.getRecipeFor(recipeType, MachineRecipe.inputFor(*inputs), level)
        cachedRecipe = match.map { it.value() }.orElse(null)
        cacheValid = true
        return cachedRecipe
    }

    private fun invalidateCachedRecipe() {
        cachedRecipe = null
        cacheValid = false
    }

    private fun canProduceOutput(recipe: MachineRecipe): Boolean {
        val out = ioItems[outputSlotIndex]
        if (out.isEmpty) return true
        val result = recipe.result
        if (!ItemStack.isSameItemSameComponents(out, result)) return false
        return out.count + result.count <= out.maxStackSize
    }

    private fun consumeAndProduce(recipe: MachineRecipe) {
        // Consume one of each input slot the recipe touches.
        for (i in 0 until minOf(inputSlotCount, recipe.ingredients.size)) {
            val s = ioItems[i]
            s.shrink(1)
            if (s.isEmpty) ioItems[i] = ItemStack.EMPTY
        }
        invalidateCachedRecipe()
        // Produce output (or stack onto existing).
        val out = ioItems[outputSlotIndex]
        if (out.isEmpty) {
            ioItems[outputSlotIndex] = recipe.result.copy()
        } else {
            out.grow(recipe.result.count)
        }
    }

    fun progressForDisplay(): Int = progressTicks
    fun progressMax(): Int = cachedRecipe?.processingTime ?: 1

    companion object {
        /** Primary input slot — always 0. Convenience constant for
         *  single-input subclasses. */
        const val SLOT_INPUT: Int = 0

        /** Default count if a subclass doesn't override. */
        const val DEFAULT_EXPANSION_SLOTS: Int = 2

        /** Backward-compatibility alias — single-input + 1 output = 2.
         *  Existing call sites that referenced this constant continue
         *  to compile; new code should use [outputSlotIndex] instead. */
        @Deprecated("Use ioSlotCount on the BE instance for multi-input compat",
            ReplaceWith("be.ioSlotCount"))
        const val IO_SLOT_COUNT: Int = 2

        /** Same backward-compat as IO_SLOT_COUNT — output slot of a
         *  single-input machine is always 1. */
        @Deprecated("Use outputSlotIndex on the BE instance",
            ReplaceWith("be.outputSlotIndex"))
        const val SLOT_OUTPUT: Int = 1
    }
}
