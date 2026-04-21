/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.datagen;

import lekkit.scev.main.ScalarEvolution;
import lekkit.scev.main.ScevRegistry;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.client.model.generators.ItemModelProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import net.neoforged.neoforge.registries.DeferredItem;

public class ScevItemModelProvider extends ItemModelProvider {
    public ScevItemModelProvider(PackOutput output, ExistingFileHelper helper) {
        super(output, ScalarEvolution.MODID, helper);
    }

    @Override
    protected void registerModels() {
        // Plain item/generated models for all material items
        for (DeferredItem<? extends Item> item : new DeferredItem[] {
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
                ScevRegistry.FLASH_CHIP, ScevRegistry.HDD, ScevRegistry.NVME,
                ScevRegistry.VGA_CARD, ScevRegistry.GPIO_CARD,
                ScevRegistry.SOUND_CARD, ScevRegistry.RTL8169,
                ScevRegistry.MOTHERBOARD1, ScevRegistry.MOTHERBOARD2, ScevRegistry.MOTHERBOARD3
        }) {
            String name = item.getId().getPath();
            basicItem(item.get());
        }

        // NVME_PRELOADED reuses the nvme sprite. The preloaded variant is the
        // same physical NVMe SSD with different disk contents; visually
        // identical. We generate a model JSON that points at the shared
        // scev:item/nvme texture rather than a duplicate PNG — keeps the jar
        // a few KB lighter and makes any future sprite change automatically
        // flow through.
        singleTexture(
                ScevRegistry.NVME_PRELOADED.getId().getPath(),
                ResourceLocation.withDefaultNamespace("item/generated"),
                "layer0",
                ResourceLocation.fromNamespaceAndPath(ScalarEvolution.MODID, "item/nvme"));

    }
}
