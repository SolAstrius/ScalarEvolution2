/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.bus;

import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * Marker interface for block entities that participate in a peripheral bus.
 *
 * <p>The {@link PeripheralBusController} on each computer walks outward
 * from the computer block, BFS-style, enqueuing any adjacent BE that
 * implements this interface. Each element contributes its device kinds to
 * the resulting {@link PeripheralBus}, and the controller stamps the
 * bound machine UUID back onto every element so peripherals can route
 * input / display to "their" machine without a reverse scan.
 *
 * <h2>Conduit vs device</h2>
 *
 * <p>Every element is a conduit by default — the BFS walks through it
 * regardless of whether it contributes devices. A cable block implements
 * this with an empty {@link #peripheralKinds} set; a keyboard implements
 * it with {@link PeripheralDeviceKind#KEYBOARD}. Terminal devices that
 * should NOT pass the bus through themselves can override
 * {@link #isBusConduit} to return {@code false} — a keyboard placed
 * between two computers then doesn't accidentally bridge them.
 */
public interface PeripheralBusElement {
    /**
     * Kinds this element contributes to the bus. Empty set = pure conduit
     * (a cable). Multiple kinds are allowed (a keyboard with a built-in
     * mouse reports both).
     */
    default Set<PeripheralDeviceKind> peripheralKinds() { return Set.of(); }

    /**
     * Does the BFS scan traverse through this element to its neighbours?
     * True for cables and passive bus elements. False for terminal devices
     * that shouldn't bridge two buses (a keyboard between two computers,
     * for instance, should belong to only one of them).
     */
    default boolean isBusConduit() { return true; }

    /**
     * The UUID of the machine this element is bound to, or {@code null}
     * if the element isn't on any live bus. Set by
     * {@link PeripheralBusController#scan} after each successful scan;
     * consumed by peripheral-specific interaction code (e.g. right-click
     * on a keyboard opens the bound machine's screen).
     *
     * <p>This is transient — not persisted in NBT. If the world reloads
     * while a machine is running, the first controller tick re-stamps
     * every bus element.
     */
    @Nullable UUID boundMachineUuid();

    /** Update binding; called by the controller during a scan. */
    void setBoundMachineUuid(@Nullable UUID uuid);

    /**
     * The world position of the computer this element is bound to. Paired
     * with {@link #boundMachineUuid} — peripherals use the position to
     * open the computer's menu without having to reverse-scan to find it.
     */
    @Nullable BlockPos boundMachinePos();

    void setBoundMachinePos(@Nullable BlockPos pos);
}
