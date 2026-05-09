/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Page-layout math (per-context PoseStack transforms via the baked
 * model's display transforms; centred quad in the unit cube; texture
 * cache keyed by content) is adapted from CC: Tweaked's
 * PrintoutItemRenderer / PrintoutRenderer (Mozilla Public License
 * v2.0, 2018 The CC: Tweaked Developers — separate from the
 * peripheral package which is CCPL-licensed and thus not used).
 * Source: https://github.com/cc-tweaked/cc-tweaked
 */
package lekkit.scev.client.render.item

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import lekkit.scev.items.Printout
import lekkit.scev.items.PrintoutItem
import lekkit.scev.main.ScalarEvolution
import lekkit.scev.main.ScevDataComponents
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.resources.model.BakedModel
import net.minecraft.client.resources.model.ModelResourceLocation
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.EntityType
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.client.event.RenderItemInFrameEvent
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions
import org.joml.Matrix4f
import java.util.LinkedHashMap

/**
 * BEWLR for [lekkit.scev.items.PrintoutItem]. Fires for every
 * display context (first-person, third-person, item-frame, GUI
 * icon, ground entity) and draws a single page-shaped quad textured
 * with a `DynamicTexture` baked from the stack's [Printout].
 *
 * **Per-context positioning.** The renderer applies the baked
 * model's display transforms (the same `display` block in the
 * vanilla item JSON model that places a 2D icon correctly in every
 * context) and then draws our quad in unit-cube-local space. This
 * mirrors [HandheldItemRenderer]'s approach and means the page
 * sits where you'd expect a 2D item icon to sit, without
 * per-context manual tuning. The chassis BakedModel itself is
 * **not** rendered — the printout has no 3D body, only the page
 * surface.
 *
 * **Texture cache.** [Printout] equality is content-based, so two
 * stacks with identical pixels share one `DynamicTexture`. The
 * cache is an LRU bounded at [MAX_CACHED_TEXTURES] — when an entry
 * is evicted, its GL handle is released so we don't leak texture
 * memory across world loads / printer activity.
 *
 * **One-sided.** `RenderType.entityCutoutNoCull` so item-frames
 * still show the page from the back (item-frame back-faces show
 * the contained item on the side the frame is hung against).
 */
class PrintoutItemRenderer : IClientItemExtensions {
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
            val printout = stack.get(ScevDataComponents.PRINTOUT_CONTENT.get())
                ?: return  // No content yet — render nothing rather than a blank quad.
            poseStack.pushPose()

            // Position in the unit cube using the placeholder JSON
            // model's display transforms. Going through
            // ItemRenderer.render is unsafe here for the same reason
            // HandheldItemRenderer documents: if isCustomRenderer is
            // true on the model, render() would re-dispatch back here
            // and infinite-recurse. We mirror the vanilla
            // translate(0.5) → transform → translate(-0.5) sequence.
            val mc = Minecraft.getInstance()
            val model: BakedModel = mc.modelManager.getModel(MODEL_ID)
            val leftHanded =
                ctx == ItemDisplayContext.FIRST_PERSON_LEFT_HAND ||
                ctx == ItemDisplayContext.THIRD_PERSON_LEFT_HAND
            poseStack.translate(0.5f, 0.5f, 0.5f)
            model.transforms.getTransform(ctx).apply(leftHanded, poseStack)
            poseStack.translate(-0.5f, -0.5f, -0.5f)

