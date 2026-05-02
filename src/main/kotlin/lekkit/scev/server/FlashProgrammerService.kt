/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server

import com.mojang.logging.LogUtils
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import lekkit.scev.blockentity.FlashProgrammerBlockEntity
import lekkit.scev.items.FirmwareBlob
import lekkit.scev.items.FlashItem
import lekkit.scev.items.StorageItem
import lekkit.scev.main.ScevDataComponents
import lekkit.scev.menu.FlashProgrammerMenu
import net.minecraft.Util
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.server.ServerLifecycleHooks

/**
 * Executes a flash-programmer write: slurp the first [FirmwareBlob.MAX_SIZE]
 * bytes of the source NVMe's backing image file, stamp them onto the target
 * flash's `FIRMWARE_BYTES` data component. Returns a status enum the screen
 * turns into a message.
 *
 * **Threading.** Disk reads happen off the server thread via [Util.ioPool];
 * the final component mutation + [FlashProgrammerBlockEntity.setChanged]
 * hops back to the server tick thread via the [MinecraftServer]'s
 * `Executor` (it's a `BlockableEventLoop` underneath). Prior to this, the
 * whole operation — up to 2 MiB of file I/O — ran inline in the packet
 * handler on the tick thread and stalled the server for one click.
 *
 * **Authoring loop.**
 *   1. Boot Linux in a workstation.
 *   2. Compile or otherwise produce a firmware binary on the guest.
 *   3. Write it to the start of the NVMe raw block device (usually
 *      `/dev/nvme0n1`) — `dd if=firmware.bin of=/dev/nvme0n1 bs=512 conv=fsync`.
 *   4. Shut the VM down. Remove the NVMe.
 *   5. Drop the NVMe into the programmer's left slot, blank flash into the
 *      right, press Write. The target chip carries those bytes in
 *      `FIRMWARE_BYTES`.
 *
 * Because `dd` wipes any filesystem/partition table on the NVMe, players
 * typically use a dedicated NVMe as the firmware donor.
 */
object FlashProgrammerService {
    private val LOG = LogUtils.getLogger()

    /**
     * Execute one write click asynchronously. Disk I/O runs off the server
     * thread; the returned future completes on the server thread with the
     * final [FlashProgrammerMenu.WriteStatus].
     *
     * Must be called from the server thread (the caller holds an [ItemStack]
     * reference that's only safe to read there).
     */
    @JvmStatic fun writeAsync(prog: FlashProgrammerBlockEntity): CompletableFuture<FlashProgrammerMenu.WriteStatus> {
        val source = prog.getItem(FlashProgrammerBlockEntity.SLOT_SOURCE)
        val target = prog.getItem(FlashProgrammerBlockEntity.SLOT_TARGET)

        if (source.isEmpty || source.item !is StorageItem || source.item is FlashItem) {
            return CompletableFuture.completedFuture(FlashProgrammerMenu.WriteStatus.NO_SOURCE)
        }
        if (target.isEmpty || target.item !is FlashItem) {
            return CompletableFuture.completedFuture(FlashProgrammerMenu.WriteStatus.NO_TARGET)
        }

        val storage = source.item as StorageItem
        // Never been initialized by any machine — no image file to read.
        val uuid = storage.getUuid(source)
            ?: return CompletableFuture.completedFuture(FlashProgrammerMenu.WriteStatus.UNREADABLE_SOURCE)

        val server = ServerLifecycleHooks.getCurrentServer()
        if (server == null) {
            // Outside a running server (unit test, shutting-down, ...) —
            // no "home" thread for the async path to return to, so degrade
            // to a blocking read on the caller thread.
            val bytes = readSourceImage(uuid)
            return CompletableFuture.completedFuture(applyWrite(prog, bytes))
        }

        return CompletableFuture
            .supplyAsync({ readSourceImage(uuid) }, Util.ioPool())
            .thenApplyAsync({ bytes -> applyWrite(prog, bytes) }, server)
    }

    /**
     * Synchronous variant — full read+apply on the caller thread. Prefer
     * [writeAsync] in server code; this exists for tests and one-shot tools.
     */
    @JvmStatic fun write(prog: FlashProgrammerBlockEntity): FlashProgrammerMenu.WriteStatus =
        writeAsync(prog).join()

    /**
     * Server-thread stage of the write: validate target, size-check the
     * bytes, set the data component, mark the BE changed.
     */
    private fun applyWrite(prog: FlashProgrammerBlockEntity, bytes: ByteArray?): FlashProgrammerMenu.WriteStatus {
        val target = prog.getItem(FlashProgrammerBlockEntity.SLOT_TARGET)
        if (target.isEmpty || target.item !is FlashItem) {
            // Player yanked the target chip out mid-read.
            return FlashProgrammerMenu.WriteStatus.NO_TARGET
        }
        if (bytes == null) return FlashProgrammerMenu.WriteStatus.UNREADABLE_SOURCE
        if (bytes.size > FirmwareBlob.MAX_SIZE) {
            // Defensive — the reader caps at MAX_SIZE, but guard against a
            // future code path returning unbounded content.
            return FlashProgrammerMenu.WriteStatus.TOO_LARGE
        }

        // Stamp the bytes. Clear the typed-kind tags so FIRMWARE_BYTES is
        // the sole authoritative source on the target (parser precedence
        // puts bytes first anyway — leaving stale tags is confusing).
        target.set(ScevDataComponents.FIRMWARE_BYTES.get(), FirmwareBlob(bytes))
        target.remove(ScevDataComponents.FIRMWARE_KIND.get())
        target.remove(ScevDataComponents.FIRMWARE_ID_OVERRIDE.get())
        prog.setItem(FlashProgrammerBlockEntity.SLOT_TARGET, target)
        prog.setChanged()

        LOG.info("FlashProgrammer: wrote {} bytes from NVMe to target flash at {}",
            bytes.size, prog.blockPos)
        return FlashProgrammerMenu.WriteStatus.OK
    }

    /**
     * Read the first [FirmwareBlob.MAX_SIZE] bytes of the source storage's
     * backing image file. Returns null when the image doesn't exist yet
     * (fresh NVMe never powered on) or is empty. Short files return at
     * their actual length.
     *
     * Runs on [Util.ioPool] — do not touch Minecraft state from here.
     */
    private fun readSourceImage(uuid: UUID): ByteArray? {
        val imagePath = Path.of(StorageManager.imagePath(uuid))
        if (!Files.isRegularFile(imagePath)) return null

        return try {
            Files.newInputStream(imagePath).use { input ->
                val buf = ByteArray(FirmwareBlob.MAX_SIZE)
                var total = 0
                while (total < buf.size) {
                    val n = input.read(buf, total, buf.size - total)
                    if (n < 0) break
                    total += n
                }
                when {
                    total == 0         -> null
                    total == buf.size  -> buf
                    else               -> buf.copyOfRange(0, total)
                }
            }
        } catch (e: IOException) {
            LOG.warn("FlashProgrammer: failed to read source image {}", imagePath, e)
            null
        }
    }
}
