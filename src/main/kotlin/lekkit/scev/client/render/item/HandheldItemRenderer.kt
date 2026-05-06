/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.render.item

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import lekkit.scev.client.DisplayManager
import lekkit.scev.main.ScevDataComponents
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.ItemBlockRenderTypes
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.entity.ItemRenderer
import net.minecraft.client.resources.model.BakedModel
import net.minecraft.client.resources.model.ModelResourceLocation
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions
import org.joml.Matrix4f

/**
 * BEWLR for handheld computer items. Draws the item's chassis BakedModel
 * (resolved from the registered item id, same path the icon would use)
 * and overlays a single textured quad sampling the live framebuffer
 * texture from [DisplayManager], keyed on the stack's MACHINE_UUID.
 *
 * **Why BEWLR.** Fires for every display context — first person, third
 * person, item frame, GUI icon, ground entity. One renderer covers
 * every view of the item. The icon variant draws the chassis with the
 * screen as a flat overlay; the in-hand variants get the same plus a
 * little depth offset so the screen sits proud of the bezel.
 *
 * **No FX.** Plain `entityTranslucentEmissive` quad — emissive so the
 * screen reads in the dark, translucent so it can be drawn after the
 * chassis without depth tearing. No shader, no scanlines, no curvature.
 * If a specific chassis kind wants visual character later, swap the
 * RenderType for a `crt_fx`-style custom shader at this single call
 * site.
 *
 * **Where the framebuffer comes from.** [DisplayManager] is shared
 * with `MachineScreen`; both consumers ask for the same UUID and get
 * the same `DynamicTexture`. Server-side `MachineDisplayStreamer`
 * (owned per-UUID by `HandheldTickHost`) broadcasts H.264 NAL bytes to
 * nearby players; each receiving client decodes into its `DisplayState`.
 * The holder sees their own screen; spectators within the broadcast
 * radius see it too.
 */
class HandheldItemRenderer(private val profile: ChassisProfile) : IClientItemExtensions {

    private val bewlr = object : net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer(
        Minecraft.getInstance().blockEntityRenderDispatcher,
        Minecraft.getInstance().entityModels,
    ) {
        override fun renderByItem(
            stack: ItemStack,
            ctx: ItemDisplayContext,
            poseStack: PoseStack,
            buffers: MultiBufferSource,
            packedLight: Int,
            packedOverlay: Int,
        ) {
            val mc = Minecraft.getInstance()
            val model: BakedModel = mc.modelManager.getModel(profile.modelId)

            poseStack.pushPose()

            // Apply the per-context display transform manually (this is what
            // ItemRenderer.render would do for a non-custom model). Going
            // through itemRenderer.render directly is unsafe here: if the
            // chassis model ever sets isCustomRenderer=true (which is the
            // standard way to route items through a BEWLR), render() would
            // dispatch back to renderByItem and infinite-recurse. Mirroring
            // the vanilla translate(0.5) → transform → translate(-0.5)
            // sequence avoids the round-trip entirely.
            val leftHanded =
                ctx == ItemDisplayContext.FIRST_PERSON_LEFT_HAND ||
                ctx == ItemDisplayContext.THIRD_PERSON_LEFT_HAND
            poseStack.translate(0.5f, 0.5f, 0.5f)
            model.transforms.getTransform(ctx).apply(leftHanded, poseStack)
            poseStack.translate(-0.5f, -0.5f, -0.5f)

            renderChassisAndScreen(stack, profile, poseStack, buffers, packedLight, packedOverlay)

            poseStack.popPose()
        }
    }

    override fun getCustomRenderer(): net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer = bewlr

    /**
     * Third-person arm pose: report the SCEV_HANDHELD pose so spectators
     * see the holder raising both hands like they're reading a tablet.
     * First-person rendering uses the mixin path
     * ([lekkit.scev.client.render.item.HandheldFirstPersonRenderer])
     * instead — this hook only affects the player model, which is not
     * drawn for the local player's first-person view.
     */
    override fun getArmPose(
        entity: net.minecraft.world.entity.LivingEntity,
        hand: net.minecraft.world.InteractionHand,
        stack: ItemStack,
    ): net.minecraft.client.model.HumanoidModel.ArmPose? {
        return lekkit.scev.client.render.HandheldArmPose.pose
    }

