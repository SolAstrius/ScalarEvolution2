/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.blockentity;

import lekkit.scev.main.ScevRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public class CRTBlockEntity extends ScevBlockEntity {
    public CRTBlockEntity(BlockPos pos, BlockState state) {
        super(ScevRegistry.CRT_BE.get(), pos, state);
    }
}
