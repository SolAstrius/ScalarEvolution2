/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.screen.widget;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Button widget that blits sub-regions of a sprite sheet based on state.
 *
 * <p>Three intrinsic frames the caller supplies as UVs:
 * <ul>
 *   <li><b>idle</b> — default look.</li>
 *   <li><b>hover</b> — mouse-over feedback.</li>
 *   <li><b>pressed</b> — click flash, lasts ~200 ms after {@link #onPress}.</li>
 * </ul>
 *
 * <p>Optional latched state via {@link #withToggle}: alternate idle/hover
 * frames used when the supplied {@link BooleanSupplier} returns true.
 * Intended for buttons like "machine is powered" where the visual should
 * reflect a persistent state.
 *
 * <p>Temporary failure flash via {@link #flashFail} — overlays a red tint
 * for a short duration regardless of other state. Used to signal "your
 * click didn't take" without needing a separate failure-state frame.
 *
 * <p>The onClick callback receives the button itself so call sites can
 * invoke {@link #flashFail} without capturing a mutable reference.
 */
public class IconButton extends AbstractButton {

    private static final long PRESS_FLASH_DURATION_MS = 200L;

    private final ResourceLocation texture;
    private final int sheetWidth, sheetHeight;
    private final int uIdle, vIdle;
    private final int uHover, vHover;
    private final int uPressed, vPressed;
    private final Consumer<IconButton> onClick;

    private long lastPressedAtMs;
    private long failFlashEndMs;

    private @Nullable BooleanSupplier toggleSupplier;
    private int uOnIdle, vOnIdle;
    private int uOnHover, vOnHover;

    public IconButton(int x, int y, int width, int height,
                      ResourceLocation texture, int sheetWidth, int sheetHeight,
                      int uIdle, int vIdle,
                      int uHover, int vHover,
                      int uPressed, int vPressed,
                      Component message, Consumer<IconButton> onClick) {
        super(x, y, width, height, message);
        this.texture = texture;
        this.sheetWidth = sheetWidth;
        this.sheetHeight = sheetHeight;
        this.uIdle = uIdle;
        this.vIdle = vIdle;
        this.uHover = uHover;
        this.vHover = vHover;
        this.uPressed = uPressed;
        this.vPressed = vPressed;
        this.onClick = onClick;
    }

    /**
     * Binds a latched-state predicate and the UVs used while it returns true.
     *
     * @param supplier polled every frame; {@code true} → render toggled-on.
     * @param uOn, vOn UV for "on, idle".
     * @param uOnHover, vOnHover UV for "on, hovered".
     */
    public IconButton withToggle(BooleanSupplier supplier,
                                 int uOn, int vOn,
                                 int uOnHover, int vOnHover) {
        this.toggleSupplier = supplier;
        this.uOnIdle = uOn;
        this.vOnIdle = vOn;
        this.uOnHover = uOnHover;
        this.vOnHover = vOnHover;
        return this;
    }

    /**
     * Tint the button red for {@code durationMs} starting now. Independent of
     * the press flash; overlaps if both are active. Used to signal a rejected
     * click (failed validation, server returned an error).
     */
    public void flashFail(long durationMs) {
        this.failFlashEndMs = System.currentTimeMillis() + durationMs;
    }

    @Override
    public void onPress() {
        if (isPressedFlashing()) return;
        this.lastPressedAtMs = System.currentTimeMillis();
        onClick.accept(this);
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, this.alpha);

        int u, v;
        if (isPressedFlashing()) {
            u = uPressed; v = vPressed;
        } else if (toggleSupplier != null && toggleSupplier.getAsBoolean()) {
            if (isHovered()) { u = uOnHover; v = vOnHover; }
            else             { u = uOnIdle;  v = vOnIdle; }
        } else if (isHovered()) {
            u = uHover; v = vHover;
        } else {
            u = uIdle; v = vIdle;
        }
        graphics.blit(texture, getX(), getY(), u, v, width, height, sheetWidth, sheetHeight);

        // Fail-flash overlay: solid red tint over the current frame.
        if (isFailFlashing()) {
            graphics.fill(getX(), getY(), getX() + width, getY() + height, 0x80FF3030);
        }

        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private boolean isPressedFlashing() {
        return lastPressedAtMs != 0
                && (System.currentTimeMillis() - lastPressedAtMs) < PRESS_FLASH_DURATION_MS;
    }

    private boolean isFailFlashing() {
        return failFlashEndMs != 0 && System.currentTimeMillis() < failFlashEndMs;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        this.defaultButtonNarrationText(narrationElementOutput);
    }
}
