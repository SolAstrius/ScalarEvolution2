/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.items

import net.minecraft.network.chat.Component
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag

/**
 * System-on-chip — an integrated package combining CPU, RAM, and minimal
 * peripherals on a single die, distinct from [CpuItem], which is a
 * socketed processor installed on a full motherboard alongside separate
 * RAM sticks and PCI expansion cards.
 *
 * The two lineages differ both in worldspace (SoCs plug into small
 * dedicated boards; CPUs require a full motherboard) and in capability
 * envelope: SoCs span from microcontroller-class (rv32, kilobytes of
 * on-die RAM, bare-metal firmware) to small embedded-Linux SBC-class
 * (rv64, tens of MiB), whereas CpuItems target workstation-class full
 * Linux with arbitrary external RAM.
 *
 * ## Tier model
 *
 * Each registered instance captures a specific spec tuple:
 *
 * ```
 *   tier | isa      | harts | embeddedRamKiB | workload class
 *   -----+----------+-------+----------------+---------------------------
 *    1   | rv32im   |   1   | 4              | bare-metal / tiny firmware
 *    2   | rv32imac |   1   | 256            | MCU + RTOS (NuttX, FreeRTOS)
 *    3   | rv64imac |   2   | 32768 (32 MiB) | embedded Linux, no PCI
 * ```
 *
 * No SocItem is accepted into any motherboard slot today — the matching
 * "MCU board" block that consumes them is a follow-up PR. This class
 * establishes the item family + spec surface so that block can be built
 * against a stable contract.
 */
class SocItem(
    props: Properties,
    val tier: Int,
    val isa: String,
    val hartCount: Int,
    val embeddedRamKib: Int,
) : Item(props) {

    override fun appendHoverText(stack: ItemStack, ctx: TooltipContext, tooltip: MutableList<Component>, flag: TooltipFlag) {
        ScevTooltips.kv(tooltip, "text.scev.tier", tier.toString())
        ScevTooltips.kv(tooltip, "text.scev.cores", hartCount.toString())
        ScevTooltips.kv(tooltip, "text.scev.isa", isa)
        ScevTooltips.kv(tooltip, "text.scev.embedded_ram", formatRam(embeddedRamKib))
        super.appendHoverText(stack, ctx, tooltip, flag)
    }

    companion object {
        /**
         * Pretty-print on-die RAM as `"N KiB"` for sub-MiB values and
         * `"N MiB"` otherwise. Keeps the 4 KiB microcontroller tier
         * readable ("4 KiB") while letting the 32-MiB Linux-capable tier
         * present sensibly ("32 MiB" instead of "32768 KiB").
         */
        @JvmStatic
        fun formatRam(kib: Int): String = when {
            kib < 1024 -> "$kib KiB"
            kib % 1024 == 0 -> "${kib / 1024} MiB"
            else -> "%.1f MiB".format(kib / 1024.0)
        }
    }
}
