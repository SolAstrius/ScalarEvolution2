/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.blockentity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

public class KeyboardBlockEntity extends ScevBlockEntity {
    private final boolean hasMouse;

    public KeyboardBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state,
                               boolean hasMouse) {
        super(type, pos, state);
        this.hasMouse = hasMouse;
    }

    public boolean hasMouse() { return hasMouse; }
}
