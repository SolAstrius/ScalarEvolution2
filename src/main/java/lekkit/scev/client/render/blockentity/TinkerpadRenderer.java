/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.render.blockentity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import lekkit.scev.blockentity.TinkerpadBlockEntity;
import lekkit.scev.blocks.DirectionalBlock;
import lekkit.scev.client.DisplayManager;
import lekkit.scev.client.DisplayState;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;

/**
 * Renders the machine's framebuffer onto the Tinkerpad laptop's lid screen.
 *
 * <p><b>OBJ geometry.</b> The lid is a thin vertical slab at
 * block-local z=[0.125, 0.171875] (-Z end of the block in the unrotated model).
 * A flat keyboard base sits at y=[0, 0.093] extending the full depth
 * (z=[0.125, 0.875]). A real-laptop lid's <i>interior</i> face — the one
 * facing the keyboard tray, where the screen actually lives — is the
 * {@value #LID_Z_MAX} face at +Z in block-local.
 *
 * <p><b>Where we draw.</b> A single translucent quad at
 * {@code z = LID_Z_MAX + Z_BIAS} (just outside the interior face, offset to
 * avoid z-fighting with the OBJ's baked bezel). The tinkerpad texture has a
 * transparent cutout where the bezel surrounds the screen area, so the
 * player can see this quad through the lid even when the lid itself is
 * between the quad and the camera.
 *
 * <p><b>Winding.</b> {@link RenderType#entityTranslucentEmissive} renders
 * two-sided, so {@link VertexConsumer#setNormal} alone doesn't fix
 * orientation — the quad's vertex winding does.
 *
 * <p>We use {@code BL → TL → TR → BR}. Cross product
 * {@code (TL-BL) × (TR-BL) = (0,+,0) × (+,+,0) = (0,0,-)} — so the
 * right-hand-rule front face points -Z in block-local. The typical player
 * placement (player south of block, {@code FACING=SOUTH}, blockstate rotates
 * the model 180°) swings that -Z block-local normal to +Z in world,
 * pointing straight at the player. They see the front face of the quad and
 * UVs read left-to-right.
 *
 * <p><b>History.</b> An earlier version emitted quads on <i>both</i> faces
 * of the lid so the framebuffer was visible from any viewing angle. That
 * was correct-ish (both quads rendered non-mirrored from their own outward
 * side) but wrong visually — a real laptop doesn't have a working screen
 * on the back cover of the lid, and the spurious "back screen" showed up
 * through the bezel cutout too. Collapsing to a single quad on the
 * interior face matches the real-laptop convention and the 1.7.10
 * renderer (which drew at old z=-0.4375 = new z=0.171875, the same
 * interior face).
 *
 * <p>If the machine isn't running or the display hasn't been initialised,
 * the renderer does nothing — the block's own OBJ model still shows the
 * printed screen graphic as a static placeholder.
 */
public class TinkerpadRenderer implements BlockEntityRenderer<TinkerpadBlockEntity> {

    // Screen rectangle on the lid's interior face, in block-local XY.
    static final float SCREEN_X0 = 0.18f;
    static final float SCREEN_X1 = 0.82f;
    static final float SCREEN_Y0 = 0.21f;
    static final float SCREEN_Y1 = 0.72f;

    // Lid thickness in block-local z: [LID_Z_MIN, LID_Z_MAX]. The framebuffer
    // quad sits just outside the +Z (interior / keyboard-facing) face of the
    // lid, biased by Z_BIAS to avoid z-fighting with the OBJ's baked bezel.
    static final float LID_Z_MIN = 0.125f;
    static final float LID_Z_MAX = 0.171875f;
    static final float Z_BIAS = 0.002f;
    static final float SCREEN_Z = LID_Z_MAX + Z_BIAS; // 0.173875

    @Override
    public void render(TinkerpadBlockEntity be, float partialTicks, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        DisplayState display = DisplayManager.get(be.getMachineUUID());
        if (display == null) return;

        ResourceLocation tex = display.getOrUploadTexture();
        if (tex == null) return;

        poseStack.pushPose();
        try {
            // Rotate around block centre to match FACING. The rotation values in
            // yawFromFacing mirror the blockstate JSON so the quad follows the OBJ.
            poseStack.translate(0.5, 0.0, 0.5);
            poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(yawFromFacing(be.getBlockState())));
            poseStack.translate(-0.5, 0.0, -0.5);

            VertexConsumer buffer = buffers.getBuffer(RenderType.entityTranslucentEmissive(tex));
            Matrix4f mat = poseStack.last().pose();

            // Emissive: ignore world lighting, always draw at full brightness.
            int fullLight = LightTexture.pack(15, 15);
            int alpha = 0xFF;

            // Vertex order BL → TL → TR → BR — right-hand rule yields -Z
            // block-local normal → +Z world normal for FACING=SOUTH.
            // UVs: texture's V grows downward (Minecraft/DirectX convention).
            // BL=(0,1) bottom-left, TL=(0,0) top-left, TR=(1,0) top-right,
            // BR=(1,1) bottom-right — canonical, no U or V mirror.
            emitVertex(buffer, mat, SCREEN_X0, SCREEN_Y0, SCREEN_Z, 0.0f, 1.0f, fullLight, packedOverlay, alpha);
            emitVertex(buffer, mat, SCREEN_X0, SCREEN_Y1, SCREEN_Z, 0.0f, 0.0f, fullLight, packedOverlay, alpha);
            emitVertex(buffer, mat, SCREEN_X1, SCREEN_Y1, SCREEN_Z, 1.0f, 0.0f, fullLight, packedOverlay, alpha);
            emitVertex(buffer, mat, SCREEN_X1, SCREEN_Y0, SCREEN_Z, 1.0f, 1.0f, fullLight, packedOverlay, alpha);
        } finally {
            poseStack.popPose();
        }
    }

    private static void emitVertex(VertexConsumer buf, Matrix4f mat, float x, float y, float z,
                                   float u, float v, int light, int overlay, int alpha) {
        buf.addVertex(mat, x, y, z)
           .setColor(0xFF, 0xFF, 0xFF, alpha)
           .setUv(u, v)
           .setOverlay(overlay)
           .setLight(light)
           // Normal matches the winding-implied front face direction (-Z in
           // block-local). Emissive render ignores lighting, but some shader
           // packs still sample normals for effects.
           .setNormal(0f, 0f, -1f);
    }

    /**
     * Map the block's HORIZONTAL_FACING to a yaw rotation (degrees around Y).
     * The OBJ's lid is at -Z in the default (unrotated) model; a block with
     * FACING=NORTH uses 0° so the lid stays at -Z, SOUTH uses 180°, etc.
     * The rotation values match the blockstate JSON (see
     * {@code src/generated/resources/.../blockstates/tinkerpad.json}).
     */
    static float yawFromFacing(BlockState state) {
        Direction facing = state.hasProperty(DirectionalBlock.FACING)
                ? state.getValue(DirectionalBlock.FACING) : Direction.NORTH;
        return switch (facing) {
            case NORTH -> 0f;
            case EAST -> 270f;
            case SOUTH -> 180f;
            case WEST -> 90f;
            default -> 0f;
        };
    }
}
