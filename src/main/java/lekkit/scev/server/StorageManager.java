/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.EnumSet;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

/**
 * Disk image helper.
 *
 * <h2>Layout</h2>
 * <ul>
 *   <li><b>Per-world (mutable):</b> {@code <world>/scev/images/&lt;uuid&gt;.img} and
 *       {@code <world>/scev/snapshots/&lt;uuid&gt;.img}. Rebound on each
 *       {@link #onServerStarting(MinecraftServer)}. This keeps NVMe contents tied
 *       to the save, so backing up / copying / deleting a world Just Works.</li>
 *   <li><b>Shared (read-only):</b> {@code ./scev/assets/} next to the game
 *       directory. Firmware + template blobs extracted from the mod jar (or
 *       user-supplied overrides) live here — no reason to duplicate per world.</li>
 * </ul>
 *
 * <p>Before a server starts (tests, datagen, dedicated-server startup before
 * the level is ready), the mutable dirs fall back to {@code ./scev/images/}
 * and {@code ./scev/snapshots/} so the API is always safe to call. Tests that
 * exercise the disk pipeline without a real server hit the fallback path.
 */
public final class StorageManager {
    private static final Logger LOG = LogUtils.getLogger();

    private static final Path CWD_ROOT = Paths.get("scev");
    private static final Path ASSETS = CWD_ROOT.resolve("assets");
    private static final Path FALLBACK_IMAGES = CWD_ROOT.resolve("images");
    private static final Path FALLBACK_SNAPSHOTS = CWD_ROOT.resolve("snapshots");

    /**
     * Current mutable roots. Rebound on server start; default to the CWD
     * fallback so tests + pre-server call sites don't NPE. Reads are
     * per-tick-ish; a volatile is cheaper than a synchronized getter.
     */
    private static volatile Path images = FALLBACK_IMAGES;
    private static volatile Path snapshots = FALLBACK_SNAPSHOTS;

    static {
        try {
            Files.createDirectories(ASSETS);
            Files.createDirectories(FALLBACK_IMAGES);
            Files.createDirectories(FALLBACK_SNAPSHOTS);
        } catch (IOException ignore) {
            // Directories will be lazy-created later.
        }
    }

    private StorageManager() {}

    /**
     * Rebind the mutable image / snapshot roots to live inside the active
     * world's save folder. Called from {@code ServerStartingEvent}. Safe to
     * call more than once (singleplayer enters/exits worlds repeatedly) and
     * idempotent when the server's level path hasn't changed.
     */
    public static void onServerStarting(MinecraftServer server) {
        Path worldRoot = server.getWorldPath(LevelResource.ROOT).resolve("scev");
        Path newImages = worldRoot.resolve("images");
        Path newSnapshots = worldRoot.resolve("snapshots");
        try {
            Files.createDirectories(newImages);
            Files.createDirectories(newSnapshots);
        } catch (IOException e) {
            LOG.warn("Failed to create per-world scev dirs under {}: {} — falling back to ./scev",
                    worldRoot, e.getMessage());
            return;
        }
        images = newImages;
        snapshots = newSnapshots;
        LOG.info("scev disk images bound to {}", worldRoot);
    }

    /**
     * Release per-world bindings on {@code ServerStoppingEvent}. Subsequent
     * callers see the CWD fallback until the next server starts. Prevents
     * a just-stopped singleplayer world from leaking its path into the next
     * world entered in the same JVM.
     */
    public static void onServerStopping() {
        images = FALLBACK_IMAGES;
        snapshots = FALLBACK_SNAPSHOTS;
    }

    public static String imagePath(UUID imageUuid) {
        return images.resolve(imageUuid + ".img").toString();
    }

    public static String snapshotPath(UUID machineUuid) {
        return snapshots.resolve(machineUuid + ".img").toString();
    }

    public static String assetPath(String asset) {
        return ASSETS.resolve(asset).toString();
    }

    public static boolean checkImage(UUID imageUuid) {
        return Files.isRegularFile(images.resolve(imageUuid + ".img"));
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
        Path path = images.resolve(imageUuid + ".img");
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
     * <p>The copy is <b>sparse-preserving</b>: zero-filled 64 KiB chunks are
     * skipped (leaving a filesystem-level hole at the destination), so a 1 GiB
     * mostly-empty ext4 template materialises as ~60–80 MiB of actual disk
     * usage rather than a full 1 GiB per NVMe item. A plain
     * {@link Files#copy(Path, Path, java.nio.file.CopyOption...)} would write
     * the zeros byte-for-byte, and three preloaded NVMes would cost 3 GiB of
     * host storage. See {@link #sparseCopy}.
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
                sparseCopy(source, images.resolve(imageUuid + ".img"));
            } catch (IOException ignore) {
                // fall through — checkImage below reports the truth
            }
        }
        return checkImage(imageUuid);
    }

