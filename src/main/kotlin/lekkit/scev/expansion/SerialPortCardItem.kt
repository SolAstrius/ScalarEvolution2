/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.expansion

import net.minecraft.world.item.Item

/** RS-232 / TTL serial UART expansion card.
 *
 *  When installed in a machine's expansion slot, the host advertises a
 *  TX/RX pin to the peripheral bus. Future serial-cable blocks can
 *  attach a VT100 terminal to the host without the host needing
 *  built-in serial. */
class SerialPortCardItem(props: Properties) : Item(props), IExpansionCard {
    override val cardKind: ExpansionCardKind = ExpansionCardKind.SERIAL
}

/** I²C two-wire bus master expansion card. */
class I2CCardItem(props: Properties) : Item(props), IExpansionCard {
    override val cardKind: ExpansionCardKind = ExpansionCardKind.I2C
}

/** Battery-backed real-time clock expansion card. */
class RtcCardItem(props: Properties) : Item(props), IExpansionCard {
    override val cardKind: ExpansionCardKind = ExpansionCardKind.RTC
}

/** Generic GPIO breakout expansion card. */
class GpioCardItem(props: Properties) : Item(props), IExpansionCard {
    override val cardKind: ExpansionCardKind = ExpansionCardKind.GPIO
}
