/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.items

import lekkit.scev.main.ScevDataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag

/**
 * Flash-ROM chip. Carries a firmware image that the backend loads into
 * guest RAM at the reset vector.
 *
 * Which firmware a chip carries is decided by the data components on the
 * stack — see [lekkit.scev.main.ScevDataComponents] for the precedence
 * order (custom bytes > registry override > typed kind > default
 * [FlashFirmware.LINUX]). The tooltip surfaces the resolved kind so
 * players can tell a freshly-stamped Blinky chip apart from a blank
 * Linux flash without an identify step.
 */
class FlashItem(props: Properties) : StorageItem(props, "fw_payload.bin", 8L) {

    override fun appendHoverText(stack: ItemStack, ctx: TooltipContext, tooltip: MutableList<Component>, flag: TooltipFlag) {
        super.appendHoverText(stack, ctx, tooltip, flag)
        ScevTooltips.kv(tooltip, "text.scev.firmware", describeFirmware(stack))
    }

    companion object {
        /**
         * Format the firmware on this stack as one of:
         * - `"Custom (N bytes)"` if raw bytes are attached.
         * - The override id's string form (namespace:path) if only the
         *   third-party escape hatch is set.
         * - The lang key for the typed kind (e.g. `"Blinky (bare-metal demo)"`).
         * - [FlashFirmware.LINUX]'s lang if nothing is set — matches
         *   parser fallback behavior.
         */
        @JvmStatic
        fun describeFirmware(stack: ItemStack): Component {
            val bytes = stack.get(ScevDataComponents.FIRMWARE_BYTES.get())
            if (bytes != null && !bytes.isEmpty()) {
                return Component.translatable("text.scev.firmware.custom", bytes.bytes.size)
            }
            val override = stack.get(ScevDataComponents.FIRMWARE_ID_OVERRIDE.get())
            if (override != null) {
                return Component.literal(override.toString())
            }
            val kind = stack.get(ScevDataComponents.FIRMWARE_KIND.get()) ?: FlashFirmware.LINUX
            return Component.translatable("text.scev.firmware.${kind.serializedName}")
        }
    }
}
