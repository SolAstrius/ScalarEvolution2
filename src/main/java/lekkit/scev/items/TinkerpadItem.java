/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.items;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.item.BlockItem;

/**
 * BlockItem for the Tinkerpad. Future work: open a "laptop" GUI on right-click
 * even when not placed, using the stack's stored motherboard.
 */
public class TinkerpadItem extends BlockItem {
    public TinkerpadItem(Block block, Properties props) {
        super(block, props);
    }
}
