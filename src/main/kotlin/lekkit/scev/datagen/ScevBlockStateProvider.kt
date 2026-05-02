/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.datagen

import lekkit.scev.blocks.CableBlock
import lekkit.scev.blocks.DirectionalBlock
import lekkit.scev.main.ScalarEvolution
import lekkit.scev.main.ScevRegistry
import net.minecraft.core.Direction
import net.minecraft.data.PackOutput
import net.minecraft.resources.ResourceLocation
import net.neoforged.neoforge.client.model.generators.BlockModelBuilder
import net.neoforged.neoforge.client.model.generators.BlockStateProvider
import net.neoforged.neoforge.client.model.generators.ConfiguredModel
import net.neoforged.neoforge.client.model.generators.loaders.ObjModelBuilder
import net.neoforged.neoforge.common.data.ExistingFileHelper
import net.neoforged.neoforge.registries.DeferredBlock

/**
 * Generates blockstates + block-model JSONs.
 *
 * All seven modded blocks reference OBJ meshes via the
 * `neoforge:obj` loader. The OBJ files are pre-transformed into
 * Minecraft block space (vertices in [0, 1]) — see
 * `scripts/preprocess-obj.sh`.
 *
 * We use NeoForge's official [ObjModelBuilder] rather than a
 * handcrafted `CustomLoaderBuilder` to guarantee the generated JSON
 * shape matches exactly what `ObjLoader#read` expects. The builder
 * also verifies the OBJ file exists in known resource packs at datagen
 * time via `existingFileHelper.exists(...)` — catching
 * typo/missing-file bugs early.
 */