    /** Per-chassis-kind config. Add more profiles for phones, tablets, watches. */
    data class ChassisProfile(
        /** Resolved baked model — usually `<modid>:<itemname>#inventory`. */
        val modelId: ModelResourceLocation,
        /** Screen quad rectangle in block-local coords (matches the chassis JSON model bezel cutout). */
        val screenRect: ScreenRect,
    )

    /** XY rect of the screen face, plus the Z plane it sits on. */
    data class ScreenRect(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val z: Float)

    companion object {
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val BLACK = 0xFF000000.toInt()

        /**
         * Map of `Item` → its [ChassisProfile], populated when each
         * handheld item's BEWLR is registered via
         * [registerProfile]. Lookup target for the first-person
         * two-handed renderer ([HandheldFirstPersonRenderer]) which
         * doesn't go through `IClientItemExtensions` at all (it's
         * dispatched from a Mixin into vanilla `ItemInHandRenderer`)
         * and therefore can't reach the per-instance profile field.
         */
        @JvmField
        val profilesByItem: MutableMap<net.minecraft.world.item.Item, ChassisProfile> = HashMap()

        @JvmStatic fun registerProfile(item: net.minecraft.world.item.Item, profile: ChassisProfile) {
            profilesByItem[item] = profile
        }

        /**
         * Render the chassis BakedModel + screen overlay in the *current*
         * pose-stack space. Caller is responsible for applying the desired
         * world/view transform (e.g. the two-handed map pose for first-person
         * held rendering). Used by both the BEWLR (item-icon / item-frame /
         * dropped entity / one-handed in-hand) and the first-person two-handed
         * renderer.
         */
        @JvmStatic
        fun renderChassisAndScreen(
            stack: ItemStack,
            profile: ChassisProfile,
            poseStack: PoseStack,
            buffers: MultiBufferSource,
            packedLight: Int,
            packedOverlay: Int,
        ) {
            val mc = Minecraft.getInstance()
            val model: BakedModel = mc.modelManager.getModel(profile.modelId)
            val chassisRt = ItemBlockRenderTypes.getRenderType(stack, true)
            val chassisVc = ItemRenderer.getFoilBufferDirect(buffers, chassisRt, true, stack.hasFoil())
            mc.itemRenderer.renderModelLists(model, stack, packedLight, packedOverlay, poseStack, chassisVc)

            val uuid = stack.get(ScevDataComponents.MACHINE_UUID.get())
            val display = uuid?.let { DisplayManager.get(it) }
            val tex = display?.getOrUploadTexture()
            val rect = profile.screenRect
            val mat = poseStack.last().pose()
            val emissiveLight = LightTexture.pack(15, 15)
            if (tex != null) {
                val buffer = buffers.getBuffer(RenderType.entityTranslucentEmissive(tex))
                emitScreenQuad(buffer, mat, rect, emissiveLight, packedOverlay, paint = WHITE)
            } else {
                val buffer = buffers.getBuffer(RenderType.solid())
                emitScreenQuad(buffer, mat, rect, emissiveLight, packedOverlay, paint = BLACK)
            }
        }

        private fun emitScreenQuad(
            buf: VertexConsumer,
            mat: Matrix4f,
            r: ScreenRect,
            light: Int,
            overlay: Int,
            paint: Int,
        ) {
            // BL → TL → TR → BR. UVs: V grows downward.
            val a = (paint ushr 24) and 0xFF
            val rr = (paint ushr 16) and 0xFF
            val g = (paint ushr 8) and 0xFF
            val b = paint and 0xFF
            emit(buf, mat, r.x0, r.y0, r.z, 0f, 1f, rr, g, b, a, light, overlay)
            emit(buf, mat, r.x0, r.y1, r.z, 0f, 0f, rr, g, b, a, light, overlay)
            emit(buf, mat, r.x1, r.y1, r.z, 1f, 0f, rr, g, b, a, light, overlay)
            emit(buf, mat, r.x1, r.y0, r.z, 1f, 1f, rr, g, b, a, light, overlay)
        }

        private fun emit(
            buf: VertexConsumer, mat: Matrix4f, x: Float, y: Float, z: Float,
            u: Float, v: Float, r: Int, g: Int, b: Int, a: Int, light: Int, overlay: Int,
        ) {
            buf.addVertex(mat, x, y, z)
                .setColor(r, g, b, a)
                .setUv(u, v)
                .setOverlay(overlay)
                .setLight(light)
                .setNormal(0f, 0f, 1f)
        }

    }
}
