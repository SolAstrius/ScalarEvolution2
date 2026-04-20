/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.EnumSet;
import java.util.UUID;

/**
 * Disk image helper. Images live under {@code ./scev/images/&lt;uuid&gt;.img}; read-only
 * boot-strap payloads live under {@code ./scev/assets/}.
 */
public final class StorageManager {
    private static final Path ROOT = Paths.get("scev");
    private static final Path ASSETS = ROOT.resolve("assets");
    private static final Path IMAGES = ROOT.resolve("images");
    private static final Path SNAPSHOTS = ROOT.resolve("snapshots");

    static {
        try {
            Files.createDirectories(ASSETS);
            Files.createDirectories(IMAGES);
            Files.createDirectories(SNAPSHOTS);
        } catch (IOException ignore) {
            // Directories will be lazy-created later.
        }
    }

    private StorageManager() {}

    public static String imagePath(UUID imageUuid) {
        return IMAGES.resolve(imageUuid + ".img").toString();
    }

    public static String snapshotPath(UUID machineUuid) {
        return SNAPSHOTS.resolve(machineUuid + ".img").toString();
    }

    public static String assetPath(String asset) {
        return ASSETS.resolve(asset).toString();
    }

    public static boolean checkImage(UUID imageUuid) {
        return Files.isRegularFile(IMAGES.resolve(imageUuid + ".img"));
    }

    /**
     * Create a sparse image file of {@code imageMb} megabytes at the conventional
     * path. {@link FileChannel#truncate} only shrinks — it can't grow an empty
     * file. To force the file's length without writing {@code imageMb} MiB of
     * zeros, we write a single byte at the last offset which grows the file
     * sparsely on macOS / Linux / modern Windows.
     */
    public static boolean createImage(UUID imageUuid, long imageMb) {
        if (imageMb <= 0) return false;
        Path path = IMAGES.resolve(imageUuid + ".img");
        try (FileChannel ch = FileChannel.open(
                path,
                EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.SPARSE, StandardOpenOption.WRITE))) {
            long lenBytes = imageMb << 20;
            // Write a zero byte at (lenBytes - 1) to extend the file sparsely.
            ch.position(lenBytes - 1);
            ch.write(java.nio.ByteBuffer.wrap(new byte[] { 0 }));
            return ch.size() == lenBytes;
        } catch (IOException ignore) {
            return checkImage(imageUuid);
        }
    }

    /**
     * Copy a template asset into the per-UUID disk image slot.
     *
     * <p>Asset lookup priority:
     * <ol>
     *   <li>{@link FirmwareAssets#ensureExtracted(String)} — pulls a classpath-bundled
     *       copy from the mod jar to {@code ./scev/assets/&lt;origin&gt;} if needed.
     *       Also catches user-supplied copies already on disk (the user-wins rule
     *       lives inside FirmwareAssets).</li>
     *   <li>Direct path under {@code ./scev/assets/&lt;origin&gt;} — legacy path for
     *       assets that aren't recognized by FirmwareAssets (e.g. HDD templates
     *       the user has manually dropped in).</li>
     * </ol>
     *
     * <p>Returns {@code true} iff the image now exists at the per-UUID path.
     */
    public static boolean copyImage(UUID imageUuid, String origin) {
        Path source = null;
        String bundled = FirmwareAssets.resolveAbsolutePath(origin);
        if (bundled != null) {
            source = Paths.get(bundled);
        } else {
            Path legacy = Paths.get(assetPath(origin));
            if (Files.isRegularFile(legacy)) source = legacy;
        }
        if (source != null) {
            try {
                // Intentionally no REPLACE_EXISTING: callers (namely initImage)
                // only enter this path when the per-UUID image doesn't yet
                // exist, and a concurrent re-entry must NOT clobber user data
                // that another caller just finished writing.
                Files.copy(source, IMAGES.resolve(imageUuid + ".img"),
                        StandardCopyOption.COPY_ATTRIBUTES);
            } catch (IOException ignore) {
                // fall through
            }
        }
        return checkImage(imageUuid);
    }

    /**
     * Ensure an image exists for {@code imageUuid}. Priority:
     * <ol>
     *   <li>If the image already exists on disk, return true.</li>
     *   <li>If {@code origin} is non-null, try copying the template. If that
     *       succeeds, return true.</li>
     *   <li>Otherwise (including when the template copy failed because the
     *       asset wasn't shipped), fall back to creating a blank sparse image.
     *       A blank flash is useful as "empty storage" — e.g. a flash chip
     *       without firmware still boots the splash; a blank NVMe lets the
     *       user install an OS themselves.</li>
     * </ol>
     */
    public static boolean initImage(UUID imageUuid, long imageMb, String origin) {
        if (checkImage(imageUuid)) return true;
        if (origin != null && copyImage(imageUuid, origin)) return true;
        // Fallback: create a blank sparse image at the declared size. This keeps
        // the attach path working when the mod ships without asset templates —
        // the BootSplash will still paint, and bootrom-loading will return
        // false rather than crashing.
        return createImage(imageUuid, imageMb);
    }
}
