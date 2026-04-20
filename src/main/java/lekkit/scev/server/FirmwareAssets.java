/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Ships read-only firmware/OS blobs from the mod jar into {@code ./scev/assets/}
 * on first use. This is the backbone of the "out-of-box" experience: a freshly
 * installed mod boots real OpenSBI + U-Boot instead of a 16-byte demo loop,
 * because this class materializes {@code fw_payload.bin} (and any other bundled
 * blob) before {@link StorageManager#copyImage} goes looking for it.
 *
 * <h2>Asset lookup priority</h2>
 * <ol>
 *   <li><b>Classpath (bundled)</b> — {@code /assets/scev/firmware/&lt;name&gt;} inside
 *       the mod jar. Extracted to disk the first time the asset is requested.</li>
 *   <li><b>Local disk (user-supplied)</b> — {@code ./scev/assets/&lt;name&gt;}. If
 *       the user drops their own firmware/kernel here, we use it in preference
 *       to extracting the bundled copy <i>only</i> when no bundled copy exists.
 *       Users who want to override the bundled copy can delete
 *       {@code ./scev/assets/&lt;name&gt;} <i>and</i> set {@code scev.firmware.force_bundled=false}.</li>
 * </ol>
 *
 * <h2>Extraction semantics</h2>
 * <ul>
 *   <li><b>Idempotent:</b> calling {@link #ensureExtracted} twice is a no-op on
 *       the second call. File existence is checked up front.</li>
 *   <li><b>Atomic:</b> extraction writes to a temp file under the same
 *       directory then renames — no readers ever see a half-written file.</li>
 *   <li><b>Fail-soft:</b> extraction failure logs a warning and returns the
 *       on-disk path anyway. Callers must still {@link #isAvailable check}
 *       before using.</li>
 * </ul>
 *
 * <h2>Why a separate class (not folded into {@link StorageManager})</h2>
 * <ul>
 *   <li>Firmware assets are read-only, fixed-size, shared across all machines.
 *       Disk images are per-UUID, mutable, lazy-created.</li>
 *   <li>Tests for extraction want to point at an ephemeral directory without
 *       touching {@code ./scev/images/}.</li>
 *   <li>Lets {@link StorageManager#copyImage} stay a pure file-copy helper with
 *       no knowledge of the classpath.</li>
 * </ul>
 */
public final class FirmwareAssets {
    private static final Logger LOG = LogUtils.getLogger();

    /** Classpath prefix for bundled firmware resources. */
    public static final String CLASSPATH_PREFIX = "/assets/scev/firmware/";

    /** On-disk directory where extracted + user-supplied assets live. */
    private static final Path ASSETS_DIR = Paths.get("scev", "assets");

    /**
     * Canonical name of the default M-mode firmware blob (OpenSBI + U-Boot).
     * Matches {@link lekkit.scev.items.FlashItem}'s declared origin.
     */
    public static final String DEFAULT_FIRMWARE = "fw_payload.bin";

    static {
        try {
            Files.createDirectories(ASSETS_DIR);
        } catch (IOException ignore) {
            // Will be lazily created on first extraction attempt.
        }
    }

    private FirmwareAssets() {}

    /**
     * Is there a classpath-bundled copy of {@code name}? This is decided at
     * runtime by peeking at the mod jar's resource table — so removing a
     * firmware file from the jar immediately switches any caller to the
     * user-supplied path without any code changes.
     */
    public static boolean isBundled(String name) {
        Objects.requireNonNull(name, "name");
        return FirmwareAssets.class.getResource(CLASSPATH_PREFIX + name) != null;
    }

    /**
     * Is the asset available for use — either bundled in the jar or already
     * extracted/placed on disk. Callers use this as a precondition before
     * passing the path to RVVM's {@code load_bootrom} / {@code load_kernel}.
     */
    public static boolean isAvailable(String name) {
        Objects.requireNonNull(name, "name");
        return isBundled(name) || Files.isRegularFile(ASSETS_DIR.resolve(name));
    }

    /**
     * The on-disk path an asset <i>would</i> live at if present. Does not
     * create or extract anything. Useful for building paths that will be
     * populated later.
     */
    public static Path diskPath(String name) {
        Objects.requireNonNull(name, "name");
        return ASSETS_DIR.resolve(name);
    }

    /**
     * Ensure the named asset exists on disk and return its absolute path.
     *
     * <p>Priority logic:
     * <ul>
     *   <li>Extracted file matches the bundled copy's size → use the extract
     *       (cheap cache hit, avoids re-copying the ~30 MB kernel each boot).</li>
     *   <li>Extracted file exists but bundled copy has different size → the
     *       mod's bundled asset was updated (e.g. new kernel shipped); the
     *       extract is stale. Re-extract and warn.</li>
     *   <li>Extracted file exists and no bundled copy on the classpath →
     *       user-supplied fallback; trust it and use as-is.</li>
     *   <li>No extract and bundled copy present → extract.</li>
     *   <li>Neither → {@code null}, caller handles.</li>
     * </ul>
     *
     * <p>Before April 2026 this method used "extract-wins-silently" semantics,
     * which meant a stale extract from an older mod version kept getting
     * loaded forever even after rebuilding the mod with a newer kernel. Size
     * comparison was added to auto-invalidate the cache on mod upgrade.
     *
     * <p>Thread-safe: synchronized on the class so concurrent extraction
     * attempts don't race.
     */
    public static synchronized @Nullable Path ensureExtracted(String name) {
        Objects.requireNonNull(name, "name");
        Path target = ASSETS_DIR.resolve(name);
        boolean targetExists = Files.isRegularFile(target);
        boolean bundled = isBundled(name);

        if (targetExists && !bundled) {
            // User-supplied fallback — no bundled copy to compare against, so
            // we trust the on-disk file completely.
            return target.toAbsolutePath();
        }
        if (targetExists && bundled) {
            long targetSize = sizeOrMinusOne(target);
            long bundledSize = bundledSizeOrMinusOne(name);
            if (targetSize >= 0 && bundledSize >= 0 && targetSize == bundledSize) {
                // Cache hit — extracted copy matches bundled size, use it.
                return target.toAbsolutePath();
            }
            LOG.warn("Firmware asset {} on disk ({} bytes) differs from bundled copy ({} bytes); "
                            + "re-extracting. If you intentionally customised this file, rename "
                            + "it before the mod upgrade clobbers it again.",
                    name, targetSize, bundledSize);
            // Fall through to re-extract below.
        }
        if (!bundled) {
            LOG.debug("Firmware asset {} not bundled and not on disk", name);
            return null;
        }
        try {
            Files.createDirectories(ASSETS_DIR);
        } catch (IOException e) {
            LOG.warn("Could not create {} for firmware assets", ASSETS_DIR, e);
            return null;
        }
        // Write atomically: temp file + move. A crashed extraction never
        // leaves a half-written asset that later callers would silently read.
        Path tmp;
        try {
            tmp = Files.createTempFile(ASSETS_DIR, name + ".", ".part");
        } catch (IOException e) {
            LOG.warn("Could not create temp file under {}", ASSETS_DIR, e);
            return null;
        }
        try (InputStream in = FirmwareAssets.class.getResourceAsStream(CLASSPATH_PREFIX + name);
             OutputStream out = Files.newOutputStream(tmp)) {
            if (in == null) {
                LOG.warn("Bundled firmware asset {} disappeared between probe and open", name);
                Files.deleteIfExists(tmp);
                return null;
            }
            in.transferTo(out);
        } catch (IOException e) {
            LOG.warn("Failed to extract bundled firmware asset {}", name, e);
            try { Files.deleteIfExists(tmp); } catch (IOException ignore) {}
            return null;
        }
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomic) {
            // Fallback for filesystems that don't support ATOMIC_MOVE (e.g. across mounts).
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException fatal) {
                LOG.warn("Failed to place extracted firmware asset {} at {}", name, target, fatal);
                try { Files.deleteIfExists(tmp); } catch (IOException ignore) {}
                return null;
            }
        }
        LOG.info("Extracted bundled firmware asset {} to {}", name, target);
        return target.toAbsolutePath();
    }

    /**
     * Return the absolute path of an extracted asset, or {@code null} if it
     * can't be made available (neither bundled nor on disk). Convenience for
     * call sites that want the path directly.
     */
    public static @Nullable String resolveAbsolutePath(String name) {
        Path p = ensureExtracted(name);
        return p == null ? null : p.toString();
    }

    /** Size of {@code target} in bytes, or -1 if it can't be stat'd. */
    private static long sizeOrMinusOne(Path target) {
        try {
            return Files.size(target);
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * Size in bytes of the classpath-bundled asset {@code name}, or -1 if it
     * isn't bundled or can't be read. We stream the resource to count bytes —
     * classpath resources don't expose a size attribute through the URL API.
     */
    private static long bundledSizeOrMinusOne(String name) {
        try (InputStream in = FirmwareAssets.class.getResourceAsStream(CLASSPATH_PREFIX + name)) {
            if (in == null) return -1;
            long total = 0;
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) total += n;
            return total;
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * Size in bytes of the on-disk asset (after extraction if needed).
     * Returns -1 when the asset isn't available. Used by tests and by
     * diagnostics so we can report "firmware loaded, size=2.7 MiB" in logs.
     */
    public static long sizeBytes(String name) {
        Path p = ensureExtracted(name);
        if (p == null) return -1;
        try {
            return Files.size(p);
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * Reset extraction state — for tests only. Deletes the on-disk copy if it
     * exists. Does nothing to the bundled resource.
     */
    public static void forgetExtracted(String name) {
        try {
            Files.deleteIfExists(ASSETS_DIR.resolve(name));
        } catch (IOException ignore) {
        }
    }
}
