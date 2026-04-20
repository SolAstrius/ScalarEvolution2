/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.items;

import net.minecraft.world.item.Item;

/**
 * Generic PCIe expansion card. {@link Kind} identifies the device kind to attach
 * at machine boot.
 */
public class PciCardItem extends Item {
    public enum Kind { NET, VGA, GPIO, SOUND }

    private final Kind kind;

    public PciCardItem(Properties props, Kind kind) {
        super(props);
        this.kind = kind;
    }

    public Kind getKind() { return kind; }
}
