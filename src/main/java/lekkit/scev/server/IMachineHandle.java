/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server;

import java.util.UUID;
import lekkit.scev.items.MotherboardItem;

/**
 * Server-side interface implemented by anything that owns a RISC-V machine
 * (block entity or item-backed laptop). The menu + packet pipeline dispatches
 * to this.
 */
public interface IMachineHandle {
    UUID getMachineUUID();
    boolean isValid();
    void powerOn();
    void powerOff();
    void power();
    void reset();
    boolean isPowered();

    int getCaseSlotCount();
    int getMaxMotherboardLevel();
    MotherboardItem getMotherboardItem();
}
