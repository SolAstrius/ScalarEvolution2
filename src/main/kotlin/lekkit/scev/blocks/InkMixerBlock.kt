/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.blocks

import lekkit.scev.blockentity.InkMixerBlockEntity
import lekkit.scev.main.ScevRegistry
import lekkit.scev.menu.InkMixerMenu
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState

class InkMixerBlock(props: Properties) :
    ProcessingMachineBlockBase<InkMixerBlockEntity>(props, "container.scev.ink_mixer") {
    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        InkMixerBlockEntity(pos, state)
    override fun beType(): BlockEntityType<InkMixerBlockEntity> = ScevRegistry.INK_MIXER_BE.get()
    override fun openMenu(id: Int, inv: Inventory, be: InkMixerBlockEntity): AbstractContainerMenu =
        InkMixerMenu(id, inv, be)
}
