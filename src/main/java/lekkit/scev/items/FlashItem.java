/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.items;

import java.util.List;
import lekkit.scev.main.ScevDataComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * Flash-ROM chip. Carries a firmware image that the backend loads into guest
 * RAM at the reset vector.
 *
 * <p>Which firmware a chip carries is decided by the data components on the
 * stack — see {@link lekkit.scev.main.ScevDataComponents} for the precedence
 * order (custom bytes &gt; registry override &gt; typed kind &gt; default
 * {@link FlashFirmware#LINUX}). The tooltip surfaces the resolved kind so
 * players can tell a freshly-stamped Blinky chip apart from a blank Linux
 * flash without an identify step.
 */
public class FlashItem extends StorageItem {
    public FlashItem(Properties props) {
        super(props, "fw_payload.bin", 8);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, ctx, tooltip, flag);

        Component firmwareLabel = describeFirmware(stack);
        tooltip.add(Component.translatable("text.scev.firmware")
                .append(Component.literal(": "))
                .append(firmwareLabel.copy().withStyle(ChatFormatting.YELLOW)));
    }

    /**
     * Format the firmware on this stack as one of:
     * <ul>
     *   <li>{@code "Custom (N bytes)"} if raw bytes are attached.</li>
     *   <li>The override id's string form (namespace:path) if only the
     *       third-party escape hatch is set.</li>
     *   <li>The lang key for the typed kind (e.g. {@code "Blinky (bare-metal demo)"}).</li>
     *   <li>{@link FlashFirmware#LINUX}'s lang if nothing is set — matches
     *       parser fallback behavior.</li>
     * </ul>
     */
    public static Component describeFirmware(ItemStack stack) {
        FirmwareBlob bytes = stack.get(ScevDataComponents.FIRMWARE_BYTES.get());
        if (bytes != null && !bytes.isEmpty()) {
            return Component.translatable("text.scev.firmware.custom", bytes.bytes().length);
        }
        ResourceLocation override = stack.get(ScevDataComponents.FIRMWARE_ID_OVERRIDE.get());
        if (override != null) {
            return Component.literal(override.toString());
        }
        FlashFirmware kind = stack.get(ScevDataComponents.FIRMWARE_KIND.get());
        if (kind == null) kind = FlashFirmware.LINUX;
        return Component.translatable("text.scev.firmware." + kind.getSerializedName());
    }
}