            drawPage(printout, poseStack, buffers, packedLight, packedOverlay)
            poseStack.popPose()
        }
    }

    override fun getCustomRenderer(): net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer = bewlr

    companion object {
        /** Resource id of the placeholder item-generated model whose display
         *  transforms drive per-context positioning. Generated by
         *  `ScevItemModelProvider`. */
        private val MODEL_ID: ModelResourceLocation =
            ModelResourceLocation.inventory(
                ResourceLocation.fromNamespaceAndPath(ScalarEvolution.MODID, "printout"))

        /** Gutter between stacked pages, packed ABGR (NativeImage's native form). */
        private const val PAGE_GAP_ABGR: Int = 0xFF80B0C8.toInt()  // RGB 0xC8B080
        private const val PAGE_GAP_PX: Int = 2

        /** RGB (0x00RRGGBB) → ABGR (0xAABBGGRR) for [NativeImage.setPixelRGBA].
         *  Alpha is forced opaque — palette entries don't carry alpha. */
        private fun rgbToAbgr(rgb: Int): Int {
            val r = (rgb ushr 16) and 0xFF
            val g = (rgb ushr 8) and 0xFF
            val b = rgb and 0xFF
            return (0xFF shl 24) or (b shl 16) or (g shl 8) or r
        }

        private const val MAX_CACHED_TEXTURES: Int = 64

        /** Monotone counter so generated texture ids never collide. */
        private var textureCounter: Long = 0

        /** Content-keyed LRU. Evicting an entry releases its texture handle. */
        private val cache: MutableMap<Printout, CacheEntry> =
            object : LinkedHashMap<Printout, CacheEntry>(16, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<Printout, CacheEntry>
                ): Boolean {
                    if (size <= MAX_CACHED_TEXTURES) return false
                    Minecraft.getInstance().textureManager.release(eldest.value.id)
                    eldest.value.texture.close()
                    return true
                }
            }

        private data class CacheEntry(val texture: DynamicTexture, val id: ResourceLocation)

        /**
         * Item-frame render hook. Subscribed to [RenderItemInFrameEvent]
         * from `ScevClient.onClientSetup`. Replaces vanilla's "render
         * the held stack as a small floating icon" with a full-frame
         * page render — the printout fills the frame face like a
         * posted document.
         *
         * Transforms are the CC: Tweaked recipe (MPL-2.0): forward
         * bias to avoid z-fighting with the frame mesh, ZP 180° to
         * flip the page upright (frame entity space has Y down), 0.95
         * scale to fill the frame's interior, and a -0.5 / -0.5
         * recentre so [drawPage]'s 0..1 unit-square reads centred.
         * The Z scale negates so back-face culling resolves to the
         * frame's outward normal — pairs with the
         * `entityCutoutNoCull` render type so the page is visible
         * from both sides of a hung frame.
         */
        // Temporary diagnostic — flips true the first time
        // onRenderInFrame fires, regardless of stack item. Logged once
        // so we can tell from the run log whether the listener is
        // subscribed at all (independent of whether the stack happens
        // to be a PrintoutItem). Remove once frame rendering is
        // confirmed working.
        private var loggedFirstFire: Boolean = false

        @JvmStatic
        fun onRenderInFrame(event: RenderItemInFrameEvent) {
            if (!loggedFirstFire) {
                loggedFirstFire = true
                com.mojang.logging.LogUtils.getLogger().info(
                    "[scev] RenderItemInFrameEvent listener fired for stack: {}",
                    event.itemStack
                )
            }
            val stack = event.itemStack
            if (stack.item !is PrintoutItem) return
            com.mojang.logging.LogUtils.getLogger().info(
                "[scev] printout-in-frame: drawing"
            )
            val printout = stack.get(ScevDataComponents.PRINTOUT_CONTENT.get()) ?: return

            val pose = event.poseStack
            pose.pushPose()
            pose.translate(0f, 0f, -0.001f)
            pose.mulPose(Axis.ZP.rotationDegrees(180f))
            pose.scale(0.95f, 0.95f, -0.95f)
            pose.translate(-0.5f, -0.5f, 0f)

            // Glow item frames pre-light their contents — match
            // vanilla map-in-frame behavior so a printout in a glow
            // frame stays readable in the dark.
            val light = if (event.itemFrameEntity.type == EntityType.GLOW_ITEM_FRAME) {
                LightTexture.pack(15, 15)
            } else event.packedLight

            drawPage(printout, pose, event.multiBufferSource, light, OverlayTexture.NO_OVERLAY)
            pose.popPose()
            event.isCanceled = true
        }

        /** Resolve / build the texture for a [Printout]. Render-thread only. */
        @JvmStatic
        fun textureFor(p: Printout): ResourceLocation {
            cache[p]?.let { return it.id }

            // Layout: pages stacked vertically with a [PAGE_GAP_PX]
            // gutter so multi-page printouts read as a strip.
            val w = p.width
            val pageH = p.height
            val gap = if (p.pageCount > 1) PAGE_GAP_PX else 0
            val totalH = p.pageCount * pageH + (p.pageCount - 1) * gap
            val img = NativeImage(NativeImage.Format.RGBA, w, totalH, false)

            // Pre-convert the printout's RGB palette to NativeImage's
            // ABGR pixel format so the inner blit loop is a tight
            // index → lookup → write with no per-pixel arithmetic.
            val palAbgr = IntArray(Printout.PALETTE_SIZE) { rgbToAbgr(p.palette[it]) }

            // Page rasters (palette[0] is paper / background — fills
            // every untouched pixel by virtue of the format).
            for (page in 0 until p.pageCount) {
                val yOff = page * (pageH + gap)
                for (y in 0 until pageH) {
                    for (x in 0 until w) {
                        img.setPixelRGBA(x, yOff + y, palAbgr[p.pixel(page, x, y)])
                    }
                }
            }
            // Gutters between stacked pages — drawn after page content
            // so they cleanly overwrite anything that bled into the gap
            // band (currently nothing, but cheap insurance).
            if (gap > 0) {
                for (page in 0 until p.pageCount - 1) {
                    val yStart = (page + 1) * pageH + page * gap
                    for (gy in 0 until gap) for (x in 0 until w) {
                        img.setPixelRGBA(x, yStart + gy, PAGE_GAP_ABGR)
                    }
                }
            }

            val tex = DynamicTexture(img)
            val id = ScalarEvolution.rl("printout/dyn_" + (textureCounter++).toString(16))
            Minecraft.getInstance().textureManager.register(id, tex)
            cache[p] = CacheEntry(tex, id)
            return id
        }

        /**
         * Draw one printout in the *current* PoseStack space,
         * occupying the unit square with its longest edge spanning
         * 1.0 and the other edge centred.
         */
        @JvmStatic
        fun drawPage(
            p: Printout,
            pose: PoseStack,
            buffers: MultiBufferSource,
            packedLight: Int,
            packedOverlay: Int,
        ) {
            val tex = textureFor(p)
            val mat = pose.last().pose()

            // Aspect = texHeight / texWidth. Used to fit the longest
            // axis to 1.0 unit.
            val gap = if (p.pageCount > 1) PAGE_GAP_PX else 0
            val texHeight = p.pageCount * p.height + (p.pageCount - 1) * gap
            val aspect = texHeight.toFloat() / p.width.toFloat()
            val maxEdge = maxOf(1f, aspect)
            val pageW = 1f / maxEdge
            val pageH = aspect / maxEdge
            val x0 = (1f - pageW) * 0.5f
            val y0 = (1f - pageH) * 0.5f
            val x1 = x0 + pageW
            val y1 = y0 + pageH

            val buffer = buffers.getBuffer(RenderType.entityCutoutNoCull(tex))
            // BL → TL → TR → BR. UVs: V increases downward in NativeImage,
            // and GUI Y also increases downward, so V==y mapping reads upright.
            emit(buffer, mat, x0, y1, 0f, 0f, 1f, packedLight, packedOverlay)
            emit(buffer, mat, x1, y1, 0f, 1f, 1f, packedLight, packedOverlay)
            emit(buffer, mat, x1, y0, 0f, 1f, 0f, packedLight, packedOverlay)
            emit(buffer, mat, x0, y0, 0f, 0f, 0f, packedLight, packedOverlay)
        }

        private fun emit(
            buf: VertexConsumer, mat: Matrix4f,
            x: Float, y: Float, z: Float,
            u: Float, v: Float,
            packedLight: Int, packedOverlay: Int,
        ) {
            // Full-bright white per-vertex tint — the texture carries the
            // page color; we don't want to modulate it. Light comes from
            // the world (GUI / first-person inject 0xF000F0 themselves).
            buf.addVertex(mat, x, y, z)
                .setColor(0xFF, 0xFF, 0xFF, 0xFF)
                .setUv(u, v)
                .setOverlay(if (packedOverlay == 0) OverlayTexture.NO_OVERLAY else packedOverlay)
                .setLight(if (packedLight == 0) LightTexture.pack(15, 15) else packedLight)
                .setNormal(0f, 0f, 1f)
        }
    }
}
