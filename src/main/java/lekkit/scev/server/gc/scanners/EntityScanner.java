/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server.gc.scanners;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import lekkit.scev.server.gc.DiskImageScanner;
import lekkit.scev.server.gc.ScanContext;
import lekkit.scev.server.gc.StackInspector;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import org.slf4j.Logger;

/**
 * Walks every loaded entity in every server level and harvests
 * {@code STORAGE_UUID}s.
 *
 * <p>Three classes of entity matter here:
 *
 * <ol>
 *   <li><b>{@link ItemEntity}</b> — "items on the ground." Lifecycle events
 *       fire for these (despawn, lava, void, kill), but while they're alive
 *       they must be counted as live references. Otherwise a player dropping
 *       an NVMe, walking away briefly, and coming back would find the image
 *       gone if the sweep had run in between.</li>
 *   <li><b>Automation entities</b> — chest-minecart, hopper-minecart,
 *       chest-boat. These expose {@link Capabilities.ItemHandler#ENTITY_AUTOMATION}.</li>
 *   <li><b>Inventory entities</b> — horses with chests, llamas. Expose
 *       {@link Capabilities.ItemHandler#ENTITY}.</li>
 * </ol>
 *
 * <p>Players are handled by {@link PlayerInventoryScanner}; we skip them
 * here to avoid double-counting.
 *
 * <p><b>Create contraptions:</b> Contraption entities (trains, rotating
 * assemblies, …) carry their mounted storage in private fields on the
 * entity, NOT as a capability. A Create-specific compat scanner would be
 * needed to cover those; documented as a limitation.
 */
public final class EntityScanner implements DiskImageScanner {
    private static final Logger LOG = LogUtils.getLogger();

    @Override
    public void scan(ScanContext ctx) {
        MinecraftServer server = ctx.server();
        if (server == null) return;
        for (ServerLevel level : server.getAllLevels()) {
            scanLevel(level, ctx);
        }
    }

    private static void scanLevel(ServerLevel level, ScanContext ctx) {
        for (Entity e : level.getEntities().getAll()) {
            if (e instanceof Player) continue; // handled by PlayerInventoryScanner
            // Event-driven GC excludes the triggering entity so its own stack
            // doesn't self-protect against deletion. See ScanContext.excludeEntity.
            if (ctx.isEntityExcluded(e.getUUID())) continue;
            try {
                scanEntity(e, ctx);
            } catch (Throwable t) {
                // One misbehaving mod entity shouldn't break the whole scan.
                LOG.warn("[scev-gc] scan failed for entity {} ({}): {}",
                        e.getClass().getSimpleName(), e.getUUID(), t.getMessage());
            }
        }
    }

    private static void scanEntity(Entity e, ScanContext ctx) {
        // ItemEntity: scan its carried stack directly.
        if (e instanceof ItemEntity ie) {
            StackInspector.inspect(ie.getItem(), ctx::addLive);
            return;
        }

        // Try ENTITY_AUTOMATION first (the "external, automation-facing
        // inventory" on chest-carts / chest-boats / hopper-carts). Falls
        // through to ENTITY (horse inventory, etc.). Two separate queries
        // rather than a single one because the two capabilities may be
        // registered against the same entity with different inventories
        // and we want to scan both if so.
        IItemHandler auto = e.getCapability(Capabilities.ItemHandler.ENTITY_AUTOMATION, null);
        if (auto != null) {
            for (int i = 0; i < auto.getSlots(); i++) {
                StackInspector.inspect(auto.getStackInSlot(i), ctx::addLive);
            }
        }
        IItemHandler own = e.getCapability(Capabilities.ItemHandler.ENTITY, null);
        if (own != null && own != auto) {
            for (int i = 0; i < own.getSlots(); i++) {
                StackInspector.inspect(own.getStackInSlot(i), ctx::addLive);
            }
        }
    }
}
