/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.render

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import lekkit.scev.client.terminal.setup.SetupModel
import lekkit.scev.main.ScalarEvolution
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.resources.ResourceLocation
import net.neoforged.neoforge.client.event.RegisterShadersEvent

/**
 * Custom GLSL shader + matching [RenderType] for the live VT100 face.
 *
 * Replaces `entityTranslucentEmissive` in [lekkit.scev.client.render.blockentity.TerminalRenderer]
 * with a shader that does, on the GPU:
 *
 *   - Phosphor coloring:  `texel.rgb * vertexColor.rgb`
 *   - Brightness:         folded into vertexColor.rgb by the renderer
 *   - Scanlines:          every other source-pixel row dimmed by
 *                         `vertexColor.a` (encoded in 0..1 from the
 *                         [setup.SetupModel.PersistentState.scanlines]
 *                         percentage)
 *   - (future)            bloom, vignette, persistence, barrel
 *                         distortion — each just another GLSL pass on
 *                         top of `crt_fx.fsh`.
 *
 * Per-block params travel in vertex attributes (color RGBA), not
 * uniforms, so MultiBufferSource batching across multiple visible
 * VT100s works without per-quad uniform mutation. The only uniform
 * that's actually per-block-state — the source texture height, used
 * for scanline period — is set once from the shader JSON and stays
 * fixed because every kind we currently ship renders to a 312-px-tall
 * mlterm output.
 *
 * Shader files live at `assets/scev/shaders/core/crt_fx.{vsh,fsh,json}`.
 */
object CrtFxShader {

    /** Shader instance, resolved by [onRegisterShaders]. Null until
     *  the resource pack has been loaded; the [RenderType]'s shader
     *  shard handles the deferred lookup gracefully via the supplier
     *  pattern Mojang's render pipeline expects. */
    @Volatile private var instance: ShaderInstance? = null

    @JvmStatic
    fun onRegisterShaders(e: RegisterShadersEvent) {
        // POSITION_COLOR_TEX matches the attributes declared in
        // crt_fx.json. The renderer must emit vertices in this exact
        // format when using CRT_FX_RENDER_TYPE — see TerminalRenderer's
        // emitVertex for the pack order.
        e.registerShader(
            ShaderInstance(
                e.resourceProvider,
                ResourceLocation.fromNamespaceAndPath(ScalarEvolution.MODID, "crt_fx"),
                DefaultVertexFormat.POSITION_TEX_COLOR,
            )
        ) { loaded -> instance = loaded }
    }

    /**
     * The render-state shard that binds [instance] for any draw using
     * [CRT_FX_RENDER_TYPE]. Mojang's [RenderType] composite-state
     * builder takes a [RenderStateShard.ShaderStateShard] which holds
     * a shader supplier — the supplier is consulted lazily at draw
     * time, so we can hand it `instance::get` even though `instance`
     * is null at class-load time.
     */
    private val shaderShard: RenderStateShard.ShaderStateShard =
        RenderStateShard.ShaderStateShard { instance }

    /**
     * Called by [lekkit.scev.client.render.blockentity.TerminalRenderer.render]
     * to fetch the [RenderType] keyed on a specific texture
     * (the [lekkit.scev.client.terminal.TerminalActiveHost.Handle.texLocation]
     * for whichever block is currently active). One RenderType per
     * texture so vanilla's batcher doesn't cross-bleed buffers across
     * different terminals — same pattern as
     * [RenderType.entityTranslucentEmissive].
     */
    fun renderType(tex: ResourceLocation): RenderType =
        renderTypeCache.getOrPut(tex) { buildRenderType(tex) }

    private val renderTypeCache: MutableMap<ResourceLocation, RenderType> = HashMap()

