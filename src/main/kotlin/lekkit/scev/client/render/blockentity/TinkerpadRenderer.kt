/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.render.blockentity

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import lekkit.scev.blockentity.TinkerpadBlockEntity
import lekkit.scev.blocks.DirectionalBlock
import lekkit.scev.client.DisplayManager
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.core.Direction
import net.minecraft.world.level.block.state.BlockState
import org.joml.Matrix4f

/**
 * Renders the machine's framebuffer onto the Tinkerpad laptop's lid screen.
 *
 * **OBJ geometry.** The lid is a thin vertical slab at block-local
 * z=[0.125, 0.171875] (-Z end of the unrotated model). A flat keyboard
 * base sits at y=[0, 0.093] extending the full depth (z=[0.125, 0.875]).
 * A real-laptop lid's *interior* face — the one facing the keyboard tray,
 * where the screen actually lives — is the [LID_Z_MAX] face at +Z in
 * block-local.
 *
 * **Where we draw.** A single translucent quad at `z = LID_Z_MAX + Z_BIAS`
 * (just outside the interior face, offset to avoid z-fighting with the
 * OBJ's baked bezel). The tinkerpad texture has a transparent cutout
 * where the bezel surrounds the screen area.
 *
 * **Winding.** [RenderType.entityTranslucentEmissive] renders two-sided,
 * so [VertexConsumer.setNormal] alone doesn't fix orientation — the
 * vertex winding does. We use BL → TL → TR → BR; cross product
 * `(TL-BL) × (TR-BL) = (0,+,0) × (+,+,0) = (0,0,-)` so the right-hand-rule
 * front face points -Z block-local. The typical `FACING=SOUTH` placement
 * rotates the model 180°, which swings -Z block-local to +Z world,
 * pointing straight at the player.
 */
class TinkerpadRenderer : BlockEntityRenderer<TinkerpadBlockEntity> {

    override fun render(
        be: TinkerpadBlockEntity, partialTicks: Float, poseStack: PoseStack,
        buffers: MultiBufferSource, packedLight: Int, packedOverlay: Int,
    ) {
        val display = DisplayManager.get(be.getMachineUUID()) ?: return
        val tex = display.getOrUploadTexture() ?: return

        poseStack.pushPose()
        try {
            // Rotate around block centre to match FACING. Values mirror the
            // blockstate JSON so the quad follows the OBJ.
            poseStack.translate(0.5, 0.0, 0.5)
            poseStack.mulPose(Axis.YP.rotationDegrees(yawFromFacing(be.blockState)))
            poseStack.translate(-0.5, 0.0, -0.5)

            val buffer = buffers.getBuffer(RenderType.entityTranslucentEmissive(tex))
            val mat = poseStack.last().pose()

            // Emissive: ignore world lighting, draw at full brightness.
            val fullLight = LightTexture.pack(15, 15)
            val alpha = 0xFF

            // BL → TL → TR → BR. UVs: V grows downward (MC/DirectX convention).
            emitVertex(buffer, mat, SCREEN_X0, SCREEN_Y0, SCREEN_Z, 0.0f, 1.0f, fullLight, packedOverlay, alpha)
            emitVertex(buffer, mat, SCREEN_X0, SCREEN_Y1, SCREEN_Z, 0.0f, 0.0f, fullLight, packedOverlay, alpha)
            emitVertex(buffer, mat, SCREEN_X1, SCREEN_Y1, SCREEN_Z, 1.0f, 0.0f, fullLight, packedOverlay, alpha)
            emitVertex(buffer, mat, SCREEN_X1, SCREEN_Y0, SCREEN_Z, 1.0f, 1.0f, fullLight, packedOverlay, alpha)
        } finally {
            poseStack.popPose()
        }
    }

    companion object {
        // Screen rectangle on the lid's interior face, in block-local XY.
        const val SCREEN_X0: Float = 0.18f
        const val SCREEN_X1: Float = 0.82f
        const val SCREEN_Y0: Float = 0.21f
        const val SCREEN_Y1: Float = 0.72f

        // Lid thickness in block-local z: [LID_Z_MIN, LID_Z_MAX]. The framebuffer
        // quad sits just outside the +Z (interior / keyboard-facing) face, biased
        // by Z_BIAS to avoid z-fighting with the OBJ's baked bezel.
        const val LID_Z_MIN: Float  = 0.125f
        const val LID_Z_MAX: Float  = 0.171875f
        const val Z_BIAS: Float     = 0.002f
        const val SCREEN_Z: Float   = LID_Z_MAX + Z_BIAS  // 0.173875

        private fun emitVertex(
            buf: VertexConsumer, mat: Matrix4f, x: Float, y: Float, z: Float,
            u: Float, v: Float, light: Int, overlay: Int, alpha: Int,
        ) {
            buf.addVertex(mat, x, y, z)
                .setColor(0xFF, 0xFF, 0xFF, alpha)
                .setUv(u, v)
                .setOverlay(overlay)
                .setLight(light)
                // Normal matches the winding-implied front face direction (-Z
                // in block-local). Emissive render ignores lighting, but some
                // shader packs sample normals for effects.
                .setNormal(0f, 0f, -1f)
        }

        /**
         * Map HORIZONTAL_FACING to a yaw rotation (deg around Y). The OBJ's
         * lid is at -Z in the default model; FACING=NORTH = 0° (lid stays
         * at -Z), SOUTH = 180°, etc.
         */
        fun yawFromFacing(state: BlockState): Float {
            val facing = if (state.hasProperty(DirectionalBlock.FACING))
                state.getValue(DirectionalBlock.FACING) else Direction.NORTH
            return when (facing) {
                Direction.NORTH -> 0f
                Direction.EAST  -> 270f
                Direction.SOUTH -> 180f
                Direction.WEST  -> 90f
                else -> 0f
            }
        }
    }
}
