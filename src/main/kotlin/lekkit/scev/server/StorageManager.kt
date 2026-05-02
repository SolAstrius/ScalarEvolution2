/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server

import com.mojang.logging.LogUtils
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.EnumSet
import java.util.UUID
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.storage.LevelResource

/**
 * Disk image helper.
 *
 * **Layout.**
 *  - **Per-world (mutable):** `<world>/scev/images/<uuid>.img` and
 *    `<world>/scev/snapshots/<uuid>.img`. Rebound on each [onServerStarting].
 *    Keeps NVMe contents tied to the save, so backing up / copying / deleting
 *    a world Just Works.
 *  - **Shared (read-only):** `./scev/assets/` next to the game directory.
 *    Firmware + template blobs extracted from the mod jar (or user
 *    overrides) — no reason to duplicate per world.
 *
 * Before a server starts (tests, datagen, dedicated-server startup before
 * the level is ready), the mutable dirs fall back to `./scev/images/` and
 * `./scev/snapshots/` so the API is always safe to call.
 */
object StorageManager {
    private val LOG = LogUtils.getLogger()

    private val CWD_ROOT: Path = Paths.get("scev")
    private val ASSETS: Path = CWD_ROOT.resolve("assets")
    private val FALLBACK_IMAGES: Path = CWD_ROOT.resolve("images")
    private val FALLBACK_SNAPSHOTS: Path = CWD_ROOT.resolve("snapshots")

    /**
     * Current mutable roots. Rebound on server start; default to the CWD
     * fallback so tests + pre-server callers don't NPE. Reads are
     * per-tick-ish; @Volatile is cheaper than a synchronized getter.
     */
    @Volatile private var images: Path = FALLBACK_IMAGES
    @Volatile private var snapshots: Path = FALLBACK_SNAPSHOTS

    init {
        try {
            Files.createDirectories(ASSETS)
            Files.createDirectories(FALLBACK_IMAGES)
            Files.createDirectories(FALLBACK_SNAPSHOTS)
        } catch (_: IOException) {
            // Directories will be lazy-created later.
        }
    }

    /**
     * Rebind the mutable image / snapshot roots to live inside the active
     * world's save folder. Called from `ServerStartingEvent`. Safe to call
     * more than once (singleplayer enters/exits worlds repeatedly) and
     * idempotent when the server's level path hasn't changed.
     */
    @JvmStatic fun onServerStarting(server: MinecraftServer) {
        val worldRoot = server.getWorldPath(LevelResource.ROOT).resolve("scev")
        val newImages = worldRoot.resolve("images")
        val newSnapshots = worldRoot.resolve("snapshots")
        try {
            Files.createDirectories(newImages)
            Files.createDirectories(newSnapshots)
        } catch (e: IOException) {
            LOG.warn("Failed to create per-world scev dirs under {}: {} — falling back to ./scev",
                worldRoot, e.message)
            return
        }
        images = newImages
        snapshots = newSnapshots
        LOG.info("scev disk images bound to {}", worldRoot)
    }

    /**
     * Release per-world bindings on `ServerStoppingEvent`. Subsequent callers
     * see the CWD fallback until the next server starts. Prevents a just-
     * stopped singleplayer world from leaking its path into the next world
     * entered in the same JVM.
     */
    @JvmStatic fun onServerStopping() {
        images = FALLBACK_IMAGES
        snapshots = FALLBACK_SNAPSHOTS
    }

    @JvmStatic fun imagePath(imageUuid: UUID): String = images.resolve("$imageUuid.img").toString()
    @JvmStatic fun snapshotPath(machineUuid: UUID): String = snapshots.resolve("$machineUuid.img").toString()
    @JvmStatic fun assetPath(asset: String): String = ASSETS.resolve(asset).toString()
    @JvmStatic fun checkImage(imageUuid: UUID): Boolean = Files.isRegularFile(images.resolve("$imageUuid.img"))

