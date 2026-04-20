/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.items;

import net.minecraft.world.item.Item;

public class CpuItem extends Item {
    private final int level;

    public CpuItem(Properties props, int level) {
        super(props);
        this.level = level;
    }

    public int getLevel() { return level; }
}
