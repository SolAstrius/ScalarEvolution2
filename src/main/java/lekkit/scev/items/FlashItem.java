/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.items;

public class FlashItem extends StorageItem {
    public FlashItem(Properties props) {
        super(props, "fw_payload.bin", 8);
    }
}
