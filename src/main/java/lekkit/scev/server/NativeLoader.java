/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import lekkit.rvvm.RVVMNative;
import org.slf4j.Logger;

/**
 * Loads {@code librvvm} at runtime. Priority:
 * <ol>
 *   <li>system-wide {@code System.loadLibrary("rvvm")} (RVVMNative's own static init)</li>
 *   <li>{@code natives/&lt;os&gt;-&lt;arch&gt;/librvvm.&lt;ext&gt;} extracted from the mod jar</li>
 * </ol>
 *
 * <p>Thread-safe. Calling {@link #ensureLoaded()} multiple times is cheap after the first success.
 */
public final class NativeLoader {
    private static final Logger LOG = LogUtils.getLogger();
    private static volatile Boolean loaded = null;

    private NativeLoader() {}

    public static boolean ensureLoaded() {
        if (loaded != null) return loaded;
        synchronized (NativeLoader.class) {
            if (loaded != null) return loaded;
            loaded = tryLoad();
            return loaded;
        }
    }

    private static boolean tryLoad() {
        if (RVVMNative.isLoaded()) {
            LOG.info("librvvm already loaded (system-wide)");
            return true;
        }

        String classifier = detectClassifier();
        if (classifier == null) {
            LOG.warn("Unsupported OS/arch for librvvm bundled native");
            return false;
        }

        String libName = System.mapLibraryName("rvvm");
        String resourcePath = "/natives/" + classifier + "/" + libName;

        try (InputStream in = NativeLoader.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                LOG.warn("No bundled librvvm for classifier {} (expected at {})", classifier, resourcePath);
                return false;
            }
            Path tempDir = Files.createTempDirectory("scev-native-");
            Path tempLib = tempDir.resolve(libName);
            Files.copy(in, tempLib, StandardCopyOption.REPLACE_EXISTING);
            tempLib.toFile().deleteOnExit();
            boolean ok = RVVMNative.loadLib(tempLib.toAbsolutePath().toString());
            if (ok) {
                LOG.info("Loaded bundled librvvm from {}", tempLib);
            } else {
                LOG.warn("Extracted librvvm but failed ABI check (path: {})", tempLib);
            }
            return ok;
        } catch (IOException e) {
            LOG.error("Failed extracting bundled librvvm", e);
            return false;
        }
    }

    private static String detectClassifier() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String canonicalArch = switch (arch) {
            case "amd64", "x86_64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> arch;
        };
        if (os.contains("mac") || os.contains("darwin")) return "macos-" + canonicalArch;
        if (os.contains("linux")) return "linux-" + canonicalArch;
        if (os.contains("win")) return "windows-" + canonicalArch;
        return null;
    }
}
