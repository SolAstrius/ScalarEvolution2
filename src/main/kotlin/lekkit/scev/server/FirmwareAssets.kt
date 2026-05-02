/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server

import com.mojang.logging.LogUtils
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.zip.CRC32

/**
 * Ships read-only firmware/OS blobs from the mod jar into `./scev/assets/`
 * on first use. Backbone of the "out-of-box" experience: a freshly installed
 * mod boots real OpenSBI + U-Boot instead of a 16-byte demo loop because
 * this class materialises `fw_payload.bin` (and any other bundled blob)
 * before [StorageManager.copyImage] goes looking for it.
 *
 * **Asset lookup priority.**
 *  1. **Classpath (bundled)** — `/assets/scev/firmware/<name>` inside the
 *     mod jar. Extracted to disk on first request.
 *  2. **Local disk (user-supplied)** — `./scev/assets/<name>`. If the user
 *     drops their own firmware/kernel, we use it in preference to extracting
 *     the bundled copy *only* when no bundled copy exists. To intentionally
 *     override the bundled copy, set `scev.firmware.force_bundled=false`
 *     and place the file at `./scev/assets/<name>`.
 *
 * **Extraction semantics.**
 *  - Idempotent: [ensureExtracted] is a no-op on subsequent calls.
 *  - Atomic: writes to a temp file under the same dir, then renames.
 *  - Fail-soft: extraction failure logs a warning and returns `null`;
 *    callers must check before using.
 */
object FirmwareAssets {
    private val LOG = LogUtils.getLogger()

    /** Classpath prefix for bundled firmware resources. */
    @JvmField val CLASSPATH_PREFIX: String = "/assets/scev/firmware/"

    /** On-disk directory where extracted + user-supplied assets live. */
    private val ASSETS_DIR: Path = Paths.get("scev", "assets")

    /**
     * Canonical name of the default M-mode firmware blob (OpenSBI + U-Boot).
     * Matches `FlashItem`'s declared origin.
     */
    @JvmField val DEFAULT_FIRMWARE: String = "fw_payload.bin"

    init {
        try { Files.createDirectories(ASSETS_DIR) } catch (_: IOException) {
            // Will be lazily created on first extraction attempt.
        }
    }

    /**
     * Is there a classpath-bundled copy of [name]? Decided at runtime by
     * peeking at the mod jar's resource table — removing a firmware file
     * from the jar immediately switches any caller to the user-supplied
     * path without code changes.
     */
    @JvmStatic fun isBundled(name: String): Boolean =
        FirmwareAssets::class.java.getResource(CLASSPATH_PREFIX + name) != null

    /**
     * Is the asset available — bundled in the jar or already on disk.
     * Callers use this as a precondition before passing the path to RVVM's
     * `load_bootrom` / `load_kernel`.
     */
    @JvmStatic fun isAvailable(name: String): Boolean =
        isBundled(name) || Files.isRegularFile(ASSETS_DIR.resolve(name))

    /**
     * The on-disk path an asset *would* live at if present. Does not create
     * or extract anything. Useful for building paths populated later.
     */
    @JvmStatic fun diskPath(name: String): Path = ASSETS_DIR.resolve(name)

