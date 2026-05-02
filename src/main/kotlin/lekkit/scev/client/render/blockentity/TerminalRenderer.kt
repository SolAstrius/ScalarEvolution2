/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.render.blockentity

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import lekkit.scev.blockentity.TerminalBlockEntity
import lekkit.scev.blocks.DirectionalBlock
import lekkit.scev.client.render.CrtFxShader
import lekkit.scev.client.terminal.TerminalActiveHost
import lekkit.scev.main.ScalarEvolution
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.block.state.BlockState
import org.joml.Matrix4f

/**
 * Paints the live mlterm texture onto the front face of a VT100
 * block, when [TerminalActiveHost] is currently hosting the matching
 * machine UUID.
 *
 * One mlterm exists per JVM (see [TerminalActiveHost] for the
 * single-slot rationale), so at most one VT100 in the world is
 * "live" at any time — the most-recently-opened (or most-recently
 * ambient-acquired) one. Other VT100 blocks render their static
 * model texture as usual. Multi-block-live waits on lifting
 * mlterm-fb-embed's one-buffer constraint.
 *
 * The host's texture is registered with the texture manager when
 * acquired and stays valid for the host's full lifetime; we just
 * read its [TerminalActiveHost.Handle.texLocation] each frame.
 */
class TerminalRenderer : BlockEntityRenderer<TerminalBlockEntity> {

    override fun render(
        be: TerminalBlockEntity, partialTicks: Float, poseStack: PoseStack,
        buffers: MultiBufferSource, packedLight: Int, packedOverlay: Int,
    ) {
        // Pick the texture + tint based on whether this terminal has a
        // live mlterm session attached:
        //
        //  - bound + active host  → the live mlterm framebuffer
        //                           (texLocation), with the player's
        //                           configured CRT FX tint.
        //  - no host (off / never opened / unbound terminal) → the
        //                           static dark-glass off-state
        //                           texture, full-strength white tint
        //                           so the texture's actual colour
        //                           shows. Same bulged geometry —
        //                           the curve should be visible from
        //                           day one even before the machine's
        //                           been powered on.
        //
        // The bulged quad emits unconditionally so the picture-tube
        // silhouette is always there.
        val uuid = be.boundMachineUuid()
        val handle = uuid?.let { TerminalActiveHost.peek(it) }

        val texLoc: ResourceLocation
        val tint: Int
        if (handle != null) {
            // Pump the latest mlterm frame and upload. The TerminalScreen
            // does this from its Surface lambda while the GUI is open,
            // but when no GUI is open the Surface stops running and the
            // texture stalls on whatever frame was last uploaded — so
            // the in-world block face freezes even though the worker
            // thread is happily producing new frames. Doing it here
            // keeps the block face live regardless of GUI state.
            //
            // Persistence runs CPU-side because frame-ping-pong doesn't
            // fit MC's BlockEntityRenderer model (no per-BE FBO). The
            // source is only 480×312 so the per-pixel blend is well
            // under a millisecond.
            handle.backend.renderToPtr(handle.nativeImagePixelsPtr, handle.backend.pixelW)
            handle.applyPersistence(be.setupState.persistence)
            handle.texture.upload()
            texLoc = handle.texLocation
            tint = CrtFxShader.packTint(be.setupState)
        } else {
            texLoc = SCREEN_OFF_TEX
            tint = OFF_STATE_TINT
        }

        // Stage block-face FX. Curvature is forced to ZERO here
        // because the screen quad is geometrically bulged below (see
        // SCREEN_TESS / BULGE / bulgeZ) — the dome shape produces
        // its own on-axis barrel-distortion look via perspective
        // foreshortening when the player views head-on, and pairing
        // that with the shader's UV warp would double up the
        // distortion (text legibly bowed twice).
        CrtFxShader.stageEffects(curvature = 0f)

        poseStack.pushPose()
        try {
            // Center, rotate to face direction, decenter — same trick
            // the workstation framebuffer renderer used. DirectionalBlock
            // stores facing in BlockState; default NORTH if missing.
            poseStack.translate(0.5, 0.0, 0.5)
            poseStack.mulPose(Axis.YP.rotationDegrees(yawFromFacing(be.blockState)))
            poseStack.translate(-0.5, 0.0, -0.5)

            // CRT FX shader render type — does phosphor / brightness /
            // scanlines per-pixel on the GPU, reading per-block params
            // from each vertex's Color attribute (set in `tint`). One
            // RenderType per texture so vanilla's batcher won't merge
            // multiple terminals' draw calls and lose the texture
            // association.
            val buffer = buffers.getBuffer(CrtFxShader.renderType(texLoc))
            val mat = poseStack.last().pose()
            val fullLight = LightTexture.pack(15, 15)

            // Tessellated dome: subdivide the screen rect into an
            // SCREEN_TESS × SCREEN_TESS grid and displace inner cells
            // outward along the quad's normal so the picture tube
            // physically bulges, not just optically. From the front
            // it looks the same as a flat quad (the shader's UV warp
            // is what the eye reads as "curve" head-on); from the
            // side you get the unmistakable rounded glass silhouette
            // of a real CRT.
            //
            // The bulge profile is a hemispherical-ish cap: max at
            // (u,v)=(0.5,0.5), tapering quadratically to zero at the
            // edges, clamped at zero so the corners stay flush with
            // the model bezel surface.
            for (row in 0 until SCREEN_TESS) {
                for (col in 0 until SCREEN_TESS) {
                    val u0 = col.toFloat() / SCREEN_TESS
                    val u1 = (col + 1).toFloat() / SCREEN_TESS
                    val v0 = row.toFloat() / SCREEN_TESS
                    val v1 = (row + 1).toFloat() / SCREEN_TESS
                    val x0 = SCREEN_X0 + u0 * (SCREEN_X1 - SCREEN_X0)
                    val x1 = SCREEN_X0 + u1 * (SCREEN_X1 - SCREEN_X0)
                    val y0 = SCREEN_Y0 + v0 * (SCREEN_Y1 - SCREEN_Y0)
                    val y1 = SCREEN_Y0 + v1 * (SCREEN_Y1 - SCREEN_Y0)
                    val z00 = bulgeZ(u0, v0)
                    val z10 = bulgeZ(u1, v0)
                    val z11 = bulgeZ(u1, v1)
                    val z01 = bulgeZ(u0, v1)
                    // Per-cell winding mirrors the original quad —
                    // BL → BR → TR → TL with v inverted at the
                    // corners (texture origin top-left vs OpenGL
                    // bottom-left).
                    emitVertex(buffer, mat, x0, y0, z00, u0, 1f - v0, fullLight, packedOverlay, tint)
                    emitVertex(buffer, mat, x1, y0, z10, u1, 1f - v0, fullLight, packedOverlay, tint)
                    emitVertex(buffer, mat, x1, y1, z11, u1, 1f - v1, fullLight, packedOverlay, tint)
                    emitVertex(buffer, mat, x0, y1, z01, u0, 1f - v1, fullLight, packedOverlay, tint)
                }
            }
        } finally {
            poseStack.popPose()
        }
    }

