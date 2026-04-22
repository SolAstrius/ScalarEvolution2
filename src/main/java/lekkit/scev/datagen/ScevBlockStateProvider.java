/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.datagen;

import lekkit.scev.blocks.CableBlock;
import lekkit.scev.blocks.DirectionalBlock;
import lekkit.scev.main.ScalarEvolution;
import lekkit.scev.main.ScevRegistry;
import net.minecraft.core.Direction;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.neoforged.neoforge.client.model.generators.BlockModelBuilder;
import net.neoforged.neoforge.client.model.generators.BlockStateProvider;
import net.neoforged.neoforge.client.model.generators.ConfiguredModel;
import net.neoforged.neoforge.client.model.generators.MultiPartBlockStateBuilder;
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

        // Cable multipart: central 4×4×4 core + one arm per connected
        // neighbour. The 6 BooleanProperty values on CableBlock drive a
        // multipart blockstate; each arm only renders when its direction
        // is connected. Center-cube bounds (6..10 on each axis) mirror
        // OC1's cable bounds so the visual density looks similar.
        cableMultipart();

        // Flash programmer: placeholder single-texture cube. Will be
        // re-skinned once proper block art is finished.
        simpleCubeBlock(ScevRegistry.FLASH_PROGRAMMER, "flash_programmer");
    }

    /**
     * Build the cable's multipart model: one always-visible core model,
     * plus six arm models each gated on a direction property. The item
     * model uses a plain full-cube so the creative tab icon is solid.
     */
    private void cableMultipart() {
        ResourceLocation tex = ScalarEvolution.rl("block/cable");
        ResourceLocation cap = ScalarEvolution.rl("block/cable_cap");

        BlockModelBuilder core = models().getBuilder("cable_core")
                .texture("particle", tex)
                .texture("0", tex)
                .element()
                    .from(6, 6, 6).to(10, 10, 10)
                    .face(Direction.DOWN).texture("#0").cullface(null).end()
                    .face(Direction.UP).texture("#0").cullface(null).end()
                    .face(Direction.NORTH).texture("#0").cullface(null).end()
                    .face(Direction.SOUTH).texture("#0").cullface(null).end()
                    .face(Direction.WEST).texture("#0").cullface(null).end()
                    .face(Direction.EAST).texture("#0").cullface(null).end()
                .end();

        MultiPartBlockStateBuilder mpb = getMultipartBuilder(ScevRegistry.CABLE.get());
        mpb.part().modelFile(core).addModel().end();
        for (Direction d : Direction.values()) {
            BlockModelBuilder arm = cableArmModel(d, tex, cap);
            BooleanProperty prop = lekkit.scev.blocks.CableBlock.propertyFor(d);
            mpb.part().modelFile(arm).addModel().condition(prop, true).end();
        }

        // Inventory icon — a simple full-cube using the cable texture.
        models().cubeAll("cable_inventory", tex);
        itemModels().withExistingParent("cable", ScalarEvolution.rl("block/cable_inventory"));
    }

    /**
     * Build one arm model — a rectangular prism from the core's face
     * (at 6 or 10 on the relevant axis) to the block boundary (0 or 16).
     * The outer face uses {@code cable_cap} so the arm looks "plugged in"
     * at its far end; the four side faces use the main cable texture.
     */
    private BlockModelBuilder cableArmModel(Direction d, ResourceLocation side, ResourceLocation cap) {
        int x0 = 6, y0 = 6, z0 = 6;
        int x1 = 10, y1 = 10, z1 = 10;
        Direction outer;
        switch (d) {
            case DOWN  -> { y0 = 0;  outer = Direction.DOWN; }
            case UP    -> { y1 = 16; outer = Direction.UP; }
            case NORTH -> { z0 = 0;  outer = Direction.NORTH; }
            case SOUTH -> { z1 = 16; outer = Direction.SOUTH; }
            case WEST  -> { x0 = 0;  outer = Direction.WEST; }
            case EAST  -> { x1 = 16; outer = Direction.EAST; }
            default -> throw new IllegalArgumentException("bad direction: " + d);
        }
        BlockModelBuilder model = models().getBuilder("cable_arm_" + d.getSerializedName())
                .texture("particle", side)
                .texture("0", side)
                .texture("1", cap);

        var el = model.element().from(x0, y0, z0).to(x1, y1, z1);
        for (Direction face : Direction.values()) {
            // End-face gets the cap texture + cullface (it sits flush against
            // the neighbouring block and should be culled by its own occlusion).
            String texSlot = face == outer ? "#1" : "#0";
            Direction cull = face == outer ? outer : null;
            el.face(face).texture(texSlot).cullface(cull).end();
        }
        el.end();
        return model;
    }

    /** Registers a one-texture-per-face cube block + matching item model. */
    private void simpleCubeBlock(DeferredBlock<?> deferred, String name) {
        ResourceLocation tex = ScalarEvolution.rl("block/" + name);
        BlockModelBuilder model = models().cubeAll(name, tex);
        Block block = deferred.get();
        simpleBlock(block, model);
        simpleBlockItem(block, model);
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
