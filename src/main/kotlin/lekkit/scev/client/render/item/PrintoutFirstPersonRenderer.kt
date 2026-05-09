/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.render.item

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import lekkit.scev.main.ScevDataComponents
import net.minecraft.client.Minecraft
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.entity.player.PlayerRenderer
import net.minecraft.util.Mth
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.item.ItemStack

/**
 * First-person renderer for [lekkit.scev.items.PrintoutItem].
 *
 * Mirrors vanilla `ItemInHandRenderer.renderTwoHandedMap` /
 * `renderOneHandedMap` so a held printout reads exactly like a held
 * filled map: raised in front of the face when both hands are free,
 * one-handed grip otherwise. Dispatched from
 * `lekkit.scev.mixin.handheld.ItemInHandRendererMixin` (same hook
 * point as the handheld renderer) before vanilla's hardcoded
 * `Items.FILLED_MAP` branch.
 *
 * **Why a separate object from [HandheldFirstPersonRenderer].**
 * The pose math is identical (it's just map math), but the final
 * "draw" step differs: handhelds render a 3D chassis BakedModel
 * plus a live framebuffer overlay; a printout is a flat page with
 * a static bitmap. Sharing a generic helper would mean threading a
 * draw-callback through the renderer; copying the constants is
 * simpler and lets each renderer evolve independently.
 *
 * **Constants are copies of vanilla's** `ItemInHandRenderer.MAP_*`,
 * inlined because they're private in vanilla. Kept verbatim so a
 * vanilla refactor can be diff-detected.
 */
object PrintoutFirstPersonRenderer {

    /** Both hands raised, page presented to the camera. Used when the
     *  printout is in main hand and offhand is empty. */
    @JvmStatic
    fun renderTwoHanded(
        stack: ItemStack,
        pitch: Float,
        swingProgress: Float,
        equipProgress: Float,
        poseStack: PoseStack,
        buffers: MultiBufferSource,
        packedLight: Int,
    ) {
        val printout = stack.get(ScevDataComponents.PRINTOUT_CONTENT.get()) ?: return
        val player = Minecraft.getInstance().player ?: return

        // Mirror ItemInHandRenderer.renderTwoHandedMap.
        val swingSqrt = Mth.sqrt(swingProgress)
        val swayY = -0.2f * Mth.sin(swingProgress * Math.PI.toFloat())
        val swayZ = -0.4f * Mth.sin(swingSqrt * Math.PI.toFloat())
        poseStack.translate(0f, -swayY / 2f, swayZ)

        val tilt = calculateMapTilt(pitch)
        poseStack.translate(0f, 0.04f + equipProgress * -1.2f + tilt * -0.5f, -0.72f)
        poseStack.mulPose(Axis.XP.rotationDegrees(tilt * -85f))

        if (!player.isInvisible) {
            poseStack.pushPose()
            poseStack.mulPose(Axis.YP.rotationDegrees(90f))
            renderHand(player, poseStack, buffers, packedLight, HumanoidArm.RIGHT)
            renderHand(player, poseStack, buffers, packedLight, HumanoidArm.LEFT)
            poseStack.popPose()
        }

        val swingBob = Mth.sin(swingSqrt * Math.PI.toFloat())
        poseStack.mulPose(Axis.XP.rotationDegrees(swingBob * 20f))

        // Mirror vanilla `renderMap`'s final scale + orient so the page
        // sits at "held in front of face" size and faces the camera.
        // drawPage renders into 0..1 unit-square local coords; the final
        // translate(-0.5, -0.5, 0) re-centres around the origin so the
        // earlier rotations spin the page around its centre, not its
        // corner.
        poseStack.scale(2f, 2f, 2f)
        poseStack.mulPose(Axis.YP.rotationDegrees(180f))
        poseStack.mulPose(Axis.ZP.rotationDegrees(180f))
        poseStack.scale(0.38f, 0.38f, 0.38f)
        poseStack.translate(-0.5f, -0.5f, 0f)

        PrintoutItemRenderer.drawPage(
            printout, poseStack, buffers, packedLight, OVERLAY_NONE,
        )
    }

