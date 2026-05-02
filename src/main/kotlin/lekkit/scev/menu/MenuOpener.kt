/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.menu

import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.MenuProvider
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu

/**
 * Open a menu on the server side. Replaces the four-block-deep
 * anonymous `object : MenuProvider {…}` boilerplate every machine
 * block was carrying.
 *
 * The [factory] receives the container id + player inventory and
 * builds the menu — typically a one-line constructor call. Extra
 * payload is written via [extraData] (defaults to a single
 * [BlockPos], which is what every callsite needs).
 */
internal inline fun ServerPlayer.openScevMenu(
    titleKey: String,
    pos: BlockPos,
    crossinline factory: (id: Int, inv: Inventory) -> AbstractContainerMenu,
) {
    openMenu(object : MenuProvider {
        override fun getDisplayName(): Component = Component.translatable(titleKey)
        override fun createMenu(id: Int, inv: Inventory, p: Player): AbstractContainerMenu = factory(id, inv)
    }) { buf -> buf.writeBlockPos(pos) }
}

/**
 * Open with an arbitrary extra-data writer. Used by [MotherboardItem]
 * which writes a VarInt slot index instead of a BlockPos.
 */
internal inline fun ServerPlayer.openScevMenu(
    titleKey: String,
    crossinline writeExtra: (FriendlyByteBuf) -> Unit,
    crossinline factory: (id: Int, inv: Inventory) -> AbstractContainerMenu,
) {
    openMenu(object : MenuProvider {
        override fun getDisplayName(): Component = Component.translatable(titleKey)
        override fun createMenu(id: Int, inv: Inventory, p: Player): AbstractContainerMenu = factory(id, inv)
    }) { buf -> writeExtra(buf) }
}