    /**
     * Ensure the named asset exists on disk and return its absolute path.
     *
     * Priority:
     *  - Extracted file's CRC32 matches bundled copy → cache hit, return path.
     *  - Extracted exists but bundled CRC differs → mod's bundled asset was
     *    updated; the extract is stale. Re-extract and warn.
     *  - Extracted exists, no bundled copy → user-supplied fallback. Trust it.
     *  - No extract, bundled present → extract.
     *  - Neither → null, caller handles.
     *
     * Switched from size comparison to CRC32 after an image-contents-differ-
     * but-size-identical bug in the Alpine pipeline. CRC stream costs ~200 ms
     * on a 65 MiB asset per boot — acceptable, runs once-per-asset-per-process.
     *
     * Thread-safe: synchronized so concurrent extraction attempts don't race.
     */
    @JvmStatic @Synchronized fun ensureExtracted(name: String): Path? {
        val target = ASSETS_DIR.resolve(name)
        val targetExists = Files.isRegularFile(target)
        val bundled = isBundled(name)

        if (targetExists && !bundled) {
            // User-supplied fallback — no bundled copy to compare against.
            return target.toAbsolutePath()
        }
        if (targetExists && bundled) {
            val bundledCrc = bundledCrcOrMinusOne(name)
            val targetCrc = fileCrcOrMinusOne(target)
            if (bundledCrc >= 0 && targetCrc >= 0 && targetCrc == bundledCrc) {
                return target.toAbsolutePath()  // CRC match — cache hit
            }
            LOG.warn("Firmware asset {} on disk (CRC={}) differs from bundled copy (CRC={}); " +
                "re-extracting. If you intentionally customised this file, rename it before " +
                "the mod upgrade clobbers it again.",
                name, "%08x".format(targetCrc), "%08x".format(bundledCrc))
            // Fall through to re-extract.
        }
        if (!bundled) {
            LOG.debug("Firmware asset {} not bundled and not on disk", name)
            return null
        }
        try {
            Files.createDirectories(ASSETS_DIR)
        } catch (e: IOException) {
            LOG.warn("Could not create {} for firmware assets", ASSETS_DIR, e)
            return null
        }
        // Write atomically: temp file + move. A crashed extraction never
        // leaves a half-written asset that later callers would silently read.
        val tmp = try {
            Files.createTempFile(ASSETS_DIR, "$name.", ".part")
        } catch (e: IOException) {
            LOG.warn("Could not create temp file under {}", ASSETS_DIR, e)
            return null
        }

        // Sparse-aware extraction — see sparseStreamCopy. The shipped images
        // are mostly-empty ext4; a plain transferTo would write every zero
        // block, costing ~1 GiB for a 1 GiB image that's only ~70 MiB of
        // real data.
        try {
            FirmwareAssets::class.java.getResourceAsStream(CLASSPATH_PREFIX + name).use { input ->
                FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.SPARSE).use { out ->
                    if (input == null) {
                        LOG.warn("Bundled firmware asset {} disappeared between probe and open", name)
                        Files.deleteIfExists(tmp)
                        return null
                    }
                    sparseStreamCopy(input, out)
                }
            }
        } catch (e: IOException) {
            LOG.warn("Failed to extract bundled firmware asset {}", name, e)
            try { Files.deleteIfExists(tmp) } catch (_: IOException) {}
            return null
        }

        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: IOException) {
            // Fallback for filesystems that don't support ATOMIC_MOVE (across mounts).
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            } catch (fatal: IOException) {
                LOG.warn("Failed to place extracted firmware asset {} at {}", name, target, fatal)
                try { Files.deleteIfExists(tmp) } catch (_: IOException) {}
                return null
            }
        }
        LOG.info("Extracted bundled firmware asset {} to {}", name, target)
        return target.toAbsolutePath()
    }

    /**
     * Absolute path of the extracted asset, or null if it can't be made
     * available. Convenience for call sites that want the path directly.
     */
    @JvmStatic fun resolveAbsolutePath(name: String): String? =
        ensureExtracted(name)?.toString()

    /** Size in bytes of the on-disk asset (extracts if needed), or -1. */
    @JvmStatic fun sizeBytes(name: String): Long {
        val p = ensureExtracted(name) ?: return -1
        return try { Files.size(p) } catch (_: IOException) { -1 }
    }

    /** Reset extraction state — tests only. Deletes the on-disk copy. */
    @JvmStatic fun forgetExtracted(name: String) {
        try { Files.deleteIfExists(ASSETS_DIR.resolve(name)) } catch (_: IOException) {}
    }

    /* ----- Internals ------------------------------------------------------ */

    /** CRC32 of the on-disk file, or -1 if unreadable. */
    private fun fileCrcOrMinusOne(target: Path): Long = try {
        Files.newInputStream(target).use { streamCrc(it) }
    } catch (_: IOException) { -1 }

    /** CRC32 of the bundled asset, or -1 if not bundled / unreadable. */
    private fun bundledCrcOrMinusOne(name: String): Long = try {
        FirmwareAssets::class.java.getResourceAsStream(CLASSPATH_PREFIX + name)
            ?.use { streamCrc(it) } ?: -1L
    } catch (_: IOException) { -1L }

    /**
     * Stream-to-FileChannel copy that preserves sparseness by skipping 64 KiB
     * zero-filled chunks. Destination [FileChannel.position] advances past
     * each hole — on sparse-capable filesystems (APFS/ext4/NTFS) the skipped
     * region is never allocated.
     *
     * Final file size is the sum of bytes read; when the tail is a zero
     * chunk we extend the file by writing a single zero byte at the end.
     *
     * 64 KiB chunk: amortises syscall cost; small enough that non-zero
     * content surrounded by zeros doesn't pull a whole MiB into allocation.
     */
    private fun sparseStreamCopy(input: InputStream, out: FileChannel) {
        val chunkSize = 64 * 1024
        val buf = ByteArray(chunkSize)
        var pos = 0L
        var lastChunkSkipped = false
        while (true) {
            val filled = fillBuffer(input, buf)
            if (filled == 0) break
            if (isAllZero(buf, filled)) {
                pos += filled
                lastChunkSkipped = true
                continue
            }
            out.position(pos)
            val bb = ByteBuffer.wrap(buf, 0, filled)
            var written = 0
            while (written < filled) written += out.write(bb)
            pos += filled
            lastChunkSkipped = false
        }
        // Preserve logical length when the tail was all zeros: write a
        // single zero byte at pos-1 so the file shows the right size.
        if (lastChunkSkipped && pos > 0) {
            out.position(pos - 1)
            out.write(ByteBuffer.wrap(byteArrayOf(0)))
        }
    }

    /**
     * Read as close to [buf].size bytes as the stream provides. Returns the
     * total read. Works around `InputStream.read(byte[])` returning early on
     * a partial buffer even when more data is available — which would make
     * zero-chunk detection misalign from the 64 KiB grid.
     */
    private fun fillBuffer(input: InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val n = input.read(buf, total, buf.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    /** True iff the first [len] bytes of [buf] are all zero. */
    private fun isAllZero(buf: ByteArray, len: Int): Boolean {
        for (i in 0 until len) if (buf[i] != 0.toByte()) return false
        return true
    }

    /**
     * Streaming CRC32 — hashes a stream in 16 KiB chunks. Rolled manually
     * because [java.util.zip.CheckedInputStream] would add a wrapper layer
     * and callers already own the stream lifecycle.
     */
    private fun streamCrc(input: InputStream): Long {
        val crc = CRC32()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            crc.update(buf, 0, n)
        }
        return crc.value
    }
}
