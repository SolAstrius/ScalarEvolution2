/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.terminal

import com.mojang.logging.LogUtils
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

/**
 * Resolve + load `libscev_term.{so,dylib,dll}` and the bundled
 * Cozette font from the mod jar, then call into the native
 * `nativeInit(fontPath)` to lock mlterm out of every host
 * filesystem read.
 *
 * Self-contained: ZERO host filesystem state is touched at runtime
 * beyond extracting the .so + bundled font into a per-JVM temp dir.
 * No `~/.mlterm`, no `/etc/mlterm`, no `$DISPLAY`, no fontconfig
 * scan, no helper-binary execve. The fork patches that make this
 * possible:
 *
 *  - 7c5fb5f6  ui_font_embed_lock_config + vt_color_embed_lock_config
 *  - ebc5f6f2  drop linux/input.h from embed file
 *  - 8e793e7b  skip getenv("DISPLAY") + cursor sprite in embed open
 *  - fc39fad3  default --without-fontconfig under --enable-fb-embed
 *  - d3f66fc0  in-process image loader (no mlimgloader/registobmp exec)
 *
 * Pattern mirrors H264Native — single object, idempotent init,
 * fails loudly if the bundled native isn't present for the host
 * classifier.
 */
object MltermNative {
    private val LOG = LogUtils.getLogger()
    @Volatile private var loaded: Boolean = false

    /** Idempotent. Returns true once the .so is in-process AND
     *  `nativeInit` has succeeded. */
    @JvmStatic fun ensureLoaded(): Boolean {
        if (loaded) return true
        return synchronized(MltermNative::class.java) {
            if (loaded) return@synchronized true
            tryLoad().also { loaded = it }
        }
    }

    @JvmStatic fun isLoaded(): Boolean = loaded

    private fun tryLoad(): Boolean {
        val classifier = detectClassifier() ?: run {
            LOG.warn("libscev_term: unsupported OS/arch (no native bundled)")
            return false
        }

        return try {
            val baseDir = Files.createTempDirectory("scev-mlterm-")
            baseDir.toFile().deleteOnExit()

            // Extract the bundled .so.
            val libName = System.mapLibraryName("scev_term")
            val libDst = baseDir.resolve(libName)
            MltermNative::class.java.getResourceAsStream("/natives/$classifier/$libName").use { input ->
                if (input == null) {
                    LOG.warn("libscev_term: no bundled native at /natives/{}/{}", classifier, libName)
                    return false
                }
                Files.copy(input, libDst, StandardCopyOption.REPLACE_EXISTING)
            }

            // Extract the bundled font.
            val fontDst = baseDir.resolve("cozette.bdf")
            MltermNative::class.java.getResourceAsStream("/scev/mlterm-fonts/cozette.bdf").use { input ->
                if (input == null) {
                    LOG.warn("libscev_term: bundled cozette.bdf missing from jar")
                    return false
                }
                Files.copy(input, fontDst, StandardCopyOption.REPLACE_EXISTING)
            }

            System.load(libDst.toAbsolutePath().toString())

            // Lock mlterm out of all host config reads + inject our
            // bundled font path. After this returns 1, no scev_term
            // call ever touches ~/.mlterm or fontconfig.
            if (!lekkit.mlterm.Mlterm.nativeInit(fontDst.toAbsolutePath().toString())) {
                LOG.error("libscev_term: nativeInit failed (font={}). " +
                          "Terminal opens will fail.", fontDst)
                return false
            }

            LOG.info("Loaded libscev_term from {} (font={})", libDst, fontDst)
            true
        } catch (e: IOException) {
            LOG.error("libscev_term: extract / load failed", e)
            false
        } catch (e: UnsatisfiedLinkError) {
            LOG.error("libscev_term: System.load failed", e)
            false
        }
    }

    private fun detectClassifier(): String? {
        val osn = System.getProperty("os.name", "").lowercase(Locale.ROOT)
        val arch = System.getProperty("os.arch", "").lowercase(Locale.ROOT)
        val normArch = when (arch) {
            "amd64", "x86_64" -> "x86_64"
            "aarch64", "arm64" -> "aarch64"
            else -> return null
        }
        return when {
            "linux" in osn   -> "linux-$normArch"
            "mac" in osn     -> "macos-$normArch"
            "windows" in osn -> "windows-$normArch"
            else -> null
        }
    }
}
