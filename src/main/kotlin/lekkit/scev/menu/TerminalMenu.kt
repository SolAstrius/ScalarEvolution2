/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.menu

import java.util.UUID
import lekkit.scev.blockentity.TerminalKind
import lekkit.scev.main.ScevRegistry
import net.minecraft.core.BlockPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

/**
 * Menu opened to view a VT100 terminal. Carries no inventory slots
 * other than the player's own (so shift-click from another container
 * doesn't crash the screen). The terminal grid lives client-side; the
 * menu only ferries:
 *  - [blockPos]    so the client knows which block instance to associate
 *                  with the open screen.
 *  - [machineUuid] the UUID of the machine currently bound to this
 *                  terminal block (via the peripheral bus). Null when
 *                  the block isn't on a live bus — the screen then
 *                  falls back to the disconnected boot demo. Sent once
 *                  at menu-open and not refreshed; closing + reopening
 *                  the screen picks up bus changes.
 */
class TerminalMenu(
    containerId: Int,
    inv: Inventory,
    val blockPos: BlockPos,
    val machineUuid: UUID?,
    /** Era / capability profile of the underlying block. Sent
     *  server→client at menu open so the client knows which
     *  TerminalKind to ask the active host for. Defaults to VT100
     *  on wire-format mismatch (old server / new client). */
    val kind: TerminalKind = TerminalKind.DEFAULT,
) : AbstractContainerMenu(ScevRegistry.TERMINAL_MENU.get(), containerId) {

    init {
        for (row in 0 until 3) {
            for (col in 0 until 9) {
                addSlot(Slot(inv, col + row * 9 + 9,
                    8 + col * 18, SlotDef.FAT_PLAYER_INV_Y + row * 18))
            }
        }
        for (col in 0 until 9) {
            addSlot(Slot(inv, col, 8 + col * 18, SlotDef.FAT_HOTBAR_Y))
        }
    }

    override fun quickMoveStack(player: Player, index: Int): ItemStack = ItemStack.EMPTY

    override fun stillValid(player: Player): Boolean = true

    companion object {
        @JvmStatic
        fun fromNetwork(containerId: Int, inv: Inventory, buf: RegistryFriendlyByteBuf): TerminalMenu {
            val pos = buf.readBlockPos()
            // Optional UUID — 1 byte present-flag, then 16 bytes if
            // present. Keeps the wire backward-compatible: an old
            // client connecting to a new server reads the present
            // flag, ignores the UUID, and the screen falls back to
            // the unbound code path. New client + old server: the
            // present flag is missing, readBoolean returns false on
            // EOF semantics (unset bit) and we treat as unbound.
            val present = if (buf.isReadable) buf.readBoolean() else false
            val uuid = if (present) java.util.UUID(buf.readLong(), buf.readLong()) else null
            // Wire-version-tolerant: a server that predates the kind
            // field just doesn't write the trailing string; we fall
            // back to VT100. Same trick the UUID present-flag uses.
            val kind = if (buf.isReadable)
                TerminalKind.byNameOrDefault(buf.readUtf(32))
            else TerminalKind.DEFAULT
            return TerminalMenu(containerId, inv, pos, uuid, kind)
        }
    }
}
