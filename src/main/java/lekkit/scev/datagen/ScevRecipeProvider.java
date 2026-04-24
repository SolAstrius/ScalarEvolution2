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
import net.minecraft.data.recipes.ShapelessRecipeBuilder;
import net.minecraft.data.recipes.SimpleCookingRecipeBuilder;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;
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

        // Flash programmer — motherboard + flash chip + voltage regulator +
        // soldering iron. Heavy item cost since it lets players stamp
        // arbitrary bytes onto flash.
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ScevRegistry.FLASH_PROGRAMMER.get(), 1)
                .pattern("VFV").pattern("PMP").pattern("VSV")
                .define('V', ScevRegistry.VOLTAGE_REGULATOR.get())
                .define('F', ScevRegistry.FLASH_CHIP.get())
                .define('P', ScevRegistry.PCB_BASE.get())
                .define('M', ScevRegistry.MOTHERBOARD1.get())
                .define('S', ScevRegistry.SOLDERING_IRON.get())
                .unlockedBy("has_motherboard", has(ScevRegistry.MOTHERBOARD1.get()))
                .save(out);

        // Peripheral cable — string coated in redstone with a copper core.
        // 8 cables per recipe so running a bus across a base doesn't burn
        // a player's inventory.
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ScevRegistry.CABLE.get(), 8)
                .pattern("SRS").pattern("RCR").pattern("SRS")
                .define('S', Items.STRING)
                .define('R', Items.REDSTONE)
                .define('C', Items.COPPER_INGOT)
                .unlockedBy("has_redstone", has(Items.REDSTONE))
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

        // ------------------------------------------------------------------
        // Tools & raw fabrication inputs
        // ------------------------------------------------------------------

        // Soldering iron — critical gate: every motherboard / PCI / MCU
        // recipe consumes one. Iron rod + blaze rod (heat source) + copper
        // + redstone. Blaze rod is "late-early game" in vanilla; that
        // tempo matches the first motherboard being a non-trivial craft.
        ShapedRecipeBuilder.shaped(RecipeCategory.TOOLS, ScevRegistry.SOLDERING_IRON.get(), 1)
                .pattern("  C").pattern(" B ").pattern("I  ")
                .define('C', Items.COPPER_INGOT)
                .define('B', Items.BLAZE_ROD)
                .define('I', Items.IRON_INGOT)
                .unlockedBy("has_blaze_rod", has(Items.BLAZE_ROD))
                .save(out);

        // Silicon wafer — smelted from silica compound. Parallel path to
        // mold compound (same input, different output) so a player can
        // pick whichever chain they need next without redoing the silica
        // step.
        SimpleCookingRecipeBuilder.smelting(
                        Ingredient.of(ScevRegistry.SILICA_COMPOUND.get()),
                        RecipeCategory.MISC, ScevRegistry.SILICON_WAFER.get(), 0.1f, 200)
                .unlockedBy("has_silica", has(ScevRegistry.SILICA_COMPOUND.get()))
                .save(out, "silicon_wafer_from_smelting");

        // Electronic parts — a pile of small passives. Cheap "filler"
        // component so bigger recipes can require electronics without
        // demanding that every single passive be its own item family.
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ScevRegistry.ELECTRONIC_PARTS.get(), 4)
                .pattern("RNR").pattern("NCN").pattern("RNR")
                .define('R', Items.REDSTONE)
                .define('N', Items.IRON_NUGGET)
                .define('C', Items.COPPER_INGOT)
                .unlockedBy("has_copper", has(Items.COPPER_INGOT))
                .save(out);

        // Voltage regulator — copper conductor + silicon switching + gold
        // nugget contacts. Used by every motherboard and flash programmer.
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ScevRegistry.VOLTAGE_REGULATOR.get(), 2)
                .pattern("NCN").pattern("CWC").pattern("NCN")
                .define('N', Items.GOLD_NUGGET)
                .define('C', Items.COPPER_INGOT)
                .define('W', ScevRegistry.SILICON_WAFER.get())
                .unlockedBy("has_wafer", has(ScevRegistry.SILICON_WAFER.get()))
                .save(out);

        // RTC module — an oscillator drives the clock, wrapped in
        // electronic parts to smooth the signal. Button cell stand-in:
        // redstone.
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ScevRegistry.RTC_MODULE.get(), 1)
                .pattern(" E ").pattern("EOE").pattern(" R ")
                .define('E', ScevRegistry.ELECTRONIC_PARTS.get())
                .define('O', ScevRegistry.CRYSTAL_OSCILLATOR.get())
                .define('R', Items.REDSTONE)
                .unlockedBy("has_oscillator", has(ScevRegistry.CRYSTAL_OSCILLATOR.get()))
                .save(out);

        // Memory chip — silicon wafer + gold (contacts) + redstone (the
        // "carrier"). Base unit that RAM sticks and NVMe are built from.
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ScevRegistry.MEMORY_CHIP.get(), 2)
                .pattern("NWN").pattern("RWR").pattern("NWN")
                .define('N', Items.GOLD_NUGGET)
                .define('W', ScevRegistry.SILICON_WAFER.get())
                .define('R', Items.REDSTONE)
                .unlockedBy("has_wafer", has(ScevRegistry.SILICON_WAFER.get()))
                .save(out);

        // ------------------------------------------------------------------
        // Storage lineage
        // ------------------------------------------------------------------

        // Flash chip — single silicon die + gold contacts + PCB carrier.
        // Shapeless so the layout isn't fussy. FlashItem is stacksTo(1)
        // so we output one chip per craft; the wafer yield sets the
        // effective "chips per motherboard you build" ratio instead.
        ShapelessRecipeBuilder.shapeless(RecipeCategory.MISC, ScevRegistry.FLASH_CHIP.get(), 1)
                .requires(ScevRegistry.SILICON_WAFER.get())
                .requires(Items.GOLD_NUGGET)
                .requires(ScevRegistry.PCB_BASE.get())
                .unlockedBy("has_wafer", has(ScevRegistry.SILICON_WAFER.get()))
                .save(out);

        // IDE HDD — iron platters in a case, redstone controller, copper
        // data path. Intentionally "bulky cheap storage" vs NVMe.
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ScevRegistry.HDD.get(), 1)
                .pattern("IRI").pattern("ICI").pattern("IDI")
                .define('I', Items.IRON_INGOT)
                .define('R', Items.REDSTONE)
                .define('C', Items.COPPER_INGOT)
                .define('D', ScevRegistry.DSUB_CONNECTOR.get())
                .unlockedBy("has_dsub", has(ScevRegistry.DSUB_CONNECTOR.get()))
                .save(out);

        // NVMe SSD — a pair of memory chips on a small PCB, soldered.
        // Soldering-iron-gated so it lands after the motherboard tier.
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ScevRegistry.NVME.get(), 1)
                .pattern("MPM").pattern("NSN")
                .define('M', ScevRegistry.MEMORY_CHIP.get())
                .define('P', ScevRegistry.PCB_BASE.get())
                .define('N', Items.GOLD_NUGGET)
                .define('S', ScevRegistry.SOLDERING_IRON.get())
                .unlockedBy("has_memory_chip", has(ScevRegistry.MEMORY_CHIP.get()))
                .save(out);

        // ------------------------------------------------------------------
        // Compute lineage (CPUs, SoCs, RAM)
        // ------------------------------------------------------------------

        // CPU tier 1 — one wafer, oscillator, gold contacts. Bare minimum.
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ScevRegistry.CPU1.get(), 1)
                .pattern("NWN").pattern("WOW").pattern("NWN")
                .define('N', Items.GOLD_NUGGET)
                .define('W', ScevRegistry.SILICON_WAFER.get())
                .define('O', ScevRegistry.CRYSTAL_OSCILLATOR.get())
                .unlockedBy("has_wafer", has(ScevRegistry.SILICON_WAFER.get()))
                .save(out);

        // CPU tier 2 — upgrade: previous CPU + oscillator + diamond
        // substrate. Mirrors vanilla's iron → diamond tier jump.
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ScevRegistry.CPU2.get(), 1)
                .pattern("NON").pattern("DCD").pattern("NON")
                .define('N', Items.GOLD_INGOT)
                .define('O', ScevRegistry.CRYSTAL_OSCILLATOR.get())
                .define('D', Items.DIAMOND)
                .define('C', ScevRegistry.CPU1.get())
                .unlockedBy("has_cpu1", has(ScevRegistry.CPU1.get()))
                .save(out);

        // CPU tier 3 — top tier: tier-2 CPU + netherite-grade materials.
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ScevRegistry.CPU3.get(), 1)
                .pattern("EOE").pattern("NCN").pattern("EOE")
                .define('E', Items.EMERALD)
                .define('O', ScevRegistry.CRYSTAL_OSCILLATOR.get())
                .define('N', Items.NETHERITE_INGOT)
                .define('C', ScevRegistry.CPU2.get())
                .unlockedBy("has_cpu2", has(ScevRegistry.CPU2.get()))
                .save(out);

        // SoC tier 1 — bare-metal microcontroller class. Cheaper than
        // CPU1 because it's a smaller die with no external RAM.
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ScevRegistry.SOC1.get(), 1)
                .pattern("NWN").pattern("WEW")
                .define('N', Items.GOLD_NUGGET)
                .define('W', ScevRegistry.SILICON_WAFER.get())
                .define('E', ScevRegistry.ELECTRONIC_PARTS.get())
                .unlockedBy("has_wafer", has(ScevRegistry.SILICON_WAFER.get()))
                .save(out);

        // SoC tier 2 — MCU + RTOS class. Takes a tier-1 SoC and bolts on
        // memory + oscillator for the RTOS tier's KiB-scale on-die RAM.
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ScevRegistry.SOC2.get(), 1)
                .pattern("MOM").pattern("NSN")
                .define('M', ScevRegistry.MEMORY_CHIP.get())
                .define('O', ScevRegistry.CRYSTAL_OSCILLATOR.get())
                .define('N', Items.GOLD_NUGGET)
                .define('S', ScevRegistry.SOC1.get())
                .unlockedBy("has_soc1", has(ScevRegistry.SOC1.get()))
                .save(out);

        // SoC tier 3 — embedded Linux class, rv64. 32 MiB on-die warrants
        // the memory-chip density jump; diamond substrate matches CPU2.
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ScevRegistry.SOC3.get(), 1)
                .pattern("MDM").pattern("DSD").pattern("MDM")
                .define('M', ScevRegistry.MEMORY_CHIP.get())
                .define('D', Items.DIAMOND)
                .define('S', ScevRegistry.SOC2.get())
                .unlockedBy("has_soc2", has(ScevRegistry.SOC2.get()))
                .save(out);

        // RAM sticks — same shape, scaled substrate. Each tier doubles
        // capacity (8 → 16 → 32 → 64 → 128 MiB) and the substrate cost
        // climbs to match, peaking at netherite for the top tier.
        ramRecipe(out, ScevRegistry.RAM_SODIMM1.get(), Items.IRON_NUGGET);
        ramRecipe(out, ScevRegistry.RAM_SODIMM2.get(), Items.GOLD_NUGGET);
        ramRecipe(out, ScevRegistry.RAM_SODIMM3.get(), Items.GOLD_INGOT);
        ramRecipe(out, ScevRegistry.RAM_SODIMM4.get(), Items.DIAMOND);
        ramRecipe(out, ScevRegistry.RAM_SODIMM5.get(), Items.NETHERITE_INGOT);

        // Motherboard tier 2 — tier 1 as core + more expansion. Players
        // pay a soldering iron per tier upgrade; matches the tier-1
        // recipe's iron cost so costs stack linearly with tiers.
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ScevRegistry.MOTHERBOARD2.get(), 1)
                .pattern("DMD").pattern("VBS").pattern("EPE")
                .define('D', ScevRegistry.DSUB_CONNECTOR.get())
                .define('M', ScevRegistry.MEMORY_CHIP.get())
                .define('V', ScevRegistry.VOLTAGE_REGULATOR.get())
                .define('B', ScevRegistry.MOTHERBOARD1.get())
                .define('S', ScevRegistry.SOLDERING_IRON.get())
                .define('E', ScevRegistry.ELECTRONIC_PARTS.get())
                .define('P', ScevRegistry.PCB_BASE.get())
                .unlockedBy("has_motherboard1", has(ScevRegistry.MOTHERBOARD1.get()))
                .save(out);

        // Motherboard tier 3 — top tier, adds diamond + netherite.
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ScevRegistry.MOTHERBOARD3.get(), 1)
                .pattern("DND").pattern("VBS").pattern("EPE")
                .define('D', Items.DIAMOND)
                .define('N', Items.NETHERITE_INGOT)
                .define('V', ScevRegistry.VOLTAGE_REGULATOR.get())
                .define('B', ScevRegistry.MOTHERBOARD2.get())
                .define('S', ScevRegistry.SOLDERING_IRON.get())
                .define('E', ScevRegistry.ELECTRONIC_PARTS.get())
                .define('P', ScevRegistry.PCB_BASE.get())
                .unlockedBy("has_motherboard2", has(ScevRegistry.MOTHERBOARD2.get()))
                .save(out);

        // ------------------------------------------------------------------
        // PCI expansion cards — same shape, different "function" component.
        // ------------------------------------------------------------------
        pciCard(out, ScevRegistry.VGA_CARD.get(),   ScevRegistry.GFX_DISPLAY.get());
        pciCard(out, ScevRegistry.RTL8169.get(),    Items.ENDER_PEARL);
        pciCard(out, ScevRegistry.SOUND_CARD.get(), Items.NOTE_BLOCK);
        // GPIO uses a redstone-heavy shape because it's the redstone-bridge card.
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ScevRegistry.GPIO_CARD.get(), 1)
                .pattern("RDR").pattern("PWP").pattern("RDR")
                .define('R', Items.REDSTONE)
                .define('D', Items.REDSTONE_TORCH)
                .define('P', ScevRegistry.PCB_BASE.get())
                .define('W', ScevRegistry.SILICON_WAFER.get())
                .unlockedBy("has_wafer", has(ScevRegistry.SILICON_WAFER.get()))
                .save(out);

        // ------------------------------------------------------------------
        // Cases & peripherals (blocks placed in the world)
        // ------------------------------------------------------------------

        // Workstation case — iron body, D-sub I/O, PCB backplane, character
        // display for the power LEDs / POST readout.
        ShapedRecipeBuilder.shaped(RecipeCategory.DECORATIONS, ScevRegistry.WORKSTATION.get(), 1)
                .pattern("ICI").pattern("DPD").pattern("IDI")
                .define('I', Items.IRON_INGOT)
                .define('C', ScevRegistry.CHAR_DISPLAY.get())
                .define('D', ScevRegistry.DSUB_CONNECTOR.get())
                .define('P', ScevRegistry.PCB_BASE.get())
                .unlockedBy("has_pcb", has(ScevRegistry.PCB_BASE.get()))
                .save(out);

        // Powermark — upgraded workstation: netherite accent panel + extra
        // I/O. Visually distinct sibling of Workstation so the two register
        // as separate blocks but share the same inner machine semantics.
        ShapedRecipeBuilder.shaped(RecipeCategory.DECORATIONS, ScevRegistry.POWERMARK.get(), 1)
                .pattern("NCN").pattern("DWD").pattern("NDN")
                .define('N', Items.NETHERITE_SCRAP)
                .define('C', ScevRegistry.CHAR_DISPLAY.get())
                .define('D', ScevRegistry.DSUB_CONNECTOR.get())
                .define('W', ScevRegistry.WORKSTATION.get())
                .unlockedBy("has_workstation", has(ScevRegistry.WORKSTATION.get()))
                .save(out);

        // Tinkerpad — laptop chassis: fiberglass shell, char display as
        // screen, keyboard built in, motherboard inside. Dense recipe
        // because it integrates a whole system in one block.
        ShapedRecipeBuilder.shaped(RecipeCategory.DECORATIONS, ScevRegistry.TINKERPAD.get(), 1)
                .pattern("FGF").pattern("KMK").pattern("FPF")
                .define('F', ScevRegistry.FIBERGLASS.get())
                .define('G', ScevRegistry.GFX_DISPLAY.get())
                .define('K', ScevRegistry.KEYBOARD.get())
                .define('M', ScevRegistry.MOTHERBOARD1.get())
                .define('P', ScevRegistry.PCB_BASE.get())
                .unlockedBy("has_motherboard1", has(ScevRegistry.MOTHERBOARD1.get()))
                .save(out);

        // VT100 terminal — vintage character terminal. Char display + cable
        // + D-sub for a serial link, fiberglass shell.
        ShapedRecipeBuilder.shaped(RecipeCategory.DECORATIONS, ScevRegistry.VT100.get(), 1)
                .pattern("FCF").pattern("GDG").pattern("FPF")
                .define('F', ScevRegistry.FIBERGLASS.get())
                .define('C', ScevRegistry.CHAR_DISPLAY.get())
                .define('G', Blocks.GLASS_PANE)
                .define('D', ScevRegistry.DSUB_CONNECTOR.get())
                .define('P', ScevRegistry.PCB_BASE.get())
                .unlockedBy("has_char_display", has(ScevRegistry.CHAR_DISPLAY.get()))
                .save(out);

        // CRT monitor — VT100's graphical sibling. Graphics display instead
        // of char display, same shell + I/O.
        ShapedRecipeBuilder.shaped(RecipeCategory.DECORATIONS, ScevRegistry.CRT_MONITOR.get(), 1)
                .pattern("FXF").pattern("GDG").pattern("FPF")
                .define('F', ScevRegistry.FIBERGLASS.get())
                .define('X', ScevRegistry.GFX_DISPLAY.get())
                .define('G', Blocks.GLASS_PANE)
                .define('D', ScevRegistry.DSUB_CONNECTOR.get())
                .define('P', ScevRegistry.PCB_BASE.get())
                .unlockedBy("has_gfx_display", has(ScevRegistry.GFX_DISPLAY.get()))
                .save(out);

        // Keyboard — flat pizza-box: iron nugget keycaps, PCB backplane,
        // D-sub connector for the peripheral bus port.
        ShapedRecipeBuilder.shaped(RecipeCategory.DECORATIONS, ScevRegistry.KEYBOARD.get(), 1)
                .pattern("NNN").pattern("PPD")
                .define('N', Items.IRON_NUGGET)
                .define('P', ScevRegistry.PCB_BASE.get())
                .define('D', ScevRegistry.DSUB_CONNECTOR.get())
                .unlockedBy("has_pcb", has(ScevRegistry.PCB_BASE.get()))
                .save(out);

        // Keyboard+mouse — shapeless augment: keyboard + slime ball
        // (trackpad surface) + redstone (click switch).
        ShapelessRecipeBuilder.shapeless(RecipeCategory.DECORATIONS, ScevRegistry.KEYBOARD_MOUSE.get(), 1)
                .requires(ScevRegistry.KEYBOARD.get())
                .requires(Items.SLIME_BALL)
                .requires(Items.REDSTONE)
                .unlockedBy("has_keyboard", has(ScevRegistry.KEYBOARD.get()))
                .save(out);

        // MCU board — compact: PCB + SoC socket proxy (D-sub) + soldering
        // iron. Mirrors the motherboard1 cost curve but smaller — MCU is
        // the "tiny board" parallel to full motherboards.
        ShapedRecipeBuilder.shaped(RecipeCategory.DECORATIONS, ScevRegistry.MCU_BOARD.get(), 1)
                .pattern("NDN").pattern("EPE").pattern(" S ")
                .define('N', Items.IRON_NUGGET)
                .define('D', ScevRegistry.DSUB_CONNECTOR.get())
                .define('E', ScevRegistry.ELECTRONIC_PARTS.get())
                .define('P', ScevRegistry.PCB_BASE.get())
                .define('S', ScevRegistry.SOLDERING_IRON.get())
                .unlockedBy("has_pcb", has(ScevRegistry.PCB_BASE.get()))
                .save(out);
    }

    /**
     * Shared RAM recipe shape: memory chips on a PCB with nuggets framing
     * the DIMM contacts. Substrate material differs per tier to reflect
     * the capacity jump (nugget-grade through diamond).
     */
    private void ramRecipe(RecipeOutput out, ItemLike result, ItemLike substrate) {
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, result, 1)
                .pattern("SMS").pattern("MPM").pattern("SMS")
                .define('S', substrate)
                .define('M', ScevRegistry.MEMORY_CHIP.get())
                .define('P', ScevRegistry.PCB_BASE.get())
                .unlockedBy("has_memory_chip", has(ScevRegistry.MEMORY_CHIP.get()))
                .save(out);
    }

    /**
     * Shared PCI expansion card shape. Center slot ("function") varies
     * per card; the rest is the common PCB + electronic parts + D-sub
     * bracket frame so cards look like a consistent family.
     */
    private void pciCard(RecipeOutput out, ItemLike result, ItemLike function) {
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, result, 1)
                .pattern("EDE").pattern("PFP").pattern("NNN")
                .define('E', ScevRegistry.ELECTRONIC_PARTS.get())
                .define('D', ScevRegistry.DSUB_CONNECTOR.get())
                .define('P', ScevRegistry.PCB_BASE.get())
                .define('F', function)
                .define('N', Items.GOLD_NUGGET)
                .unlockedBy("has_pcb", has(ScevRegistry.PCB_BASE.get()))
                .save(out);
    }
}
