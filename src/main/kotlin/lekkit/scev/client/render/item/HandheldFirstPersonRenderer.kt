/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.render.item

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import lekkit.scev.items.IHandheldComputer
import net.minecraft.client.Minecraft
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.entity.player.PlayerRenderer
import net.minecraft.util.Mth
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.item.ItemStack

/**
 * First-person held-hand renderer for `IHandheldComputer` items —
 * the path that mirrors vanilla's "raised both hands holding a map" pose
 * (`ItemInHandRenderer.renderTwoHandedMap` / `renderOneHandedMap`).
 *
 * Dispatched from [lekkit.scev.mixin.handheld.ItemInHandRendererMixin],
 * which intercepts vanilla `renderArmWithItem` *before* the hardcoded
 * `Items.FILLED_MAP` branch and routes to one of these two methods when
 * the held stack's item implements [IHandheldComputer].
 *
 * **Math is deliberately a near-copy of vanilla's map renderer** so the
 * pose, sway, pitch tilt, swing animation, equip-progress drop, and arm
 * rotations all match player muscle memory. The only delta is the final
 * "draw" step: instead of `renderMap` (vanilla's 128×128 map quad) we
 * call [HandheldItemRenderer.renderChassisAndScreen] to draw the chassis
 * BakedModel + the live framebuffer overlay in the same model-local
 * space.
 *
 * Looks up the [HandheldItemRenderer.ChassisProfile] via
 * [HandheldItemRenderer.profilesByItem] so this renderer doesn't depend
 * on the per-instance field on the BEWLR (which the mixin can't see).
 *
 * Constants here are the values from
 * `net.minecraft.client.renderer.ItemInHandRenderer` for `MAP_*`. Inlined
 * rather than imported because they're private in vanilla. Kept verbatim
 * so a vanilla refactor can be diff-detected.
 */
object HandheldFirstPersonRenderer {

    /** Both-hands raised pose. Called when the device is in main hand and offhand is empty. */
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
        val profile = HandheldItemRenderer.profilesByItem[stack.item] ?: return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return

        // Mirror ItemInHandRenderer.renderTwoHandedMap (lines 200-219).
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

        // Subtle bob on swing — mirror of `f4` factor in renderTwoHandedMap.
        val swingBob = Mth.sin(swingSqrt * Math.PI.toFloat())
        poseStack.mulPose(Axis.XP.rotationDegrees(swingBob * 20f))

        // Vanilla scales 2× then renderMap applies its own internal scale.
        // For a chassis BakedModel that's already in model-local 0..1 units,
        // this 2× makes the device read at "tablet held in front of face"
        // size — matches map's perceived size. Tweak if the chassis model's
        // bounding box differs significantly.
        poseStack.scale(2f, 2f, 2f)

        // Apply the same orientation flip the map quad uses so the screen
        // faces the camera (vanilla renderMap: YP 180, ZP 180), then center
        // the model so 0..1 coords are screen-relative.
        poseStack.mulPose(Axis.YP.rotationDegrees(180f))
        poseStack.mulPose(Axis.ZP.rotationDegrees(180f))
        poseStack.scale(0.38f, 0.38f, 0.38f)
        poseStack.translate(-0.5f, -0.5f, 0f)

        HandheldItemRenderer.renderChassisAndScreen(
            stack, profile, poseStack, buffers, packedLight, OVERLAY_NONE,
        )
    }

    /** Single-hand grip. Used when offhand is occupied or we're rendering the offhand stack. */
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
        val profile = HandheldItemRenderer.profilesByItem[stack.item] ?: return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return

        // Mirror ItemInHandRenderer.renderOneHandedMap (lines 174-198).
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

        // Same orient/center as renderMap (one-handed scale matches two-handed
        // for a consistent device-size feel; vanilla's map at one-hand is
        // smaller because renderMap is called without the 2× pre-scale —
        // we don't want our device to shrink in that case, so omit the 2×).
        poseStack.mulPose(Axis.YP.rotationDegrees(180f))
        poseStack.mulPose(Axis.ZP.rotationDegrees(180f))
        poseStack.scale(0.38f, 0.38f, 0.38f)
        poseStack.translate(-0.5f, -0.5f, 0f)

        HandheldItemRenderer.renderChassisAndScreen(
            stack, profile, poseStack, buffers, packedLight, OVERLAY_NONE,
        )
        poseStack.popPose()
    }

    /** Vanilla's `calculateMapTilt(pitch)` from ItemInHandRenderer.kt:151. */
    private fun calculateMapTilt(pitch: Float): Float {
        var f = 1f - pitch / 45f + 0.1f
        f = Mth.clamp(f, 0f, 1f)
        return -Mth.cos(f * Math.PI.toFloat()) * 0.5f + 0.5f
    }

    /** Render one bare arm reaching forward. Mirror of `renderMapHand`. */
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

    /** One-handed arm pose — mirror of `renderPlayerArm` in ItemInHandRenderer. */
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
