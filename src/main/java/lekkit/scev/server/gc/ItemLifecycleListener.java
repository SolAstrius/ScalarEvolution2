/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server.gc;

import com.mojang.logging.LogUtils;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.item.ItemExpireEvent;
import org.slf4j.Logger;

/**
 * Event-driven entry point for disk-image GC. Wires two NeoForge events:
 *
 * <ul>
 *   <li>{@link ItemExpireEvent} — fires just before an {@link ItemEntity}'s
 *       5-minute despawn timer removes it. Catches "player dropped it and
 *       walked away."</li>
 *   <li>{@link EntityLeaveLevelEvent} — fires when any entity leaves the
 *       level. We filter for {@link ItemEntity} + a "destroyed" removal
 *       reason ({@link Entity.RemovalReason#shouldDestroy()}). Catches
 *       lava, fire, explosion, void, {@code /kill}, and pickup-by-player.
 *       Pickup looks like destruction at the event level, but by the time
 *       we scan, the stack lives in the player's inventory and the
 *       {@link lekkit.scev.server.gc.scanners.PlayerInventoryScanner}
 *       includes its UUID in the live set — so pickup is correctly NOT
 *       deleted.</li>
 * </ul>
 *
 * <h2>Why two events, not one</h2>
 *
 * <p>{@code ItemExpireEvent} fires <i>before</i> the entity transitions to
 * a removed state; {@code EntityLeaveLevelEvent} fires <i>after</i>
 * removal. Despawn triggers both (expire then leave), so we'd double-fire
 * without deduplication — but the second firing sees an empty live-set for
 * the entity (it's already removed) and still triggers the right decision
 * via the candidate set. Running the GC twice is idempotent (the image is
 * already gone after the first).
 *
 * <p>Catching both is belt-and-suspenders: some mods remove entities without
 * going through the despawn path (direct {@code discard()}) and wouldn't
 * fire {@code ItemExpireEvent}; those still fire {@code EntityLeaveLevelEvent}.
 *
 * <h2>Removal-reason filter</h2>
 *
 * <p>{@link Entity.RemovalReason#shouldDestroy()} returns true for
 * {@code KILLED} and {@code DISCARDED} — exactly the destruction reasons.
 * {@code UNLOADED_TO_CHUNK}, {@code UNLOADED_WITH_PLAYER}, and
 * {@code CHANGED_DIMENSION} are skipped — those are entity lifecycle
 * transitions, not deletions, and the item reappears when the chunk reloads
 * or the player logs back in.
 *
 * <h2>Fast path for non-storage items</h2>
 *
 * <p>Every ItemEntity removal fires the handler, but 99.9% of them carry
 * non-storage stacks. {@link StackInspector#inspect} on a plain dirt item
 * is two data-component lookups that return null — essentially free. Only
 * when the stack holds a {@code STORAGE_UUID} (directly or nested) do we
 * proceed to the scan + GC call.
 */
public final class ItemLifecycleListener {
    private static final Logger LOG = LogUtils.getLogger();

    private ItemLifecycleListener() {}

    @SubscribeEvent
    public static void onItemExpire(ItemExpireEvent event) {
        handleItemDeath(event.getEntity());
    }

    @SubscribeEvent
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (!(event.getEntity() instanceof ItemEntity ie)) return;
        // Only act on destruction. UNLOADED_* and CHANGED_DIMENSION are
        // transient; the item reappears when the chunk reloads.
        Entity.RemovalReason reason = ie.getRemovalReason();
        if (reason == null || !reason.shouldDestroy()) return;
        handleItemDeath(ie);
    }

    /**
     * Main event-path entry. Harvest UUIDs from the stack; if non-empty,
     * run scanners (excluding the triggering entity) and hand to GC.
     */
    private static void handleItemDeath(ItemEntity ie) {
        DiskImageGc gc = ScevGc.active();
        if (gc == null) return; // server not started or already stopping

        ItemStack stack = ie.getItem();
        if (stack.isEmpty()) return;

        // Fast path: harvest first, short-circuit on empty.
        Set<UUID> candidates = new HashSet<>();
        StackInspector.inspect(stack, candidates::add);
        if (candidates.isEmpty()) return;

        MinecraftServer server = null;
        if (ie.level() instanceof ServerLevel sl) {
            server = sl.getServer();
        }

        GcResult r = GcRunner.event(gc, server, candidates, ie.getUUID());
        if (r.affected() > 0) {
            LOG.info("[scev-gc] event-driven deleted {} image(s), freed {} bytes (trigger: {})",
                    r.affected(), r.bytesFreed(), ie.getUUID());
        }
    }
}