class ScevBlockStateProvider(
    output: PackOutput,
    helper: ExistingFileHelper,
) : BlockStateProvider(output, ScalarEvolution.MODID, helper) {

    override fun registerStatesAndModels() {
        objBlock(ScevRegistry.WORKSTATION,     "workstation")
        objBlock(ScevRegistry.POWERMARK,       "powermark")
        objBlock(ScevRegistry.TINKERPAD,       "tinkerpad")
        objBlock(ScevRegistry.VT100,           "vt100")
        // Variants share the vt100 OBJ geometry; only the texture
        // differs. The texture name matches the block's registry id.
        objBlock(ScevRegistry.VT220,           "vt220", modelName = "vt100")
        objBlock(ScevRegistry.VT340,           "vt340", modelName = "vt100")
        objBlock(ScevRegistry.VT420,           "vt420", modelName = "vt100")
        objBlock(ScevRegistry.VT520,           "vt520", modelName = "vt100")
        objBlock(ScevRegistry.CRT_MONITOR,     "crt_monitor")
        objBlock(ScevRegistry.KEYBOARD,        "keyboard")
        objBlock(ScevRegistry.KEYBOARD_MOUSE,  "keyboard_mouse")
        // MCU board uses a plain JSON "orientable" cube — front, side, top
        // textures with automatic rotation based on HORIZONTAL_FACING. No
        // OBJ mesh because the block is a simple box; matches OC1's
        // Microcontroller shape.
        orientableBlock(ScevRegistry.MCU_BOARD, "mcu_board")

        // Cable multipart: central 4×4×4 core + one arm per connected
        // neighbour. The 6 BooleanProperty values on CableBlock drive a
        // multipart blockstate; each arm only renders when its direction
        // is connected. Center-cube bounds (6..10 on each axis) mirror
        // OC1's cable bounds so the visual density looks similar.
        cableMultipart()

        // Flash programmer: placeholder single-texture cube. Will be
        // re-skinned once proper block art is finished.
        simpleCubeBlock(ScevRegistry.FLASH_PROGRAMMER, "flash_programmer")

        // Pulper — placeholder PIL-generated drum texture. The block
        // is symmetric (no front face), so the cube_all / simpleBlock
        // pattern fits. When the other processing machines land
        // (SheetFormer, Dryer, Winder, InkMixer, RibbonImpregnator)
        // they each get their own simpleCubeBlock entry with the
        // matching pre-generated texture.
        simpleCubeBlock(ScevRegistry.PULPER,             "pulper")
        simpleCubeBlock(ScevRegistry.SHEET_FORMER,       "sheet_former")
        simpleCubeBlock(ScevRegistry.DRYER,              "dryer")
        simpleCubeBlock(ScevRegistry.WINDER,             "winder")
        simpleCubeBlock(ScevRegistry.INK_MIXER,          "ink_mixer")
        simpleCubeBlock(ScevRegistry.RIBBON_IMPREGNATOR, "ribbon_impregnator")
        simpleCubeBlock(ScevRegistry.TELETYPE,           "teletype")
    }

    /**
     * Build the cable's multipart model: one always-visible core
     * model, plus six arm models each gated on a direction property.
     * The item model uses a plain full-cube so the creative tab icon
     * is solid.
     */
    private fun cableMultipart() {
        val tex = ScalarEvolution.rl("block/cable")
        val cap = ScalarEvolution.rl("block/cable_cap")

        val core = models().getBuilder("cable_core")
            .texture("particle", tex)
            .texture("0", tex)
            .element()
                .from(6f, 6f, 6f).to(10f, 10f, 10f)
                .face(Direction.DOWN).texture("#0").cullface(null).end()
                .face(Direction.UP).texture("#0").cullface(null).end()
                .face(Direction.NORTH).texture("#0").cullface(null).end()
                .face(Direction.SOUTH).texture("#0").cullface(null).end()
                .face(Direction.WEST).texture("#0").cullface(null).end()
                .face(Direction.EAST).texture("#0").cullface(null).end()
            .end()

        val mpb = getMultipartBuilder(ScevRegistry.CABLE.get())
        mpb.part().modelFile(core).addModel().end()
        for (d in Direction.values()) {
            val arm = cableArmModel(d, tex, cap)
            val prop = CableBlock.propertyFor(d)
            mpb.part().modelFile(arm).addModel().condition(prop, true).end()
        }

        // Inventory icon — a simple full-cube using the cable texture.
        models().cubeAll("cable_inventory", tex)
        itemModels().withExistingParent("cable", ScalarEvolution.rl("block/cable_inventory"))
    }

    /**
     * Build one arm model — a rectangular prism from the core's face
     * (at 6 or 10 on the relevant axis) to the block boundary (0 or
     * 16). The outer face uses `cable_cap` so the arm looks "plugged
     * in" at its far end; the four side faces use the main cable
     * texture.
     */
    private fun cableArmModel(d: Direction, side: ResourceLocation, cap: ResourceLocation): BlockModelBuilder {
        var x0 = 6; var y0 = 6; var z0 = 6
        var x1 = 10; var y1 = 10; var z1 = 10
        val outer: Direction = when (d) {
            Direction.DOWN  -> { y0 = 0;  Direction.DOWN }
            Direction.UP    -> { y1 = 16; Direction.UP }
            Direction.NORTH -> { z0 = 0;  Direction.NORTH }
            Direction.SOUTH -> { z1 = 16; Direction.SOUTH }
            Direction.WEST  -> { x0 = 0;  Direction.WEST }
            Direction.EAST  -> { x1 = 16; Direction.EAST }
        }
        val model = models().getBuilder("cable_arm_" + d.serializedName)
            .texture("particle", side)
            .texture("0", side)
            .texture("1", cap)

        val el = model.element().from(x0.toFloat(), y0.toFloat(), z0.toFloat())
            .to(x1.toFloat(), y1.toFloat(), z1.toFloat())
        for (face in Direction.values()) {
            // End-face gets the cap texture + cullface (it sits flush against
            // the neighbouring block and should be culled by its own occlusion).
            val texSlot = if (face == outer) "#1" else "#0"
            val cull: Direction? = if (face == outer) outer else null
            el.face(face).texture(texSlot).cullface(cull).end()
        }
        el.end()
        return model
    }

    /** Registers a one-texture-per-face cube block + matching item model. */
    private fun simpleCubeBlock(deferred: DeferredBlock<*>, name: String) {
        val tex = ScalarEvolution.rl("block/$name")
        val model = models().cubeAll(name, tex)
        val block = deferred.get()
        simpleBlock(block, model)
        simpleBlockItem(block, model)
    }

    /**
     * Standard 6-face cube with three textures: front (the named
     * face), side (used for left/right/back), and top (used for
     * top/bottom). The vanilla `orientable` parent + `forAllStates`
     * wiring rotates the model around Y to track the
     * [DirectionalBlock.FACING] blockstate property.
     */
    private fun orientableBlock(deferred: DeferredBlock<out DirectionalBlock>, name: String) {
        val front = ScalarEvolution.rl("block/${name}_front")
        val side  = ScalarEvolution.rl("block/${name}_side")
        val top   = ScalarEvolution.rl("block/${name}_top")

        val model = models().orientable(name, side, front, top)

        val block = deferred.get()
        getVariantBuilder(block).forAllStates { state ->
            val yRot = when (state.getValue(DirectionalBlock.FACING)) {
                Direction.SOUTH -> 180
                Direction.WEST  -> 270
                Direction.EAST  -> 90
                else -> 0
            }
            ConfiguredModel.builder()
                .modelFile(model)
                .rotationY(yRot)
                .build()
        }
        simpleBlockItem(block, model)
    }

    private fun objBlock(
        deferred: DeferredBlock<out DirectionalBlock>,
        name: String,
        /** OBJ filename (without `.obj`). Defaults to [name] —
         *  override when several blocks share the same geometry but
         *  carry their own per-block textures. */
        modelName: String = name,
    ) {
        val texture = ScalarEvolution.rl("block/$name")
        val modelRL = ScalarEvolution.rl("models/block/$modelName.obj")

        // The `texture` slot is what the shared default.mtl's `map_Kd #texture`
        // resolves to at bake time. Without that slot (or without a `usemtl`
        // scope inside the OBJ), NeoForge's `ObjModel` silently drops every
        // face (ObjModel.java:541-543 — `if (mat == null) return;`) and the
        // block renders as invisible geometry. See docs/GOTCHAS.md.
        val model = models().getBuilder(name)
            .texture("texture",  texture)
            .texture("particle", texture)
        // Disable automatic culling: the OBJ geometry isn't aligned to block
        // faces (it's a desk / monitor / keyboard sitting inside the block
        // volume, not a full cube). Leaving automatic_culling=true silently
        // culls any face whose four vertices happen to lie on a block
        // boundary with outward normals — which bites particularly hard when
        // adjacent blocks are placed.
        model.customLoader { parent, helper -> ObjModelBuilder.begin(parent, helper) }
            .modelLocation(modelRL)
            .flipV(true)
            .automaticCulling(false)
            .end()

        val block = deferred.get()
        getVariantBuilder(block).forAllStates { state ->
            val yRot = when (state.getValue(DirectionalBlock.FACING)) {
                Direction.SOUTH -> 180
                Direction.WEST  -> 270
                Direction.EAST  -> 90
                else -> 0
            }
            ConfiguredModel.builder()
                .modelFile(model)
                .rotationY(yRot)
                .build()
        }
        // Inventory item model mirrors the block model.
        simpleBlockItem(block, model)
    }
}