    /**
     * Stage the world-level effect uniforms for the next batch flush
     * to use. Setting these in the renderer right before queuing
     * vertices works because [net.minecraft.client.renderer.ShaderInstance]
     * stages uniform values lazily — they get uploaded to GL when
     * the shader is bound at draw time. The world buffer flushes at
     * the end of the world render phase (well before GUI rendering),
     * and the GUI flush is forced explicitly in [TerminalScreen]
     * via `ctx.flush()`, so the staged value reaches the right draw.
     *
     * Use this to differentiate the block-face look (full CRT FX)
     * from the GUI look (zero curvature so typing isn't visually
     * disrupted) without two separate shader instances.
     */
    fun stageEffects(
        curvature: Float = JSON_DEFAULT_CURVATURE,
        vignette: Float = JSON_DEFAULT_VIGNETTE,
        bloom: Float = JSON_DEFAULT_BLOOM,
        apertureMask: Float = JSON_DEFAULT_APERTURE,
    ) {
        val s = instance ?: return
        s.safeGetUniform("Curvature").set(curvature)
        s.safeGetUniform("Vignette").set(vignette)
        s.safeGetUniform("Bloom").set(bloom)
        s.safeGetUniform("ApertureMask").set(apertureMask)
    }

    // Mirrors crt_fx.json defaults; kept here too so callers can
    // reset to the world-default state without re-reading the JSON.
    const val JSON_DEFAULT_CURVATURE: Float  = 0.10f
    const val JSON_DEFAULT_VIGNETTE: Float   = 0.30f
    const val JSON_DEFAULT_BLOOM: Float      = 0.20f
    const val JSON_DEFAULT_APERTURE: Float   = 0.00f

    /**
     * Pack the per-block CRT FX params into a single ARGB int for the
     * quad's vertex Color attribute. The fragment shader (crt_fx.fsh)
     * reads:
     *   .rgb = phosphor color × brightness — final RGB tint multiplied
     *          with each texel
     *   .a   = scanline strength 0..1 — mask amplitude for the
     *          every-other-row dim factor
     *
     * Used by both [lekkit.scev.client.render.blockentity.TerminalRenderer]
     * (in-world block face) and
     * [lekkit.scev.client.screen.TerminalScreen] (open-GUI surface)
     * so the two views stay visually consistent.
     */
    fun packTint(s: SetupModel.PersistentState): Int {
        // Real VT100 NVR Intensity is a 4-bit DAC: 0..15. Map linearly
        // to a 0..1 scalar with a floor at 0.15 so a min-intensity
        // setting still leaves the screen barely-readable rather than
        // pitch black (UX > authenticity for an unrecoverable setting).
        val brightness = 0.15f + 0.85f * (s.intensity.coerceIn(0, 15) / 15f)
        // Phosphor presets — picked to match the rough chromaticity of
        // the real CRT phosphors at full beam:
        //   P1 (green, classic VT100 look): ~rgb(0, 255, 51)
        //   P3 (amber, some VT100s + most VT220+): ~rgb(255, 176, 0)
        //   P4 (white-ish "monochrome"): slightly cool off-white
        val (rf, gf, bf) = when (s.phosphor) {
            SetupModel.Phosphor.GREEN -> Triple(0.00f, 1.00f, 0.20f)
            SetupModel.Phosphor.AMBER -> Triple(1.00f, 0.69f, 0.00f)
            SetupModel.Phosphor.WHITE -> Triple(0.92f, 0.95f, 1.00f)
        }
        val r = (brightness * rf * 255f).toInt().coerceIn(0, 255)
        val g = (brightness * gf * 255f).toInt().coerceIn(0, 255)
        val b = (brightness * bf * 255f).toInt().coerceIn(0, 255)
        // Scanline strength 0..50% → 0..127 in alpha. The shader treats
        // vertexColor.a as 0..1 (auto / 255 by GL).
        val a = (s.scanlines.coerceIn(0, 50) / 100f * 255f).toInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun buildRenderType(tex: ResourceLocation): RenderType =
        RenderType.create(
            "${ScalarEvolution.MODID}_crt_fx",
            DefaultVertexFormat.POSITION_TEX_COLOR,
            VertexFormat.Mode.QUADS,
            256,
            false,                                            // no crumbling
            true,                                             // sorting on translucency
            RenderType.CompositeState.builder()
                .setShaderState(shaderShard)
                .setTextureState(RenderStateShard.TextureStateShard(tex, false, false))
                .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                .setLightmapState(RenderStateShard.LIGHTMAP)
                .setOverlayState(RenderStateShard.OVERLAY)
                .createCompositeState(false),
        )
}
