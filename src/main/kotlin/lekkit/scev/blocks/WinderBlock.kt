/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.blocks

import lekkit.scev.blockentity.WinderBlockEntity
import lekkit.scev.main.ScevRegistry
import lekkit.scev.menu.WinderMenu
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState

class WinderBlock(props: Properties) :
    ProcessingMachineBlockBase<WinderBlockEntity>(props, "container.scev.winder") {
    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        WinderBlockEntity(pos, state)
    override fun beType(): BlockEntityType<WinderBlockEntity> = ScevRegistry.WINDER_BE.get()
    override fun openMenu(id: Int, inv: Inventory, be: WinderBlockEntity): AbstractContainerMenu =
        WinderMenu(id, inv, be)
}
