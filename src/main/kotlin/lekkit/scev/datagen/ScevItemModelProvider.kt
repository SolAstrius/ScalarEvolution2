/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.datagen

import lekkit.scev.main.ScalarEvolution
import lekkit.scev.main.ScevRegistry
import net.minecraft.data.PackOutput
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.Item
import net.neoforged.neoforge.client.model.generators.ItemModelProvider
import net.neoforged.neoforge.common.data.ExistingFileHelper
import net.neoforged.neoforge.registries.DeferredItem

class ScevItemModelProvider(
    output: PackOutput,
    helper: ExistingFileHelper,
) : ItemModelProvider(output, ScalarEvolution.MODID, helper) {

    override fun registerModels() {
        // Plain item/generated models for all material items
        val items: List<DeferredItem<out Item>> = listOf(
            ScevRegistry.EPOXY, ScevRegistry.SILICA_COMPOUND, ScevRegistry.MOLD_COMPOUND,
            ScevRegistry.FIBERGLASS, ScevRegistry.SILICON_WAFER, ScevRegistry.PCB_BASE,
            ScevRegistry.DSUB_CONNECTOR, ScevRegistry.CRYSTAL_OSCILLATOR,
            ScevRegistry.ELECTRONIC_PARTS, ScevRegistry.VOLTAGE_REGULATOR,
            ScevRegistry.RTC_MODULE, ScevRegistry.MEMORY_CHIP, ScevRegistry.CHAR_DISPLAY,
            ScevRegistry.GFX_DISPLAY,
            ScevRegistry.SOLDERING_IRON,
            ScevRegistry.CPU1, ScevRegistry.CPU2, ScevRegistry.CPU3,
            ScevRegistry.SOC1, ScevRegistry.SOC2, ScevRegistry.SOC3,
            ScevRegistry.RAM_SODIMM1, ScevRegistry.RAM_SODIMM2,
            ScevRegistry.RAM_SODIMM3, ScevRegistry.RAM_SODIMM4,
            ScevRegistry.RAM_SODIMM5,
            ScevRegistry.FLASH_CHIP, ScevRegistry.HDD, ScevRegistry.NVME,
            ScevRegistry.VGA_CARD, ScevRegistry.GPIO_CARD,
            ScevRegistry.SOUND_CARD, ScevRegistry.RTL8169,
            ScevRegistry.MOTHERBOARD1, ScevRegistry.MOTHERBOARD2, ScevRegistry.MOTHERBOARD3,
        )
        for (item in items) {
            basicItem(item.get())
        }

        // NVME_PRELOADED reuses the nvme sprite. The preloaded variant is the
        // same physical NVMe SSD with different disk contents; visually
        // identical. We generate a model JSON that points at the shared
        // scev:item/nvme texture rather than a duplicate PNG — keeps the jar
        // a few KB lighter and makes any future sprite change automatically
        // flow through.
        singleTexture(
            ScevRegistry.NVME_PRELOADED.id.path,
            ResourceLocation.withDefaultNamespace("item/generated"),
            "layer0",
            ResourceLocation.fromNamespaceAndPath(ScalarEvolution.MODID, "item/nvme"))

        // Paper / ink / ribbon chain + expansion cards — placeholder
        // models pointing at vanilla MC item textures. Replaces with
        // hand-painted PNGs later; the model JSON paths stay scev:
        // namespace so swapping is just dropping a PNG into our
        // textures dir + re-running datagen.
        vanillaTextured(ScevRegistry.PULP_SLURRY,        "minecraft:item/sugar")
        vanillaTextured(ScevRegistry.WET_PAPER_SHEET,    "minecraft:item/paper")
        vanillaTextured(ScevRegistry.PAPER_SHEET,        "minecraft:item/paper")
        vanillaTextured(ScevRegistry.BINDER,             "minecraft:item/honey_bottle")
        vanillaTextured(ScevRegistry.PAPER_ROLL,         "minecraft:item/paper")
        vanillaTextured(ScevRegistry.PIGMENT,            "minecraft:item/black_dye")
        vanillaTextured(ScevRegistry.INK_JAR,            "minecraft:item/ink_sac")
        vanillaTextured(ScevRegistry.RIBBON,             "minecraft:item/string")
        // Expansion cards use existing scev sprites that read like
        // their function:
        //   - Serial card → DB9 D-sub connector (literal serial port)
        //   - RTC card    → RTC module sprite (already exists)
        //   - GPIO card   → existing PCI-style gpio_card sprite
        //   - I2C card    → bare PCB as placeholder until a dedicated
        //                   sprite exists; reads as "small board" at
        //                   slot scale
        vanillaTextured(ScevRegistry.SERIAL_PORT_CARD,    "scev:item/dsub_connector")
        vanillaTextured(ScevRegistry.I2C_CARD,            "scev:item/pcb_base")
        vanillaTextured(ScevRegistry.RTC_CARD,            "scev:item/rtc_module")
        vanillaTextured(ScevRegistry.GPIO_EXPANSION_CARD, "scev:item/gpio_card")
    }

    /** Generate a `minecraft:item/generated` model that uses a vanilla
     *  MC texture. Used for placeholder visuals before hand-painted
     *  art lands. */
    private fun vanillaTextured(item: DeferredItem<out Item>, texturePath: String) {
        singleTexture(
            item.id.path,
            ResourceLocation.withDefaultNamespace("item/generated"),
            "layer0",
            ResourceLocation.parse(texturePath))
    }
}
