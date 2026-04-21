/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.datagen;

import lekkit.scev.blocks.DirectionalBlock;
import lekkit.scev.main.ScalarEvolution;
import lekkit.scev.main.ScevRegistry;
import net.minecraft.core.Direction;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.client.model.generators.BlockModelBuilder;
import net.neoforged.neoforge.client.model.generators.BlockStateProvider;
import net.neoforged.neoforge.client.model.generators.ConfiguredModel;
import net.neoforged.neoforge.client.model.generators.loaders.ObjModelBuilder;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import net.neoforged.neoforge.registries.DeferredBlock;

/**
 * Generates blockstates + block-model JSONs.
 *
 * <p>All seven modded blocks reference OBJ meshes via the {@code neoforge:obj}
 * loader. The OBJ files are pre-transformed into Minecraft block space
 * (vertices in [0, 1]) — see {@code scripts/preprocess-obj.sh}.
 *
 * <p>We use NeoForge's official {@link ObjModelBuilder} rather than a
 * handcrafted {@code CustomLoaderBuilder} to guarantee the generated JSON
 * shape matches exactly what {@code ObjLoader#read} expects. The builder
 * also verifies the OBJ file exists in known resource packs at datagen time
 * via {@code existingFileHelper.exists(...)} — catching typo/missing-file
 * bugs early.
 */
public class ScevBlockStateProvider extends BlockStateProvider {
    public ScevBlockStateProvider(PackOutput output, ExistingFileHelper helper) {
        super(output, ScalarEvolution.MODID, helper);
    }

    @Override
    protected void registerStatesAndModels() {
        objBlock(ScevRegistry.WORKSTATION,     "workstation");
        objBlock(ScevRegistry.POWERMARK,       "powermark");
        objBlock(ScevRegistry.TINKERPAD,       "tinkerpad");
        objBlock(ScevRegistry.VT100,           "vt100");
        objBlock(ScevRegistry.CRT_MONITOR,     "crt_monitor");
        objBlock(ScevRegistry.KEYBOARD,        "keyboard");
        objBlock(ScevRegistry.KEYBOARD_MOUSE,  "keyboard_mouse");
        // MCU board uses a plain JSON "orientable" cube — front, side, top
        // textures with automatic rotation based on HORIZONTAL_FACING. No
        // OBJ mesh because the block is a simple box; matches OC1's
        // Microcontroller shape.
        orientableBlock(ScevRegistry.MCU_BOARD, "mcu_board");
    }

    /**
     * Standard 6-face cube with three textures: front (the named face),
     * side (used for left/right/back), and top (used for top/bottom). The
     * vanilla {@code orientable} parent + {@code forAllStates} wiring
     * rotates the model around Y to track the {@link DirectionalBlock#FACING}
     * blockstate property.
     */
    private void orientableBlock(DeferredBlock<? extends DirectionalBlock> deferred, String name) {
        ResourceLocation front = ScalarEvolution.rl("block/" + name + "_front");
        ResourceLocation side  = ScalarEvolution.rl("block/" + name + "_side");
        ResourceLocation top   = ScalarEvolution.rl("block/" + name + "_top");

        BlockModelBuilder model = models().orientable(name, side, front, top);

        Block block = deferred.get();
        getVariantBuilder(block).forAllStates(state -> {
            Direction facing = state.getValue(DirectionalBlock.FACING);
            int yRot = switch (facing) {
                case SOUTH -> 180;
                case WEST  -> 270;
                case EAST  -> 90;
                default    -> 0;
            };
            return ConfiguredModel.builder()
                    .modelFile(model)
                    .rotationY(yRot)
                    .build();
        });
        simpleBlockItem(block, model);
    }

    private void objBlock(DeferredBlock<? extends DirectionalBlock> deferred, String name) {
        ResourceLocation texture  = ScalarEvolution.rl("block/" + name);
        ResourceLocation particle = texture;
        ResourceLocation modelRL  = ScalarEvolution.rl("models/block/" + name + ".obj");

        // The `texture` slot is what the shared default.mtl's `map_Kd #texture`
        // resolves to at bake time. Without that slot (or without a `usemtl`
        // scope inside the OBJ), NeoForge's `ObjModel` silently drops every
        // face (ObjModel.java:541-543 — `if (mat == null) return;`) and the
        // block renders as invisible geometry. See docs/GOTCHAS.md.
BlockModelBuilder model = models().getBuilder(name)
                .texture("texture",  texture)
                .texture("particle", particle);
        // Disable automatic culling: the OBJ geometry isn't aligned to block
        // faces (it's a desk / monitor / keyboard sitting inside the block
        // volume, not a full cube). Leaving automatic_culling=true silently
        // culls any face whose four vertices happen to lie on a block
        // boundary with outward normals — which bites particularly hard when
        // adjacent blocks are placed.
        model.customLoader(ObjModelBuilder::begin)
                .modelLocation(modelRL)
                .flipV(true)
                .automaticCulling(false)
                .end();

        Block block = deferred.get();
        getVariantBuilder(block).forAllStates(state -> {
            Direction facing = state.getValue(DirectionalBlock.FACING);
            int yRot = switch (facing) {
                case SOUTH -> 180;
                case WEST  -> 270;
                case EAST  -> 90;
                default    -> 0;
            };
            return ConfiguredModel.builder()
                    .modelFile(model)
                    .rotationY(yRot)
                    .build();
        });
        // Inventory item model mirrors the block model.
        simpleBlockItem(block, model);
    }
}
