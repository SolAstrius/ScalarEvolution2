/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.compat.jade;

import lekkit.scev.blockentity.McuBoardBlockEntity;
import lekkit.scev.items.FlashItem;
import lekkit.scev.items.SocItem;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
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
 * Jade provider for {@link McuBoardBlockEntity} — microcontroller boards
 * carrying just an SoC and a flash chip.
 *
 * <p>The MCU HUD is the biggest single win of the Jade integration: all of
 * the SoC tier / firmware kind / GPIO state has lived only on item tooltips
 * and the installation menu. Having it rendered on block-peek means
 * players can finally tell a running Blinky demo from a blank Linux board
 * at a glance.
 */
public class McuBoardProvider implements IBlockComponentProvider,
        IServerDataProvider<BlockAccessor> {

    public static final McuBoardProvider INSTANCE = new McuBoardProvider();

    private McuBoardProvider() {}

    @Override
    public ResourceLocation getUid() { return ScevJadeIds.MCU_BOARD; }

    /* ---------------- Server side ---------------- */

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor acc) {
        if (!(acc.getBlockEntity() instanceof McuBoardBlockEntity be)) return;

        data.putBoolean("powered", be.isPowered());
        data.putByte("facing", (byte) facing(be).ordinal());
        data.putInt("gpio_out", be.getOutRedstoneSignals());

        ItemStack soc = be.getItem(McuBoardBlockEntity.SLOT_SOC);
        if (soc.getItem() instanceof SocItem s) {
            data.putInt("soc_tier", s.getTier());
            data.putString("soc_isa", s.getIsa());
            data.putInt("soc_harts", s.getHartCount());
            data.putInt("soc_ram_kib", s.getEmbeddedRamKib());
        }

        ItemStack flash = be.getItem(McuBoardBlockEntity.SLOT_FLASH);
        if (flash.getItem() instanceof FlashItem) {
            // describeFirmware returns a fully-styled Component. Store the
            // raw string — Jade's server→client pipe serializes CompoundTags,
            // and TextComponents don't travel cleanly through putCompound in
            // every Jade release. The client recomposes styling locally.
            data.putString("firmware", FlashItem.describeFirmware(flash).getString());
        }
    }

    private static Direction facing(McuBoardBlockEntity be) {
        var bs = be.getBlockState();
        if (bs.hasProperty(lekkit.scev.blocks.DirectionalBlock.FACING)) {
            return bs.getValue(lekkit.scev.blocks.DirectionalBlock.FACING);
        }
        return Direction.NORTH;
    }

    @Override
    public boolean shouldRequestData(BlockAccessor acc) {
        return acc.getBlockEntity() instanceof McuBoardBlockEntity;
    }

    /* ---------------- Client side ---------------- */

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor acc, IPluginConfig cfg) {
        if (!(acc.getBlockEntity() instanceof McuBoardBlockEntity)) return;
        CompoundTag data = acc.getServerData();
        if (data == null || data.isEmpty()) return;

        boolean on = data.getBoolean("powered");
        tooltip.add(Component.translatable("jade.scev.power").append(": ").append(
                Component.literal(on ? "● ON" : "○ OFF")
                        .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.GRAY)));

        if (data.contains("soc_tier")) {
            String summary = data.getString("soc_isa")
                    + " · " + data.getInt("soc_harts") + " hart"
                    + (data.getInt("soc_harts") == 1 ? "" : "s")
                    + " · " + SocItem.formatRam(data.getInt("soc_ram_kib"));
            tooltip.add(Component.translatable("jade.scev.soc")
                    .append(": ")
                    .append(Component.literal("tier " + data.getInt("soc_tier")
                            + " (" + summary + ")").withStyle(ChatFormatting.YELLOW)));
        } else {
            tooltip.add(Component.translatable("jade.scev.no_soc")
                    .withStyle(ChatFormatting.RED));
        }

        if (data.contains("firmware")) {
            tooltip.add(Component.translatable("jade.scev.firmware")
                    .append(": ")
                    .append(Component.literal(data.getString("firmware"))
                            .withStyle(ChatFormatting.AQUA)));
        } else {
            tooltip.add(Component.translatable("jade.scev.no_flash")
                    .withStyle(ChatFormatting.RED));
        }

        int gpioOut = data.getInt("gpio_out");
        if (gpioOut != 0) {
            Direction f = Direction.values()[data.getByte("facing") & 0x7];
            int rel = lekkit.scev.machine.GpioPinMap.worldToRelative(
                    gpioOut & lekkit.scev.machine.GpioPinMap.PIN_MASK, f);
            tooltip.add(Component.translatable("jade.scev.gpio")
                    .append(": ")
                    .append(Component.literal(ComputerCaseProvider.renderPins(rel))
                            .withStyle(ChatFormatting.GOLD)));
        }
    }
}
