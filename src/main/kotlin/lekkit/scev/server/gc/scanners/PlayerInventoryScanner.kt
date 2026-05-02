/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server.gc.scanners

import lekkit.scev.server.gc.DiskImageScanner
import lekkit.scev.server.gc.ScanContext
import lekkit.scev.server.gc.StackInspector
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.capabilities.Capabilities

/**
 * Walks every online player's inventory (main + armor + off-hand) plus the
 * slots of any menu the player currently has open. Each [ItemStack] goes
 * through [StackInspector] so nested items unpack.
 *
 * Offline-player inventories aren't covered — they live in per-player data
 * files and aren't walkable without loading each. That's fine: when the
 * player logs in, their inventory materialises and the next sweep sees it.
 * Default retention (30 days) is long enough that monthly logins keep disks
 * alive.
 */
class PlayerInventoryScanner : DiskImageScanner {
    override fun scan(ctx: ScanContext) {
        val server = ctx.server ?: return
        for (player in server.playerList.players) scanPlayer(player, ctx)
    }

    private fun scanPlayer(player: ServerPlayer, ctx: ScanContext) {
        // Inventory.getContainerSize() includes main (36) + armor (4) + offhand (1) = 41.
        val inv = player.inventory
        for (i in 0 until inv.containerSize) {
            StackInspector.inspect(inv.getItem(i), ctx::addLive)
        }

        // Carried stack (cursor while a menu is open) and crafting-grid stacks
        // live outside the Inventory container. The player's ItemHandler.ENTITY
        // capability also catches mods adding new player-level slots (Curios,
        // Sophisticated Backpacks equipped slot).
        player.getCapability(Capabilities.ItemHandler.ENTITY, null)?.let { h ->
            for (i in 0 until h.slots) StackInspector.inspect(h.getStackInSlot(i), ctx::addLive)
        }

        // The cursor stack (player dragging while a menu is open) isn't in
        // the standard inventory container — cover it explicitly.
        StackInspector.inspect(player.containerMenu.carried, ctx::addLive)
    }
}
