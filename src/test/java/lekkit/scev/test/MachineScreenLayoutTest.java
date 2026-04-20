/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Source-grep tests for {@code MachineScreen} (the framebuffer view opened
 * by shift + right-click on a powered machine). Locks down the dynamic
 * sizing fix so the screen doesn't regress to the 672×512 GUI window that
 * clipped on most real-world Minecraft display settings.
 *
 * <p><b>Why source-grep.</b> {@code AbstractContainerScreen} needs a live
 * {@code Minecraft} instance to construct — we can't unit-test the rendered
 * geometry without booting the client. We instead assert the shape of the
 * code that decides the geometry.
 */
class MachineScreenLayoutTest {

    private static final Path SCREEN = projectRoot()
            .resolve("src/main/java/lekkit/scev/client/screen/MachineScreen.java");

    private static Path projectRoot() {
        String override = System.getProperty("scev.projectDir");
        return override != null ? Paths.get(override) : Paths.get("").toAbsolutePath();
    }

    @Test
    @DisplayName("MachineScreen computes a display scale that fits the window")
    void scalesToFitWindow() throws IOException {
        String src = read();
        assertTrue(src.contains("displayScale"),
                "MachineScreen must store a computed display scale — a 1:1 native blit of "
                        + "the 640×480 framebuffer doesn't fit in most GUI-scale settings.");
        assertTrue(src.contains("this.width") && src.contains("this.height"),
                "MachineScreen must read the actual window dimensions (this.width / this.height) to "
                        + "decide the display size.");
        assertTrue(src.contains("Math.min"),
                "MachineScreen must pick the smaller of scaleX/scaleY to preserve the 4:3 aspect.");
    }

    @Test
    @DisplayName("MachineScreen never upscales above native 640×480")
    void neverUpscalesAboveNative() throws IOException {
        String src = read();
        // Locked in by the `Math.min(1.0f, ...)` clamp in init(). We check
        // structurally that init() caps the scale at 1.0.
        assertTrue(src.contains("Math.min(1.0f"),
                "MachineScreen must clamp displayScale at ≤ 1.0 so a huge window doesn't "
                        + "pixelate the framebuffer. Use Math.min(1.0f, ...).");
    }

    @Test
    @DisplayName("MachineScreen blits framebuffer with destination size from displayScale")
    void blitUsesScaledDestination() throws IOException {
        String src = read();
        // The scaled blit passes both destination size AND source size to
        // GuiGraphics.blit so the texture is stretched/shrunk to fit.
        assertTrue(src.contains("displayW") && src.contains("displayH"),
                "MachineScreen must use separate displayW/displayH for the blit destination.");
        assertTrue(src.contains("g.blit(tex, displayX, displayY, displayW, displayH, 0, 0, SCREEN_W, SCREEN_H"),
                "MachineScreen must blit with scaled destination dimensions — see the init()/renderBg() "
                        + "pair. If you changed the blit signature, update this test with the new contract.");
    }

    @Test
    @DisplayName("MachineScreen maps mouse coords back to framebuffer pixels via scale^-1")
    void mouseCoordsUnscale() throws IOException {
        String src = read();
        // emitMousePlace must account for scale so the VM sees framebuffer
        // pixel positions, not GUI-space positions.
        assertTrue(src.contains("emitMousePlace"),
                "MachineScreen must have an emitMousePlace helper.");
        assertTrue(src.contains("displayScale") && src.contains("scaleInv"),
                "emitMousePlace must divide by displayScale (or multiply by 1/scale) to map "
                        + "GUI-space mouse coords back to framebuffer pixels.");
    }

    @Test
    @DisplayName("MachineScreen hides inventory slots + labels — framebuffer view has no inventory UI")
    void hidesInventoryUi() throws IOException {
        String src = read();
        assertTrue(src.contains("protected void renderSlot") && src.contains("// no-op"),
                "MachineScreen must override renderSlot to a no-op — the menu's player-inventory slots "
                        + "exist only to avoid shift-click crashes, not to be shown.");
        assertTrue(src.contains("protected void renderLabels"),
                "MachineScreen must override renderLabels so the default 'Inventory' text doesn't "
                        + "print across the framebuffer.");
    }

    @Test
    @DisplayName("MachineScreen explicitly drops widget focus so keys reach the VM")
    void setFocusedNullInInit() throws IOException {
        String src = read();
        assertTrue(src.contains("this.setFocused(null)"),
                "MachineScreen.init() must call this.setFocused(null) after adding the power/reset "
                        + "buttons. Otherwise a focused button would swallow SPACE/ENTER before our "
                        + "keyPressed override can forward them to the VM.");
    }

    @Test
    @DisplayName("MachineScreen eats charTyped events so text doesn't leak to other UIs")
    void consumesCharTyped() throws IOException {
        String src = read();
        assertTrue(src.contains("charTyped"),
                "MachineScreen must override charTyped so typed characters don't leak into debug "
                        + "overlays / search bars while the user is typing at the VM.");
    }

    private static String read() throws IOException {
        return Files.readString(SCREEN);
    }
}
