/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.expansion

import net.minecraft.network.chat.Component

/**
 * Marker interface for items that can be installed in a machine's
 * expansion slot. Each card type exposes one (or rarely more) capability
 * to the host machine — a serial-port card adds a UART pin to the
 * peripheral bus, an I2C card lets the machine talk to I2C devices,
 * etc.
 *
 * **Why a marker, not a class:** items have inheritance constraints
 * (most extend specialised vanilla `Item` subclasses), so an interface
 * is the cleanest way to tag them without coupling to a base. The host
 * machine's [ExpansionInventory] uses `Item is IExpansionCard` to gate
 * placement.
 *
 * **Capability discovery:** today this is just a marker — capability
 * inspection happens via `instanceof` checks on the specific card type
 * (e.g. `card.item is SerialPortCardItem`). When the system grows past
 * a handful of card types this should turn into a proper capability
 * lookup, e.g. `card.getCapability(MACHINE_BUS_SERIAL)`.
 */
interface IExpansionCard {
    /** The specific kind a host can switch on. Used by both the
     *  filter on [ExpansionInventory] and the visible label in the
     *  machine GUI. */
    val cardKind: ExpansionCardKind

    /** User-facing label shown on hover in the slot. Default uses the
     *  card kind's translation key. */
    fun cardLabel(): Component = Component.translatable(cardKind.langKey)
}

/**
 * Closed enum of every expansion-card kind the mod ships. Block UIs
 * can show kind-specific iconography, the host machine can route the
 * card's behavior into the right subsystem.
 *
 * Adding a new kind: bump the enum + create a matching item class
 * implementing [IExpansionCard] + register the item in
 * [lekkit.scev.main.ScevRegistry]. No further wiring needed for the
 * slot to accept it.
 */
enum class ExpansionCardKind(val langKey: String) {
    /** RS-232 / TTL serial UART. Adds a TX/RX pin to the host's
     *  peripheral bus that other blocks can wire to. */
    SERIAL("expansion.scev.serial"),

    /** I²C two-wire bus master. Lets the host talk to chained I²C
     *  devices (sensors, EEPROMs, character LCDs). */
    I2C("expansion.scev.i2c"),

    /** Real-time clock with battery-backed time. Provides wall-clock
     *  time to the host independent of game ticks. */
    RTC("expansion.scev.rtc"),

    /** Generic GPIO breakout — N digital pins per card. */
    GPIO("expansion.scev.gpio"),
}
