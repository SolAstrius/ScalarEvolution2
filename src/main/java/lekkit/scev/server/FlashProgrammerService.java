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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import lekkit.scev.blockentity.FlashProgrammerBlockEntity;
import lekkit.scev.items.FirmwareBlob;
import lekkit.scev.items.FlashItem;
import lekkit.scev.items.StorageItem;
import lekkit.scev.main.ScevDataComponents;
import lekkit.scev.menu.FlashProgrammerMenu;
import net.minecraft.Util;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Executes a flash-programmer write: slurp the first {@link FirmwareBlob#MAX_SIZE}
 * bytes of the source NVMe's backing image file, stamp them onto the
 * target flash's {@code FIRMWARE_BYTES} data component. Returns a
 * status enum the screen turns into a message.
 *
 * <h2>Threading</h2>
 *
 * Disk reads happen off the server thread via {@link Util#ioPool()};
 * the final component mutation + {@link FlashProgrammerBlockEntity#setChanged()}
 * hops back to the server tick thread via the {@link MinecraftServer}'s
 * {@link java.util.concurrent.Executor} interface (it's a
 * {@code BlockableEventLoop} underneath). Prior to this, the whole
 * operation — up to 2 MiB of file I/O — ran inline in the packet handler
 * on the tick thread and stalled the server for one click's duration.
 *
 * <h2>Authoring loop</h2>
 *
 * <ol>
 *   <li>Boot Linux in a workstation.</li>
 *   <li>Compile or otherwise produce a firmware binary on the guest.</li>
 *   <li>Write it to the start of the NVMe raw block device (usually
 *       {@code /dev/nvme0n1}) — something like
 *       {@code dd if=firmware.bin of=/dev/nvme0n1 bs=512 conv=fsync}.</li>
 *   <li>Shut the VM down. Remove the NVMe.</li>
 *   <li>Drop the NVMe into the programmer's left slot, a blank flash
 *       into the right slot, press Write. The target chip now carries
 *       those exact bytes in {@code FIRMWARE_BYTES}.</li>
 * </ol>
 *
 * <p>Because the "dd" wipes any filesystem/partition table on the NVMe,
 * players typically use a dedicated NVMe as the firmware donor.
 */
public final class FlashProgrammerService {
    private static final Logger LOG = LogUtils.getLogger();

    private FlashProgrammerService() {}

    /**
     * Execute one write click asynchronously. Disk I/O runs off the
     * server thread; the returned future completes on the server
     * thread with the final {@link FlashProgrammerMenu.WriteStatus}.
     *
     * <p>Must be called from the server thread (the caller holds an
     * {@link ItemStack} reference that's only safe to read there).
     */
    public static CompletableFuture<FlashProgrammerMenu.WriteStatus> writeAsync(
            FlashProgrammerBlockEntity prog) {
        ItemStack source = prog.getItem(FlashProgrammerBlockEntity.SLOT_SOURCE);
        ItemStack target = prog.getItem(FlashProgrammerBlockEntity.SLOT_TARGET);

        if (source.isEmpty() || !(source.getItem() instanceof StorageItem storage)
                || source.getItem() instanceof FlashItem) {
            return CompletableFuture.completedFuture(FlashProgrammerMenu.WriteStatus.NO_SOURCE);
        }
        if (target.isEmpty() || !(target.getItem() instanceof FlashItem)) {
            return CompletableFuture.completedFuture(FlashProgrammerMenu.WriteStatus.NO_TARGET);
        }

        UUID uuid = storage.getUuid(source);
        if (uuid == null) {
            // Never been initialized by any machine — no image file to read.
            return CompletableFuture.completedFuture(FlashProgrammerMenu.WriteStatus.UNREADABLE_SOURCE);
        }

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            // Outside a running server (unit test, shutting-down, ...) —
            // the async path has no "home" thread to return to, so
            // degrade to a blocking read on the caller thread.
            byte[] bytes = readSourceImage(uuid);
            return CompletableFuture.completedFuture(applyWrite(prog, bytes));
        }

        return CompletableFuture
                .supplyAsync(() -> readSourceImage(uuid), Util.ioPool())
                .thenApplyAsync(bytes -> applyWrite(prog, bytes), server);
    }

    /**
     * Synchronous variant — does the full read+apply on the caller
     * thread. Prefer {@link #writeAsync(FlashProgrammerBlockEntity)}
     * in server code; this exists for tests and one-shot tools.
     */
    public static FlashProgrammerMenu.WriteStatus write(FlashProgrammerBlockEntity prog) {
        return writeAsync(prog).join();
    }

    /**
     * Server-thread stage of the write: validate target, size-check
     * the bytes, set the data component, mark the BE changed. Returns
     * the final status for the menu.
     */
    private static FlashProgrammerMenu.WriteStatus applyWrite(
            FlashProgrammerBlockEntity prog, byte @Nullable [] bytes) {
        ItemStack target = prog.getItem(FlashProgrammerBlockEntity.SLOT_TARGET);
        if (target.isEmpty() || !(target.getItem() instanceof FlashItem)) {
            // Player yanked the target chip out mid-read.
            return FlashProgrammerMenu.WriteStatus.NO_TARGET;
        }
        if (bytes == null) {
            return FlashProgrammerMenu.WriteStatus.UNREADABLE_SOURCE;
        }
        if (bytes.length > FirmwareBlob.MAX_SIZE) {
            // Defensive — the reader already caps at MAX_SIZE, but guard
            // against a future code path that returns unbounded content.
            return FlashProgrammerMenu.WriteStatus.TOO_LARGE;
        }

        // Stamp the bytes. Clear the typed-kind tags so FIRMWARE_BYTES is
        // the sole authoritative source on the target (parser precedence
        // puts bytes first anyway — leaving stale tags is just confusing).
        target.set(ScevDataComponents.FIRMWARE_BYTES.get(), new FirmwareBlob(bytes));
        target.remove(ScevDataComponents.FIRMWARE_KIND.get());
        target.remove(ScevDataComponents.FIRMWARE_ID_OVERRIDE.get());
        prog.setItem(FlashProgrammerBlockEntity.SLOT_TARGET, target);
        prog.setChanged();

        LOG.info("FlashProgrammer: wrote {} bytes from NVMe to target flash at {}",
                bytes.length, prog.getBlockPos());
        return FlashProgrammerMenu.WriteStatus.OK;
    }

    /**
     * Read the first {@link FirmwareBlob#MAX_SIZE} bytes of the source
     * storage's backing image file on disk. Returns null when the image
     * doesn't exist yet (a fresh NVMe that's never been powered on has
     * no file) or is empty. Short files are returned at their actual
     * length.
     *
     * <p>Runs on {@link Util#ioPool()} — do not touch Minecraft state
     * from here.
     */
    private static byte @Nullable [] readSourceImage(UUID uuid) {
        Path imagePath = Path.of(StorageManager.imagePath(uuid));
        if (!Files.isRegularFile(imagePath)) {
            return null;
        }

        try (InputStream in = Files.newInputStream(imagePath)) {
            byte[] buf = new byte[FirmwareBlob.MAX_SIZE];
            int total = 0;
            while (total < buf.length) {
                int n = in.read(buf, total, buf.length - total);
                if (n < 0) break;
                total += n;
            }
            if (total == 0) return null;
            if (total == buf.length) return buf;
            byte[] trimmed = new byte[total];
            System.arraycopy(buf, 0, trimmed, 0, total);
            return trimmed;
        } catch (IOException e) {
            LOG.warn("FlashProgrammer: failed to read source image {}", imagePath, e);
            return null;
        }
    }
}
