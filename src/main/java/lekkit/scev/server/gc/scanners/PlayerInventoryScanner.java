/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server.gc.scanners;

import lekkit.scev.server.gc.DiskImageScanner;
import lekkit.scev.server.gc.ScanContext;
import lekkit.scev.server.gc.StackInspector;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Walks every online player's inventory (main + armor + off-hand) plus the
 * slots of any menu the player currently has open. Each discovered
 * {@link ItemStack} goes through {@link StackInspector} so nested items
 * (motherboard components, shulker contents, bundled stacks, …) are unpacked.
 *
 * <p>Offline-player inventories aren't covered here — they live in the
 * per-player data file and aren't walkable without loading each file.
 * That's fine for the GC's purposes: when the player logs in, their
 * inventory materializes and the next sweep sees it. Retention is long
 * enough (30 days by default) that a player logging in once a month keeps
 * their disks alive.
 */
public final class PlayerInventoryScanner implements DiskImageScanner {
    @Override
    public void scan(ScanContext ctx) {
        MinecraftServer server = ctx.server();
        if (server == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            scanPlayer(player, ctx);
        }
    }

    private static void scanPlayer(ServerPlayer player, ScanContext ctx) {
        // Inventory.getContainerSize() includes main (36) + armor (4) + offhand (1) = 41.
        Inventory inv = player.getInventory();
        int size = inv.getContainerSize();
        for (int i = 0; i < size; i++) {
            StackInspector.inspect(inv.getItem(i), ctx::addLive);
        }

        // Carried stack (cursor while a menu is open) and crafting-grid
        // stacks live outside the Inventory container. If the player has a
        // menu open, expose those via the player's attached item handler
        // capability. This also catches mods that add new player-level
        // inventories (e.g. Curios baubles, Sophisticated Backpacks equipped
        // slot) when they register against ItemHandler.ENTITY.
        IItemHandler playerHandler = player.getCapability(Capabilities.ItemHandler.ENTITY, null);
        if (playerHandler != null && playerHandler.getSlots() > 0) {
            for (int i = 0; i < playerHandler.getSlots(); i++) {
                StackInspector.inspect(playerHandler.getStackInSlot(i), ctx::addLive);
            }
        }

        // The cursor stack (what the player is dragging while a menu is open)
        // isn't in the standard inventory container — cover it explicitly.
        StackInspector.inspect(player.containerMenu.getCarried(), ctx::addLive);
    }
}
