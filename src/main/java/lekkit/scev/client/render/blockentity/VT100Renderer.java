/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.render.blockentity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.UUID;
import lekkit.scev.blockentity.VT100BlockEntity;
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
 * Renders the linked machine's framebuffer onto a VT100 terminal's screen rect.
 * The VT100 self-links to the closest running machine with a display
 * (see {@link VT100BlockEntity#resolveLinkedMachine}).
 */
public class VT100Renderer implements BlockEntityRenderer<VT100BlockEntity> {

    // Screen rectangle on the VT100's front face (rough visual match to the OBJ).
    private static final float SCREEN_X0 = 0.20f;
    private static final float SCREEN_X1 = 0.80f;
    private static final float SCREEN_Y0 = 0.30f;
    private static final float SCREEN_Y1 = 0.78f;
    private static final float SCREEN_Z = 0.124f;

    @Override
    public void render(VT100BlockEntity be, float partialTicks, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        UUID uuid = be.resolveLinkedMachine();
        if (uuid == null) return;

        DisplayState display = DisplayManager.get(uuid);
        if (display == null) return;

        ResourceLocation tex = display.getOrUploadTexture();
        if (tex == null) return;

        poseStack.pushPose();
        try {
            poseStack.translate(0.5, 0.0, 0.5);
            poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(yawFromFacing(be.getBlockState())));
            poseStack.translate(-0.5, 0.0, -0.5);

            VertexConsumer buffer = buffers.getBuffer(RenderType.entityTranslucentEmissive(tex));
            Matrix4f mat = poseStack.last().pose();
            int fullLight = LightTexture.pack(15, 15);

            emitVertex(buffer, mat, SCREEN_X0, SCREEN_Y0, SCREEN_Z, 0.0f, 1.0f, fullLight, packedOverlay);
            emitVertex(buffer, mat, SCREEN_X1, SCREEN_Y0, SCREEN_Z, 1.0f, 1.0f, fullLight, packedOverlay);
            emitVertex(buffer, mat, SCREEN_X1, SCREEN_Y1, SCREEN_Z, 1.0f, 0.0f, fullLight, packedOverlay);
            emitVertex(buffer, mat, SCREEN_X0, SCREEN_Y1, SCREEN_Z, 0.0f, 0.0f, fullLight, packedOverlay);
        } finally {
            poseStack.popPose();
        }
    }

    private static void emitVertex(VertexConsumer buf, Matrix4f mat, float x, float y, float z,
                                   float u, float v, int light, int overlay) {
        buf.addVertex(mat, x, y, z)
           .setColor(0xFF, 0xFF, 0xFF, 0xFF)
           .setUv(u, v)
           .setOverlay(overlay)
           .setLight(light)
           .setNormal(0f, 0f, -1f);
    }

    private static float yawFromFacing(BlockState state) {
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
