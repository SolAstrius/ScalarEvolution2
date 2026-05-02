/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test

import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import lekkit.scev.client.terminal.MltermBackend
import lekkit.scev.client.terminal.MltermNative
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * End-to-end smoke for libscev_term: load the native, create a
 * backend, feed a prompt, render, dump as PNG.
 *
 * Skipped when the bundled native isn't available for the host
 * classifier — CI's per-platform matrix is the place that
 * exercises this; local dev runs need `./gradlew buildMltermNative`
 * (with MLTERM_SRC pointing at the SolAstrius/mlterm-fb-embed
 * checkout) before the test sees the .so.
 *
 * Spawns mlterm's screen manager in-process. Because that's a
 * process global, a single instance per JVM is the limit — no
 * @ParameterizedTest, no second backend in the same run.
 */
class MltermBackendSmokeTest {

    @Test
    @DisplayName("MltermBackend renders 'hello world' into an IntArray + PNG")
    fun helloWorld(@TempDir outDir: Path) {
        assumeTrue(MltermNative.ensureLoaded(), "libscev_term native not available; skipping smoke")

        MltermBackend(cols = 80, rows = 24).use { term ->
            // Bold green "hello world" + newline. SGR escape sequences
            // get parsed inside mlterm — proves the parser is alive.
            term.feedString("[1;32mhello world[0m\r\n")

            // mlterm doesn't render synchronously on write — it's an
            // event-loop arch. Pump a few times with sleeps to let
            // the parser flush + idle pass run + framebuffer paint.
            val pixels = IntArray(term.pixelW * term.pixelH)
            repeat(20) {
                term.render(pixels, term.pixelW)
                Thread.sleep(50)
            }

            // Write PNG. Use BufferedImage.TYPE_INT_ARGB so the int
            // array can be installed directly as the raster's
            // backing store via setRGB.
            val img = BufferedImage(term.pixelW, term.pixelH, BufferedImage.TYPE_INT_ARGB)
            img.setRGB(0, 0, term.pixelW, term.pixelH, pixels, 0, term.pixelW)
            val pngPath = outDir.resolve("mlterm-smoke.png")
            ImageIO.write(img, "png", pngPath.toFile())

            // Sanity: at least one non-zero pixel proves something
            // got drawn (cursor block alone, if nothing else).
            val anyLit = pixels.any { (it and 0xFFFFFF) != 0 }
            check(anyLit) { "mlterm rendered an entirely-black frame to ${pngPath}" }

            println("MltermBackend smoke PNG -> $pngPath  (${term.pixelW}x${term.pixelH})")
        }
    }
}