    /* tint packing lives in CrtFxShader.packTint so TerminalScreen
     * can reuse it (the open-GUI surface renders through the same
     * shader for visual consistency with the in-world block face). */

    companion object {
        // Screen rectangle on the VT100's front face.
        //
        // The vt100.obj is built backwards from the modeller's intuition:
        //  - cube 1 at z[0.125, 0.171875] is the BACK VENT GRILLE (a thin
        //    slab at low z, textured with the vertical grille pattern at
        //    pixels x[16,32] y[1,10] image-y-down)
        //  - cube 3 at z[0.171875, 0.59375] is the MAIN CRT CASE BODY
        //  - cube 3's HIGH-Z face (z=0.59375, normal +Z) carries the
        //    SCREEN BEZEL + RECESS painted texture (pixels x[0,16]
        //    y[10,20])
        //
        // So the OBJ's "front" — the face with the screen recess — is
        // at block-local z=0.59375, NOT at low z as I first assumed.
        // Under MC's standard placement (FACING = ctx.dir.opposite),
        // this lands on the player-facing side without any rotation
        // wizardry: FACING=NORTH (yaw=0) keeps it at +Z, FACING=SOUTH
        // (yaw=180°) rotates it to -Z, etc. — the renderer's existing
        // yawFromFacing handles all four cases correctly.
        //
        // Bounds of the screen recess in the new (BB-revised) vt100.obj.
        //
        // The OBJ now has a real geometric recess cut into the case
        // body — cube 6 is the back wall of that recess (BB
        // from=[2,4,1] to=[11,11,9]). After BB's centred export and
        // our +0.5 x/z translation back to MC's [0..1] block-local
        // space, that lives at:
        //   recess opening: x[0.125, 0.6875], y[0.25, 0.6875]
        //   recess back wall (cube 6 +Z face): z = 0.5625
        //   bezel-rim front faces (cubes 3/4/5/7 +Z): z = 0.625
        //   recess depth: 0.0625 = 1 voxel pixel
        //
        // We render the screen quad EDGE-TO-EDGE with the recess
        // opening — the bezel-rim cubes are now real geometry framing
        // it, so no inset is needed.
        private const val SCREEN_X0: Float = 0.125f
        private const val SCREEN_X1: Float = 0.6875f
        private const val SCREEN_Y0: Float = 0.25f
        private const val SCREEN_Y1: Float = 0.6875f

        // Geometry of the picture-tube bulge.
        //
        // SCREEN_Z = 0.564 sits the dome's CORNERS one float-epsilon
        // (0.0015) past the recess back wall at z=0.5625, just enough
        // to avoid z-fighting with cube 6's +Z face. BULGE = 0.061
        // brings the centre forward to z=0.625 — exactly flush with
        // the bezel-rim front faces. Net result:
        //  - Front view: surface curves smoothly from the bezel
        //    level at the centre back to the recess wall at the
        //    corners. Real picture-tube on-axis distortion via
        //    perspective foreshortening on the curved geometry.
        //  - Side view: dome silhouette is now visible WITHIN the
        //    recess opening (the bezel-rim cubes form the side walls
        //    that contain it), no protrusion past the case envelope.
        //
        // Side-view bulge is finally clean because the OBJ has a real
        // recess to bulge INTO — back when the recess was just painted
        // texture on a flat cube, any bulge necessarily poked past
        // the case silhouette.
        private const val SCREEN_Z: Float  = 0.564f
        private const val BULGE: Float     = 0.061f

        // Static off-state texture — a 4×4 dark glass colour fed
        // through the same shader path as the live mlterm frame.
        // Used when the terminal has no bound machine UUID, or the
        // active host hasn't been acquired (player hasn't opened the
        // GUI on this block since loading the world). Lets the dome
        // silhouette + bezel render even on "off" terminals so the
        // picture tube shape is visible from day one. The asset is
        // 4×4 for cache-line alignment; the GPU upsamples linearly
        // to whatever the visible quad size is.
        private val SCREEN_OFF_TEX: ResourceLocation =
            ScalarEvolution.rl("textures/block/terminal_screen_off.png")

        // Off-state vertex color: full-strength white (so the off-
        // state texture's actual colour shows through unmodified)
        // with zero scanline strength (no flicker on a uniform dark
        // surface). Packed ARGB: A=0 (no scanlines), R=G=B=255.
        private const val OFF_STATE_TINT: Int = 0x00FFFFFF

        // Tessellation density for the screen dome. 16×16 = 256
        // sub-quads = 1024 vertices per visible terminal. Higher
        // than the original 8×8 because the silhouette curve at the
        // edges showed visible polygon kinks at 8 segments per side
        // when viewed in profile; 16 is smooth enough that the
        // silhouette reads as a continuous arc. Cost is still under
        // a millisecond of vertex work per visible terminal.
        private const val SCREEN_TESS: Int = 16

        /**
         * Hemispherical-ish bulge profile centred on (u,v)=(0.5,0.5).
         * Returns the block-local z (along the quad's outward normal)
         * for a vertex at the given UV. Inputs in [0..1]; output =
         * SCREEN_Z + 0..BULGE, with the peak at the centre tapering
         * quadratically to zero at the edges.
         */
        private fun bulgeZ(u: Float, v: Float): Float {
            val du = u - 0.5f
            val dv = v - 0.5f
            // r² in [0, 0.5] (corners reach 0.5 = 0.25+0.25). Profile:
            // 1 - 2r² is the dome — flat-bottomed disc that drops to
            // zero at r²=0.5 (the corners). coerceAtLeast(0) is just
            // belt-and-suspenders for floating-point edge cases.
            val r2 = du * du + dv * dv
            return SCREEN_Z + BULGE * (1f - 2f * r2).coerceAtLeast(0f)
        }

        private fun emitVertex(
            buf: VertexConsumer, mat: Matrix4f, x: Float, y: Float, z: Float,
            u: Float, v: Float, light: Int, overlay: Int, argb: Int,
        ) {
            val a = (argb ushr 24) and 0xFF
            val r = (argb ushr 16) and 0xFF
            val g = (argb ushr  8) and 0xFF
            val b =  argb         and 0xFF
            buf.addVertex(mat, x, y, z)
                .setColor(r, g, b, a)
                .setUv(u, v)
                .setOverlay(overlay)
                .setLight(light)
                .setNormal(0f, 0f, 1f)
        }

        private fun yawFromFacing(state: BlockState): Float {
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