    /**
     * Byte-identical file copy that preserves sparse regions. Reads the source
     * in 64 KiB chunks; any chunk that is entirely zero is <i>not</i> written
     * to the destination — the destination channel's position advances past
     * it, leaving a filesystem hole. The final logical size is enforced by
     * writing a zero byte at {@code source.size() - 1} when the last chunk of
     * the source was skipped.
     *
     * <p>On APFS / ext4 / NTFS this yields real-disk-usage roughly equal to
     * the non-zero content of the source, not the source's logical size — the
     * point of the exercise.
     *
     * <p>64 KiB chunk size is a compromise: large enough to avoid per-chunk
     * syscall overhead dominating throughput, small enough that a 4 KiB
     * filesystem block's worth of non-zero data doesn't pull a whole MiB
     * into actual allocation.
     */
    static void sparseCopy(Path source, Path target) throws IOException {
        final int chunk = 64 * 1024;
        try (FileChannel in = FileChannel.open(source, StandardOpenOption.READ);
             FileChannel out = FileChannel.open(target,
                     StandardOpenOption.CREATE_NEW,
                     StandardOpenOption.WRITE,
                     StandardOpenOption.SPARSE)) {
            long sourceSize = in.size();
            ByteBuffer buf = ByteBuffer.allocate(chunk);
            long pos = 0;
            boolean lastChunkSkipped = false;
            while (pos < sourceSize) {
                buf.clear();
                int n = in.read(buf, pos);
                if (n <= 0) break;
                if (isAllZero(buf.array(), n)) {
                    pos += n;
                    lastChunkSkipped = true;
                    continue;
                }
                buf.limit(n).position(0);
                out.position(pos);
                int written = 0;
                while (written < n) written += out.write(buf);
                pos += n;
                lastChunkSkipped = false;
            }
            // If we short-circuited a trailing zero chunk, the output file is
            // now short by that tail. Re-extend it to the source's logical
            // size so downstream callers (growImage, the NVMe device) see
            // the same capacity the source advertised.
            if (lastChunkSkipped || out.size() < sourceSize) {
                if (sourceSize > 0) {
                    out.position(sourceSize - 1);
                    out.write(ByteBuffer.wrap(new byte[] { 0 }));
                }
            }
        }
    }

    /**
     * True iff the first {@code len} bytes of {@code buf} are all zero.
     * Hot path inside {@link #sparseCopy}; kept in a method so JIT can inline
     * it and so the byte-equality check is in one obvious place.
     */
    private static boolean isAllZero(byte[] buf, int len) {
        for (int i = 0; i < len; i++) {
            if (buf[i] != 0) return false;
        }
        return true;
    }

    /**
     * Sparse-extend an existing per-UUID image to {@code imageMb}. A no-op when
     * the file is already at least that size — never truncates. Used by
     * {@link #initImage} to reconcile "declared disk capacity" (what the item
     * tooltip advertises and what the guest kernel sees as the NVMe device
     * size) with "template file size" (what the shipped classpath asset
     * happens to weigh on disk).
     *
     * <p>The guest's filesystem is not automatically grown — that needs a
     * guest-side {@code resize2fs} pass (the scev-mod Buildroot rootfs ships
     * one in its {@code /init} pivot script). What this method does is ensure
     * the host-side block device is big enough for the guest to expand into.
     *
     * <p>No-op if the file isn't present; the caller was expected to land it
     * via {@link #copyImage} or {@link #createImage} first.
     */
    static boolean growImage(UUID imageUuid, long imageMb) {
        if (imageMb <= 0) return true;
        Path path = images.resolve(imageUuid + ".img");
        if (!Files.isRegularFile(path)) return false;
        long targetBytes = imageMb << 20;
        try (FileChannel ch = FileChannel.open(path,
                StandardOpenOption.WRITE, StandardOpenOption.READ)) {
            if (ch.size() >= targetBytes) return true; // already ≥ declared cap
            // Same sparse-extend trick createImage uses: seek to targetBytes-1
            // and write a zero byte. The underlying filesystem allocates a
            // single terminal block and leaves everything before it as a
            // sparse hole — no per-MiB cost on the host until the guest
            // actually writes there.
            ch.position(targetBytes - 1);
            ch.write(java.nio.ByteBuffer.wrap(new byte[] { 0 }));
            return ch.size() == targetBytes;
        } catch (IOException e) {
            LOG.warn("Failed to grow image {} to {} MiB: {}", imageUuid, imageMb, e.getMessage());
            return false;
        }
    }

    /**
     * Ensure an image exists for {@code imageUuid} and matches the declared
     * {@code imageMb} capacity. Priority:
     *
     * <ol>
     *   <li>If the image already exists on disk, sparse-extend it to
     *       {@code imageMb} if smaller, then return true. Guarantees the
     *       "declared capacity" invariant for disks allocated by older mod
     *       versions that shipped smaller templates.</li>
     *   <li>If {@code origin} is non-null, try copying the template. Then
     *       sparse-extend to {@code imageMb} — templates ship at whatever
     *       size the build recipe happens to produce (e.g. a 64 MiB
     *       Buildroot ext4), which is typically smaller than the declared
     *       1 GiB NvmeItem capacity. Growing here lets the guest
     *       {@code resize2fs} to the full advertised size on first boot.</li>
     *   <li>Otherwise, fall back to creating a blank sparse image at
     *       {@code imageMb}. Useful for blank {@code NvmeItem}s and for
     *       workstations where the mod ships without the asset template.</li>
     * </ol>
     */
    public static boolean initImage(UUID imageUuid, long imageMb, String origin) {
        if (checkImage(imageUuid)) {
            // Existing image may have been allocated by an older mod version
            // that shipped a smaller template. Grow it to match the current
            // declared capacity — cheap sparse-extend on existing data.
            growImage(imageUuid, imageMb);
            return true;
        }
        if (origin != null && copyImage(imageUuid, origin)) {
            growImage(imageUuid, imageMb);
            return true;
        }
        // Fallback: create a blank sparse image at the declared size. This keeps
        // the attach path working when the mod ships without asset templates —
        // the BootSplash will still paint, and bootrom-loading will return
        // false rather than crashing.
        return createImage(imageUuid, imageMb);
    }
}
