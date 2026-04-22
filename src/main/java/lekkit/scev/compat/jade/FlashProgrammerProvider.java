/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.compat.jade;

import lekkit.scev.blockentity.FlashProgrammerBlockEntity;
import lekkit.scev.items.FlashItem;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

/**
 * Jade provider for {@link FlashProgrammerBlockEntity}. Exposes the slot
 * state (source inserted / target inserted, target's current firmware) so
 * players can tell at a glance whether they've loaded the slots correctly
 * before opening the GUI to click Write.
 *
 * <p>For the target flash chip we reuse {@link FlashItem#describeFirmware}
 * — same rendering as the item tooltip — because "what firmware is on the
 * chip I'm about to overwrite" is the most common thing a player wants
 * to double-check.
 */
public class FlashProgrammerProvider implements IBlockComponentProvider,
        IServerDataProvider<BlockAccessor> {

    public static final FlashProgrammerProvider INSTANCE = new FlashProgrammerProvider();

    private FlashProgrammerProvider() {}

    @Override
    public ResourceLocation getUid() { return ScevJadeIds.FLASH_PROGRAMMER; }

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor acc) {
        if (!(acc.getBlockEntity() instanceof FlashProgrammerBlockEntity be)) return;

        ItemStack source = be.getItem(FlashProgrammerBlockEntity.SLOT_SOURCE);
        ItemStack target = be.getItem(FlashProgrammerBlockEntity.SLOT_TARGET);

        data.putBoolean("has_source", !source.isEmpty());
        data.putBoolean("has_target", !target.isEmpty());
        if (target.getItem() instanceof FlashItem) {
            // Client recomposes styling locally — we just ship the string
            // form, same pattern as McuBoardProvider's firmware field.
            data.putString("target_firmware",
                    FlashItem.describeFirmware(target).getString());
        }
    }

    @Override
    public boolean shouldRequestData(BlockAccessor acc) {
        return acc.getBlockEntity() instanceof FlashProgrammerBlockEntity;
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor acc, IPluginConfig cfg) {
        if (!(acc.getBlockEntity() instanceof FlashProgrammerBlockEntity)) return;
        CompoundTag data = acc.getServerData();
        if (data == null || data.isEmpty()) return;

        boolean hasSource = data.getBoolean("has_source");
        boolean hasTarget = data.getBoolean("has_target");

        tooltip.add(Component.translatable("jade.scev.programmer.source")
                .append(": ")
                .append(Component.literal(hasSource ? "●" : "○")
                        .withStyle(hasSource ? ChatFormatting.GREEN : ChatFormatting.GRAY)));
        tooltip.add(Component.translatable("jade.scev.programmer.target")
                .append(": ")
                .append(Component.literal(hasTarget ? "●" : "○")
                        .withStyle(hasTarget ? ChatFormatting.GREEN : ChatFormatting.GRAY)));

        if (data.contains("target_firmware")) {
            tooltip.add(Component.translatable("jade.scev.firmware")
                    .append(": ")
                    .append(Component.literal(data.getString("target_firmware"))
                            .withStyle(ChatFormatting.AQUA)));
        }
    }
}
