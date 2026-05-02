/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.screen.widget

import com.mojang.blaze3d.systems.RenderSystem
import java.util.function.BooleanSupplier
import java.util.function.Consumer
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation

/**
 * Button widget that blits sub-regions of a sprite sheet based on state.
 *
 * Three intrinsic frames the caller supplies as UVs:
 *   - **idle** — default look.
 *   - **hover** — mouse-over feedback.
 *   - **pressed** — click flash, lasts ~200 ms after [onPress].
 *
 * Optional latched state via [withToggle]: alternate idle/hover frames
 * used when the supplied [BooleanSupplier] returns true. Intended for
 * buttons like "machine is powered" where the visual reflects a
 * persistent state.
 *
 * Temporary failure flash via [flashFail] — overlays a red tint for a
 * short duration regardless of other state. Used to signal "your click
 * didn't take" without needing a separate failure-state frame.
 *
 * The onClick callback receives the button itself so call sites can
 * invoke [flashFail] without capturing a mutable reference.
 */
open class IconButton(
    x: Int, y: Int, width: Int, height: Int,
    private val texture: ResourceLocation,
    private val sheetWidth: Int, private val sheetHeight: Int,
    private val uIdle: Int, private val vIdle: Int,
    private val uHover: Int, private val vHover: Int,
    private val uPressed: Int, private val vPressed: Int,
    message: Component,
    private val onClick: Consumer<IconButton>,
) : AbstractButton(x, y, width, height, message) {

    private var lastPressedAtMs: Long = 0L
    private var failFlashEndMs: Long = 0L

    private var toggleSupplier: BooleanSupplier? = null
    private var uOnIdle: Int = 0
    private var vOnIdle: Int = 0
    private var uOnHover: Int = 0
    private var vOnHover: Int = 0

    /**
     * Bind a latched-state predicate and the UVs used while it returns true.
     *
     * @param supplier polled every frame; true → render toggled-on.
     */
    fun withToggle(
        supplier: BooleanSupplier,
        uOn: Int, vOn: Int,
        uOnHover: Int, vOnHover: Int,
    ): IconButton {
        toggleSupplier = supplier
        uOnIdle = uOn
        vOnIdle = vOn
        this.uOnHover = uOnHover
        this.vOnHover = vOnHover
        return this
    }

    /**
     * Tint the button red for [durationMs] starting now. Independent of
     * the press flash; overlaps if both are active. Used to signal a
     * rejected click (failed validation, server returned an error).
     */
    fun flashFail(durationMs: Long) {
        failFlashEndMs = System.currentTimeMillis() + durationMs
    }

    override fun onPress() {
        if (isPressedFlashing()) return
        lastPressedAtMs = System.currentTimeMillis()
        onClick.accept(this)
    }

    override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha)

        val (u, v) = when {
            isPressedFlashing() -> uPressed to vPressed
            toggleSupplier?.asBoolean == true ->
                if (isHovered) uOnHover to vOnHover else uOnIdle to vOnIdle
            isHovered -> uHover to vHover
            else -> uIdle to vIdle
        }
        graphics.blit(texture, x, y, u.toFloat(), v.toFloat(), width, height, sheetWidth, sheetHeight)

        // Fail-flash overlay: solid red tint over the current frame.
        if (isFailFlashing()) {
            graphics.fill(x, y, x + width, y + height, 0x80FF3030.toInt())
        }

        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F)
    }

    private fun isPressedFlashing(): Boolean =
        lastPressedAtMs != 0L && (System.currentTimeMillis() - lastPressedAtMs) < PRESS_FLASH_DURATION_MS

    private fun isFailFlashing(): Boolean =
        failFlashEndMs != 0L && System.currentTimeMillis() < failFlashEndMs

    override fun updateWidgetNarration(narrationElementOutput: NarrationElementOutput) {
        defaultButtonNarrationText(narrationElementOutput)
    }

    companion object {
        private const val PRESS_FLASH_DURATION_MS: Long = 200L
    }
}