    /** Single-hand grip — used when offhand is occupied or rendering the
     *  off-hand stack. Mirror of `renderOneHandedMap`. */
    @JvmStatic
    fun renderOneHanded(
        stack: ItemStack,
        equipProgress: Float,
        arm: HumanoidArm,
        swingProgress: Float,
        poseStack: PoseStack,
        buffers: MultiBufferSource,
        packedLight: Int,
    ) {
        val printout = stack.get(ScevDataComponents.PRINTOUT_CONTENT.get()) ?: return
        val player = Minecraft.getInstance().player ?: return

        val side = if (arm == HumanoidArm.RIGHT) 1f else -1f
        poseStack.translate(side * 0.125f, -0.125f, 0f)

        if (!player.isInvisible) {
            poseStack.pushPose()
            poseStack.mulPose(Axis.ZP.rotationDegrees(side * 10f))
            renderHandSingle(player, poseStack, buffers, packedLight, equipProgress, swingProgress, arm)
            poseStack.popPose()
        }

        poseStack.pushPose()
        poseStack.translate(side * 0.51f, -0.08f + equipProgress * -1.2f, -0.75f)
        val swingSqrt = Mth.sqrt(swingProgress)
        val sin1 = Mth.sin(swingSqrt * Math.PI.toFloat())
        val swayX = -0.5f * sin1
        val swayY = 0.4f * Mth.sin(swingSqrt * (Math.PI * 2.0).toFloat())
        val swayZ = -0.3f * Mth.sin(swingProgress * Math.PI.toFloat())
        poseStack.translate(side * swayX, swayY - 0.3f * sin1, swayZ)
        poseStack.mulPose(Axis.XP.rotationDegrees(sin1 * -45f))
        poseStack.mulPose(Axis.YP.rotationDegrees(side * sin1 * -30f))

        // No 2× pre-scale here — vanilla's one-handed map is the
        // smaller variant on purpose (the player is half-glancing at
        // it). Match that feel for printouts.
        poseStack.mulPose(Axis.YP.rotationDegrees(180f))
        poseStack.mulPose(Axis.ZP.rotationDegrees(180f))
        poseStack.scale(0.38f, 0.38f, 0.38f)
        poseStack.translate(-0.5f, -0.5f, 0f)

        PrintoutItemRenderer.drawPage(
            printout, poseStack, buffers, packedLight, OVERLAY_NONE,
        )
        poseStack.popPose()
    }

    /** Vanilla's `calculateMapTilt(pitch)`. */
    private fun calculateMapTilt(pitch: Float): Float {
        var f = 1f - pitch / 45f + 0.1f
        f = Mth.clamp(f, 0f, 1f)
        return -Mth.cos(f * Math.PI.toFloat()) * 0.5f + 0.5f
    }

    private fun renderHand(
        player: AbstractClientPlayer,
        poseStack: PoseStack,
        buffers: MultiBufferSource,
        packedLight: Int,
        arm: HumanoidArm,
    ) {
        val playerRenderer = Minecraft.getInstance().entityRenderDispatcher
            .getRenderer(player) as PlayerRenderer
        poseStack.pushPose()
        val side = if (arm == HumanoidArm.RIGHT) 1f else -1f
        poseStack.mulPose(Axis.YP.rotationDegrees(92f))
        poseStack.mulPose(Axis.XP.rotationDegrees(45f))
        poseStack.mulPose(Axis.ZP.rotationDegrees(side * -41f))
        poseStack.translate(side * 0.3f, -1.1f, 0.45f)
        if (arm == HumanoidArm.RIGHT) {
            playerRenderer.renderRightHand(poseStack, buffers, packedLight, player)
        } else {
            playerRenderer.renderLeftHand(poseStack, buffers, packedLight, player)
        }
        poseStack.popPose()
    }

    private fun renderHandSingle(
        player: AbstractClientPlayer,
        poseStack: PoseStack,
        buffers: MultiBufferSource,
        packedLight: Int,
        equipProgress: Float,
        swingProgress: Float,
        arm: HumanoidArm,
    ) {
        val playerRenderer = Minecraft.getInstance().entityRenderDispatcher
            .getRenderer(player) as PlayerRenderer
        val isRight = arm != HumanoidArm.LEFT
        val side = if (isRight) 1f else -1f
        val swingSqrt = Mth.sqrt(swingProgress)
        val tx = -0.3f * Mth.sin(swingSqrt * Math.PI.toFloat())
        val ty = 0.4f * Mth.sin(swingSqrt * (Math.PI * 2.0).toFloat())
        val tz = -0.4f * Mth.sin(swingProgress * Math.PI.toFloat())
        poseStack.translate(side * (tx + 0.64f), ty + -0.6f + equipProgress * -0.6f, tz + -0.72f)
        poseStack.mulPose(Axis.YP.rotationDegrees(side * 45f))
        val sin2 = Mth.sin(swingProgress * swingProgress * Math.PI.toFloat())
        val sin1 = Mth.sin(swingSqrt * Math.PI.toFloat())
        poseStack.mulPose(Axis.YP.rotationDegrees(side * sin1 * 70f))
        poseStack.mulPose(Axis.ZP.rotationDegrees(side * sin2 * -20f))
        poseStack.translate(side * -1f, 3.6f, 3.5f)
        poseStack.mulPose(Axis.ZP.rotationDegrees(side * 120f))
        poseStack.mulPose(Axis.XP.rotationDegrees(200f))
        poseStack.mulPose(Axis.YP.rotationDegrees(side * -135f))
        poseStack.translate(side * 5.6f, 0f, 0f)
        if (isRight) {
            playerRenderer.renderRightHand(poseStack, buffers, packedLight, player)
        } else {
            playerRenderer.renderLeftHand(poseStack, buffers, packedLight, player)
        }
    }

    private val OVERLAY_NONE: Int = net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY
}