    /**
     * Create a sparse image file of [imageMb] megabytes. `FileChannel.truncate`
     * only shrinks — to grow without writing N MiB of zeros, write a single
     * byte at the last offset, which extends the file sparsely on
     * macOS / Linux / modern Windows.
     */
    @JvmStatic fun createImage(imageUuid: UUID, imageMb: Long): Boolean {
        if (imageMb <= 0) return false
        val path = images.resolve("$imageUuid.img")
        return try {
            FileChannel.open(path,
                EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.SPARSE, StandardOpenOption.WRITE)
            ).use { ch ->
                val lenBytes = imageMb shl 20
                ch.position(lenBytes - 1)
                ch.write(ByteBuffer.wrap(byteArrayOf(0)))
                ch.size() == lenBytes
            }
        } catch (_: IOException) {
            checkImage(imageUuid)
        }
    }

    /**
     * Copy a template asset into the per-UUID disk image slot.
     *
     * Asset lookup priority:
     *   1. [FirmwareAssets.ensureExtracted] — pulls a classpath-bundled copy
     *      from the mod jar to `./scev/assets/<origin>` if needed; also
     *      catches user-supplied copies (the user-wins rule lives in
     *      FirmwareAssets).
     *   2. Direct path under `./scev/assets/<origin>` — legacy path for
     *      assets FirmwareAssets doesn't recognise (e.g. user-dropped HDD
     *      templates).
     *
     * Copy is **sparse-preserving**: zero-filled 64 KiB chunks are skipped
     * (filesystem hole at the destination), so a 1 GiB mostly-empty ext4
     * template materialises as ~60–80 MiB of actual disk usage. A plain
     * `Files.copy` would write the zeros byte-for-byte.
     */
    @JvmStatic fun copyImage(imageUuid: UUID, origin: String): Boolean {
        val source: Path? = FirmwareAssets.resolveAbsolutePath(origin)?.let { Paths.get(it) }
            ?: Paths.get(assetPath(origin)).takeIf { Files.isRegularFile(it) }
        if (source != null) {
            try {
                // Intentionally no REPLACE_EXISTING: callers (initImage) only
                // enter this path when the per-UUID image doesn't yet exist;
                // a concurrent re-entry must NOT clobber user data another
                // caller just finished writing.
                sparseCopy(source, images.resolve("$imageUuid.img"))
            } catch (_: IOException) {
                // fall through — checkImage below reports the truth
            }
        }
        return checkImage(imageUuid)
    }

    /**
     * Byte-identical file copy that preserves sparse regions. Reads the
     * source in 64 KiB chunks; any chunk entirely zero is skipped — the
     * destination channel's position advances past it, leaving a filesystem
     * hole. Final logical size is enforced by writing a zero byte at
     * `source.size() - 1` when the last chunk was skipped.
     *
     * On APFS / ext4 / NTFS this yields real-disk-usage roughly equal to the
     * non-zero content of the source. 64 KiB chunk: large enough to avoid
     * per-chunk syscall overhead; small enough that a 4 KiB FS block of
     * non-zero data doesn't pull a whole MiB into actual allocation.
     */
    @JvmStatic internal fun sparseCopy(source: Path, target: Path) {
        val chunk = 64 * 1024
        FileChannel.open(source, StandardOpenOption.READ).use { input ->
            FileChannel.open(target,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SPARSE
            ).use { out ->
                val sourceSize = input.size()
                val buf = ByteBuffer.allocate(chunk)
                var pos = 0L
                var lastChunkSkipped = false
                while (pos < sourceSize) {
                    buf.clear()
                    val n = input.read(buf, pos)
                    if (n <= 0) break
                    if (isAllZero(buf.array(), n)) {
                        pos += n
                        lastChunkSkipped = true
                        continue
                    }
                    buf.limit(n).position(0)
                    out.position(pos)
                    var written = 0
                    while (written < n) written += out.write(buf)
                    pos += n
                    lastChunkSkipped = false
                }
                // If we short-circuited a trailing zero chunk, the output is
                // short by that tail. Re-extend to the source's logical size
                // so downstream (growImage, the NVMe device) sees the same
                // capacity the source advertised.
                if ((lastChunkSkipped || out.size() < sourceSize) && sourceSize > 0) {
                    out.position(sourceSize - 1)
                    out.write(ByteBuffer.wrap(byteArrayOf(0)))
                }
            }
        }
    }

    /**
     * True iff the first [len] bytes of [buf] are all zero. Hot path inside
     * [sparseCopy]; kept as a method so JIT can inline it and so the
     * byte-equality check is in one obvious place.
     */
    private fun isAllZero(buf: ByteArray, len: Int): Boolean {
        for (i in 0 until len) if (buf[i] != 0.toByte()) return false
        return true
    }

    /**
     * Sparse-extend an existing per-UUID image to [imageMb]. No-op when the
     * file is already at least that size — never truncates. Used by
     * [initImage] to reconcile "declared disk capacity" (what the item
     * tooltip advertises and what the guest sees as NVMe size) with
     * "template file size" (what the shipped classpath asset weighs).
     *
     * The guest's filesystem is not automatically grown — that needs a
     * guest-side `resize2fs` pass. This method just ensures the host-side
     * block device is big enough for the guest to expand into.
     */
    @JvmStatic internal fun growImage(imageUuid: UUID, imageMb: Long): Boolean {
        if (imageMb <= 0) return true
        val path = images.resolve("$imageUuid.img")
        if (!Files.isRegularFile(path)) return false
        val targetBytes = imageMb shl 20
        return try {
            FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.READ).use { ch ->
                if (ch.size() >= targetBytes) return@use true   // already ≥ declared cap
                // Same sparse-extend trick createImage uses.
                ch.position(targetBytes - 1)
                ch.write(ByteBuffer.wrap(byteArrayOf(0)))
                ch.size() == targetBytes
            }
        } catch (e: IOException) {
            LOG.warn("Failed to grow image {} to {} MiB: {}", imageUuid, imageMb, e.message)
            false
        }
    }

    /**
     * Ensure an image exists for [imageUuid] and matches the declared
     * [imageMb]. Priority:
     *   1. If the image exists, sparse-extend it to [imageMb] if smaller.
     *      Guarantees the "declared capacity" invariant for disks allocated
     *      by older mod versions that shipped smaller templates.
     *   2. If [origin] is non-null, try copying the template, then sparse-
     *      extend. Templates ship at whatever size the build recipe
     *      produces; growing here lets the guest `resize2fs` to the full
     *      advertised size on first boot.
     *   3. Fall back to creating a blank sparse image at [imageMb]. Used by
     *      blank `NvmeItem`s and when the mod ships without asset templates.
     */
    @JvmStatic fun initImage(imageUuid: UUID, imageMb: Long, origin: String?): Boolean {
        if (checkImage(imageUuid)) {
            growImage(imageUuid, imageMb)
            return true
        }
        if (origin != null && copyImage(imageUuid, origin)) {
            growImage(imageUuid, imageMb)
            return true
        }
        return createImage(imageUuid, imageMb)
    }
}
