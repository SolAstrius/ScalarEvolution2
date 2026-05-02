/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server

import com.mojang.logging.LogUtils
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import lekkit.rvvm.RVVMNative

/**
 * Loads `librvvm` at runtime. Priority:
 *   1. system-wide `System.loadLibrary("rvvm")` (RVVMNative's own static init)
 *   2. `natives/<os>-<arch>/librvvm.<ext>` extracted from the mod jar
 *
 * Thread-safe. Calling [ensureLoaded] multiple times is cheap after the first
 * success.
 */
object NativeLoader {
    private val LOG = LogUtils.getLogger()

    @Volatile private var loaded: Boolean? = null

    @JvmStatic fun ensureLoaded(): Boolean {
        loaded?.let { return it }
        return synchronized(NativeLoader::class.java) {
            loaded ?: tryLoad().also { loaded = it }
        }
    }

    /**
     * True iff a previous [ensureLoaded] attempt succeeded. Does NOT trigger
     * a load — preflight validators wanting to report a friendly "native
     * missing" message use this without racing common-setup.
     */
    @JvmStatic fun isLoaded(): Boolean = loaded == true

    private fun tryLoad(): Boolean {
        if (RVVMNative.isLoaded()) {
            LOG.info("librvvm already loaded (system-wide)")
            return true
        }

        val classifier = detectClassifier() ?: run {
            LOG.warn("Unsupported OS/arch for librvvm bundled native")
            return false
        }

        val libName = System.mapLibraryName("rvvm")
        val resourcePath = "/natives/$classifier/$libName"

        return try {
            NativeLoader::class.java.getResourceAsStream(resourcePath).use { input ->
                if (input == null) {
                    LOG.warn("No bundled librvvm for classifier {} (expected at {})", classifier, resourcePath)
                    return false
                }
                val tempDir = Files.createTempDirectory("scev-native-")
                val tempLib = tempDir.resolve(libName)
                Files.copy(input, tempLib, StandardCopyOption.REPLACE_EXISTING)
                tempLib.toFile().deleteOnExit()
                val ok = RVVMNative.loadLib(tempLib.toAbsolutePath().toString())
                if (ok) LOG.info("Loaded bundled librvvm from {}", tempLib)
                else    LOG.warn("Extracted librvvm but failed ABI check (path: {})", tempLib)
                ok
            }
        } catch (e: IOException) {
            LOG.error("Failed extracting bundled librvvm", e)
            false
        }
    }

    private fun detectClassifier(): String? {
        val os = System.getProperty("os.name", "").lowercase(Locale.ROOT)
        val arch = System.getProperty("os.arch", "").lowercase(Locale.ROOT)
        val canonicalArch = when (arch) {
            "amd64", "x86_64" -> "x86_64"
            "aarch64", "arm64" -> "aarch64"
            else -> arch
        }
        return when {
            "mac" in os || "darwin" in os -> "macos-$canonicalArch"
            "linux" in os                 -> "linux-$canonicalArch"
            "win" in os                   -> "windows-$canonicalArch"
            else                          -> null
        }
    }
}
