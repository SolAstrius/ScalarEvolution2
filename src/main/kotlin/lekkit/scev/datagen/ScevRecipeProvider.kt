/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.datagen

import java.util.concurrent.CompletableFuture
import lekkit.scev.main.ScevRegistry as R
import net.minecraft.core.HolderLookup
import net.minecraft.data.PackOutput
import net.minecraft.data.recipes.RecipeCategory.DECORATIONS
import net.minecraft.data.recipes.RecipeCategory.MISC
import net.minecraft.data.recipes.RecipeCategory.TOOLS
import net.minecraft.data.recipes.RecipeOutput
import net.minecraft.data.recipes.RecipeProvider
import net.minecraft.world.item.Items
import net.minecraft.world.level.ItemLike
import net.minecraft.world.level.block.Blocks

class ScevRecipeProvider(
    output: PackOutput,
    lookup: CompletableFuture<HolderLookup.Provider>,
) : RecipeProvider(output, lookup) {

    override fun buildRecipes(out: RecipeOutput) = recipes(out) { with(R) {
        // ------------------------------------------------------------------
        // Crafting supplies
        // ------------------------------------------------------------------

        // Epoxy: sugar + sugarcane + potion
        shaped(EPOXY) {
            rows("SRS", "RPR", "SRS")
            'S' to Items.SUGAR
            'R' to Items.SUGAR_CANE
            'P' to Items.POTION
            unlockBy(Items.SUGAR)
        }

        // Silica compound: sand + obsidian + epoxy (×16)
        shaped(SILICA_COMPOUND, count = 16) {
            rows("SOS", "OEO", "SOS")
            'S' to Blocks.SAND
            'E' to EPOXY
            'O' to Blocks.OBSIDIAN
            unlockBy("has_epoxy", EPOXY)
        }

        // Silica → mold compound (smelting). Custom save id needed because the
        // silicon-wafer smelting below shares the same input.
        smelt(SILICA_COMPOUND, MOLD_COMPOUND, saveId = "mold_compound_from_smelting") {
            unlockBy("has_silica", SILICA_COMPOUND)
        }

        // Fiberglass
        shaped(FIBERGLASS) {
            rows("GGG", "EEE", "GGG")
            'G' to Blocks.GLASS_PANE
            'E' to EPOXY
            unlockBy("has_epoxy", EPOXY)
        }

        // PCB base
        shaped(PCB_BASE) {
            rows("NRN", "FFF", "NRN")
            'N' to Items.GOLD_NUGGET
            'R' to Items.REDSTONE
            'F' to FIBERGLASS
            unlockBy("has_fiberglass", FIBERGLASS)
        }

        // Crystal oscillator
        shaped(CRYSTAL_OSCILLATOR) {
            rows("III", "MQM", "R R")
            'I' to Items.IRON_INGOT
            'M' to MOLD_COMPOUND
            'Q' to Items.QUARTZ
            'R' to Items.REDSTONE
            unlockBy("has_mold", MOLD_COMPOUND)
        }

        // D-Sub connector
        shaped(DSUB_CONNECTOR) {
            rows("INI", " M ")
            'I' to Items.IRON_INGOT
            'N' to Items.GOLD_NUGGET
            'M' to MOLD_COMPOUND
            unlockBy("has_mold", MOLD_COMPOUND)
        }

        // Character display
        shaped(CHAR_DISPLAY) {
            rows("MRM", "GSL", "MNM")
            'M' to MOLD_COMPOUND
            'R' to Items.REDSTONE
            'G' to Blocks.GLASS_PANE
            'S' to Items.OAK_SIGN
            'L' to Blocks.REDSTONE_LAMP
            'N' to Items.GOLD_NUGGET
            unlockBy("has_mold", MOLD_COMPOUND)
        }

        // Graphics display
        shaped(GFX_DISPLAY) {
            rows("MRM", "GDL", "MNM")
            'M' to MOLD_COMPOUND
            'R' to Blocks.REDSTONE_BLOCK
            'G' to Blocks.GLASS_PANE
            'D' to Items.DIAMOND
            'L' to Blocks.REDSTONE_LAMP
            'N' to Items.GOLD_NUGGET
            unlockBy("has_mold", MOLD_COMPOUND)
        }

        // Flash programmer — motherboard + flash chip + voltage regulator +
        // soldering iron. Heavy item cost since it lets players stamp
        // arbitrary bytes onto flash.
        shaped(FLASH_PROGRAMMER) {
            rows("VFV", "PMP", "VSV")
            'V' to VOLTAGE_REGULATOR
            'F' to FLASH_CHIP
            'P' to PCB_BASE
            'M' to MOTHERBOARD1
            'S' to SOLDERING_IRON
            unlockBy("has_motherboard", MOTHERBOARD1)
        }

        // Peripheral cable (×8)
        shaped(CABLE, count = 8) {
            rows("SRS", "RCR", "SRS")
            'S' to Items.STRING
            'R' to Items.REDSTONE
            'C' to Items.COPPER_INGOT
            unlockBy(Items.REDSTONE)
        }

        // Motherboard tier 1
        shaped(MOTHERBOARD1) {
            rows(" V ", "DPC", "OES")
            'V' to VOLTAGE_REGULATOR
            'D' to DSUB_CONNECTOR
            'P' to PCB_BASE
            'C' to RTC_MODULE
            'O' to CRYSTAL_OSCILLATOR
            'E' to ELECTRONIC_PARTS
            'S' to SOLDERING_IRON
            unlockBy("has_pcb", PCB_BASE)
        }

        // ------------------------------------------------------------------
        // Tools & raw fabrication inputs
        // ------------------------------------------------------------------

        // Soldering iron — critical gate: every motherboard / PCI / MCU
        // recipe consumes one. Iron rod + blaze rod (heat source) + copper
        // + redstone.
        shaped(SOLDERING_IRON, category = TOOLS) {
            rows("  C", " B ", "I  ")
            'C' to Items.COPPER_INGOT
            'B' to Items.BLAZE_ROD
            'I' to Items.IRON_INGOT
            unlockBy("has_blaze_rod", Items.BLAZE_ROD)
        }

        // Silicon wafer — smelted from silica compound. Parallel path to
        // mold compound (same input, different output).
        smelt(SILICA_COMPOUND, SILICON_WAFER,
            experience = 0.1f, saveId = "silicon_wafer_from_smelting") {
            unlockBy("has_silica", SILICA_COMPOUND)
        }

        // Electronic parts (×4)
        shaped(ELECTRONIC_PARTS, count = 4) {
            rows("RNR", "NCN", "RNR")
            'R' to Items.REDSTONE
            'N' to Items.IRON_NUGGET
            'C' to Items.COPPER_INGOT
            unlockBy("has_copper", Items.COPPER_INGOT)
        }

        // Voltage regulator (×2)
        shaped(VOLTAGE_REGULATOR, count = 2) {
            rows("NCN", "CWC", "NCN")
            'N' to Items.GOLD_NUGGET
            'C' to Items.COPPER_INGOT
            'W' to SILICON_WAFER
            unlockBy("has_wafer", SILICON_WAFER)
        }

        // RTC module
        shaped(RTC_MODULE) {
            rows(" E ", "EOE", " R ")
            'E' to ELECTRONIC_PARTS
            'O' to CRYSTAL_OSCILLATOR
            'R' to Items.REDSTONE
            unlockBy("has_oscillator", CRYSTAL_OSCILLATOR)
        }

        // Memory chip (×2)
        shaped(MEMORY_CHIP, count = 2) {
            rows("NWN", "RWR", "NWN")
            'N' to Items.GOLD_NUGGET
            'W' to SILICON_WAFER
            'R' to Items.REDSTONE
            unlockBy("has_wafer", SILICON_WAFER)
        }

        // ------------------------------------------------------------------
        // Storage lineage
        // ------------------------------------------------------------------

        // Flash chip — shapeless: wafer + nugget + PCB
        shapeless(FLASH_CHIP) {
            +SILICON_WAFER
            +Items.GOLD_NUGGET
            +PCB_BASE
            unlockBy("has_wafer", SILICON_WAFER)
        }

        // IDE HDD
        shaped(HDD) {
            rows("IRI", "ICI", "IDI")
            'I' to Items.IRON_INGOT
            'R' to Items.REDSTONE
            'C' to Items.COPPER_INGOT
            'D' to DSUB_CONNECTOR
            unlockBy("has_dsub", DSUB_CONNECTOR)
        }

        // NVMe SSD
        shaped(NVME) {
            rows("MPM", "NSN")
            'M' to MEMORY_CHIP
            'P' to PCB_BASE
            'N' to Items.GOLD_NUGGET
            'S' to SOLDERING_IRON
            unlockBy(MEMORY_CHIP)
        }

        // ------------------------------------------------------------------
        // Compute lineage (CPUs, SoCs, RAM)
        // ------------------------------------------------------------------

        // CPU tier 1
        shaped(CPU1) {
            rows("NWN", "WOW", "NWN")
            'N' to Items.GOLD_NUGGET
            'W' to SILICON_WAFER
            'O' to CRYSTAL_OSCILLATOR
            unlockBy("has_wafer", SILICON_WAFER)
        }

        // CPU tier 2
        shaped(CPU2) {
            rows("NON", "DCD", "NON")
            'N' to Items.GOLD_INGOT
            'O' to CRYSTAL_OSCILLATOR
            'D' to Items.DIAMOND
            'C' to CPU1
            unlockBy(CPU1)
        }

        // CPU tier 3
        shaped(CPU3) {
            rows("EOE", "NCN", "EOE")
            'E' to Items.EMERALD
            'O' to CRYSTAL_OSCILLATOR
            'N' to Items.NETHERITE_INGOT
            'C' to CPU2
            unlockBy(CPU2)
        }

        // SoC tier 1
        shaped(SOC1) {
            rows("NWN", "WEW")
            'N' to Items.GOLD_NUGGET
            'W' to SILICON_WAFER
            'E' to ELECTRONIC_PARTS
            unlockBy("has_wafer", SILICON_WAFER)
        }

        // SoC tier 2
        shaped(SOC2) {
            rows("MOM", "NSN")
            'M' to MEMORY_CHIP
            'O' to CRYSTAL_OSCILLATOR
            'N' to Items.GOLD_NUGGET
            'S' to SOC1
            unlockBy(SOC1)
        }

        // SoC tier 3
        shaped(SOC3) {
            rows("MDM", "DSD", "MDM")
            'M' to MEMORY_CHIP
            'D' to Items.DIAMOND
            'S' to SOC2
            unlockBy(SOC2)
        }

        // RAM sticks (5 tiers, same shape, varying substrate)
        ramRecipe(RAM_SODIMM1, Items.IRON_NUGGET)
        ramRecipe(RAM_SODIMM2, Items.GOLD_NUGGET)
        ramRecipe(RAM_SODIMM3, Items.GOLD_INGOT)
        ramRecipe(RAM_SODIMM4, Items.DIAMOND)
        ramRecipe(RAM_SODIMM5, Items.NETHERITE_INGOT)

        // Motherboard tier 2
        shaped(MOTHERBOARD2) {
            rows("DMD", "VBS", "EPE")
            'D' to DSUB_CONNECTOR
            'M' to MEMORY_CHIP
            'V' to VOLTAGE_REGULATOR
            'B' to MOTHERBOARD1
            'S' to SOLDERING_IRON
            'E' to ELECTRONIC_PARTS
            'P' to PCB_BASE
            unlockBy("has_motherboard1", MOTHERBOARD1)
        }

        // Motherboard tier 3
        shaped(MOTHERBOARD3) {
            rows("DND", "VBS", "EPE")
            'D' to Items.DIAMOND
            'N' to Items.NETHERITE_INGOT
            'V' to VOLTAGE_REGULATOR
            'B' to MOTHERBOARD2
            'S' to SOLDERING_IRON
            'E' to ELECTRONIC_PARTS
            'P' to PCB_BASE
            unlockBy("has_motherboard2", MOTHERBOARD2)
        }

        // ------------------------------------------------------------------
        // PCI expansion cards — same shape, different "function" component.
        // ------------------------------------------------------------------
        pciCard(VGA_CARD,   GFX_DISPLAY)
        pciCard(RTL8169,    Items.ENDER_PEARL)
        pciCard(SOUND_CARD, Items.NOTE_BLOCK)

        // GPIO uses a redstone-heavy shape because it's the redstone-bridge card.
        shaped(GPIO_CARD) {
            rows("RDR", "PWP", "RDR")
            'R' to Items.REDSTONE
            'D' to Items.REDSTONE_TORCH
            'P' to PCB_BASE
            'W' to SILICON_WAFER
            unlockBy("has_wafer", SILICON_WAFER)
        }

        // ------------------------------------------------------------------
        // Cases & peripherals (blocks placed in the world)
        // ------------------------------------------------------------------

        // Workstation case
        shaped(WORKSTATION, category = DECORATIONS) {
            rows("ICI", "DPD", "IDI")
            'I' to Items.IRON_INGOT
            'C' to CHAR_DISPLAY
            'D' to DSUB_CONNECTOR
            'P' to PCB_BASE
            unlockBy("has_pcb", PCB_BASE)
        }

        // Powermark — upgraded workstation
        shaped(POWERMARK, category = DECORATIONS) {
            rows("NCN", "DWD", "NDN")
            'N' to Items.NETHERITE_SCRAP
            'C' to CHAR_DISPLAY
            'D' to DSUB_CONNECTOR
            'W' to WORKSTATION
            unlockBy(WORKSTATION)
        }

        // Tinkerpad — laptop chassis
        shaped(TINKERPAD, category = DECORATIONS) {
            rows("FGF", "KMK", "FPF")
            'F' to FIBERGLASS
            'G' to GFX_DISPLAY
            'K' to KEYBOARD
            'M' to MOTHERBOARD1
            'P' to PCB_BASE
            unlockBy("has_motherboard1", MOTHERBOARD1)
        }

        // VT100 terminal — original 80×24 monochrome.
        shaped(VT100, category = DECORATIONS) {
            rows("FCF", "GDG", "FPF")
            'F' to FIBERGLASS
            'C' to CHAR_DISPLAY
            'G' to Blocks.GLASS_PANE
            'D' to DSUB_CONNECTOR
            'P' to PCB_BASE
            unlockBy(CHAR_DISPLAY)
        }

        // VT220 — VT100 + extra DSUB connector for the soft-font /
        // selective-erase upgrade. Cooler-gray case.
        shaped(VT220, category = DECORATIONS) {
            rows("FCF", "DDG", "FPF")
            'F' to FIBERGLASS
            'C' to CHAR_DISPLAY
            'G' to Blocks.GLASS_PANE
            'D' to DSUB_CONNECTOR
            'P' to PCB_BASE
            unlockBy(CHAR_DISPLAY)
        }

        // VT340 — needs the GFX display (sixel + ReGIS graphics planes).
        // Platinum-gray case; the "real" terminal for retrocomputing.
        shaped(VT340, category = DECORATIONS) {
            rows("FXF", "DDG", "FPF")
            'F' to FIBERGLASS
            'X' to GFX_DISPLAY
            'D' to DSUB_CONNECTOR
            'G' to Blocks.GLASS_PANE
            'P' to PCB_BASE
            unlockBy(GFX_DISPLAY)
        }

        // VT420 — VT220 era progression: char display + tier-2 motherboard
        // for the multi-session firmware. Industrial dark gray.
        shaped(VT420, category = DECORATIONS) {
            rows("FCF", "DMG", "FPF")
            'F' to FIBERGLASS
            'C' to CHAR_DISPLAY
            'D' to DSUB_CONNECTOR
            'M' to MOTHERBOARD2
            'G' to Blocks.GLASS_PANE
            'P' to PCB_BASE
            unlockBy(MOTHERBOARD2)
        }

        // VT520 — last DEC, the "future-proof" upgrade: GFX display +
        // tier-2 motherboard. Charcoal case.
        shaped(VT520, category = DECORATIONS) {
            rows("FXF", "DMG", "FPF")
            'F' to FIBERGLASS
            'X' to GFX_DISPLAY
            'D' to DSUB_CONNECTOR
            'M' to MOTHERBOARD2
            'G' to Blocks.GLASS_PANE
            'P' to PCB_BASE
            unlockBy(MOTHERBOARD2)
        }

        // ------------------------------------------------------------------
        // Ink / ribbon production chain — crafting recipes
        // ------------------------------------------------------------------

        // Paper roll for the teletype: shapeless from vanilla paper. A
        // single sheet wound onto a spool; trades stack-size for the
        // damage-bar lines counter that the teletype decrements.
        shapeless(PAPER_ROLL, category = MISC) {
            ingredient(Items.PAPER)
            unlockBy(Items.PAPER)
        }

        // Ink mixer: glass bottle + cauldron + electronic parts.
        shaped(INK_MIXER, category = DECORATIONS) {
            rows("FBF", "ICI", "FPF")
            'F' to FIBERGLASS
            'B' to Items.GLASS_BOTTLE
            'I' to Items.IRON_INGOT
            'C' to Items.CAULDRON
            'P' to PCB_BASE
            unlockBy(Items.GLASS_BOTTLE)
        }

        // Ribbon impregnator: loom + iron + electronics. Loom is the
        // closest vanilla item to "weaves cloth onto a spool."
        shaped(RIBBON_IMPREGNATOR, category = DECORATIONS) {
            rows("FIF", "ILI", "FPF")
            'F' to FIBERGLASS
            'I' to Items.IRON_INGOT
            'L' to Items.LOOM
            'P' to PCB_BASE
            unlockBy(Items.LOOM)
        }

        // Teletype: ASR-33 homage — iron frame, char-display "platen,"
        // PCB controller. Loaded with a paper roll + ribbon to print.
        shaped(TELETYPE, category = DECORATIONS) {
            rows("FIF", "ICI", "FPF")
            'F' to FIBERGLASS
            'I' to Items.IRON_INGOT
            'C' to CHAR_DISPLAY
            'P' to PCB_BASE
            unlockBy(CHAR_DISPLAY)
        }

        // Pigment from any tagged source — charcoal, dyes, lapis,
        // redstone. Tag-driven so mod compat doesn't need source patches.
        shapeless(PIGMENT, category = MISC) {
            ingredient(net.minecraft.tags.ItemTags.create(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("scev", "pigment_source")))
            unlockBy(Items.CHARCOAL)
        }

        // Binder from any tagged source (honey / slime / etc).
        shapeless(BINDER, category = MISC) {
            ingredient(net.minecraft.tags.ItemTags.create(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("scev", "binder")))
            unlockBy(Items.HONEY_BOTTLE)
        }

        // Expansion cards — each card is a PCB + the kind-specific
        // active component. Same shape across cards keeps the recipe
        // book from looking cluttered.
        shaped(SERIAL_PORT_CARD, category = MISC) {
            rows("PDP", "PCP", "P P")
            'P' to PCB_BASE
            'D' to DSUB_CONNECTOR
            'C' to ELECTRONIC_PARTS
            unlockBy(DSUB_CONNECTOR)
        }
        shaped(I2C_CARD, category = MISC) {
            rows("PRP", "PCP", "P P")
            'P' to PCB_BASE
            'R' to Items.REPEATER
            'C' to ELECTRONIC_PARTS
            unlockBy(Items.REPEATER)
        }
        shaped(RTC_CARD, category = MISC) {
            rows("PTP", "PRP", "P P")
            'P' to PCB_BASE
            'T' to RTC_MODULE
            'R' to CRYSTAL_OSCILLATOR
            unlockBy(RTC_MODULE)
        }
        shaped(GPIO_EXPANSION_CARD, category = MISC) {
            rows("PRP", "PCP", "P P")
            'P' to PCB_BASE
            'R' to Items.REDSTONE
            'C' to ELECTRONIC_PARTS
            unlockBy(Items.REDSTONE)
        }

        // CRT monitor
        shaped(CRT_MONITOR, category = DECORATIONS) {
            rows("FXF", "GDG", "FPF")
            'F' to FIBERGLASS
            'X' to GFX_DISPLAY
            'G' to Blocks.GLASS_PANE
            'D' to DSUB_CONNECTOR
            'P' to PCB_BASE
            unlockBy(GFX_DISPLAY)
        }

        // Keyboard
        shaped(KEYBOARD, category = DECORATIONS) {
            rows("NNN", "PPD")
            'N' to Items.IRON_NUGGET
            'P' to PCB_BASE
            'D' to DSUB_CONNECTOR
            unlockBy("has_pcb", PCB_BASE)
        }

        // Keyboard+mouse — shapeless augment
        shapeless(KEYBOARD_MOUSE, category = DECORATIONS) {
            +KEYBOARD
            +Items.SLIME_BALL
            +Items.REDSTONE
            unlockBy(KEYBOARD)
        }

        // MCU board
        shaped(MCU_BOARD, category = DECORATIONS) {
            rows("NDN", "EPE", " S ")
            'N' to Items.IRON_NUGGET
            'D' to DSUB_CONNECTOR
            'E' to ELECTRONIC_PARTS
            'P' to PCB_BASE
            'S' to SOLDERING_IRON
            unlockBy("has_pcb", PCB_BASE)
        }
    } }

    /** Shared RAM recipe shape: memory chips on a PCB with substrate framing. */
    private fun RecipesScope.ramRecipe(result: ItemLike, substrate: ItemLike) = with(R) {
        shaped(result) {
            rows("SMS", "MPM", "SMS")
            'S' to substrate
            'M' to MEMORY_CHIP
            'P' to PCB_BASE
            unlockBy(MEMORY_CHIP)
        }
    }

    /**
     * Shared PCI expansion card shape: function card varies by center slot.
     * Trigger is "has_pcb" (not the auto-derived "has_pcb_base") to match
     * the existing advancement JSON.
     */
    private fun RecipesScope.pciCard(result: ItemLike, function: ItemLike) = with(R) {
        shaped(result) {
            rows("EDE", "PFP", "NNN")
            'E' to ELECTRONIC_PARTS
            'D' to DSUB_CONNECTOR
            'P' to PCB_BASE
            'F' to function
            'N' to Items.GOLD_NUGGET
            unlockBy("has_pcb", PCB_BASE)
        }
    }
}
