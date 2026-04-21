/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.compat.jade;

import lekkit.scev.blockentity.ComputerCaseBlockEntity;
import lekkit.scev.items.CpuItem;
import lekkit.scev.items.MotherboardItem;
import lekkit.scev.items.NvmeItem;
import lekkit.scev.items.PciCardItem;
import lekkit.scev.items.RamItem;
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
 * Jade HUD provider for {@link ComputerCaseBlockEntity} — workstation,
 * tinkerpad, laptops. One provider instance implements both halves of the
 * handshake: it collects a tiny {@link CompoundTag} on the server, then
 * reads it back on the client to render the tooltip.
 *
 * <h2>What's surfaced</h2>
 *
 * <ul>
 *   <li>Power state (ON / OFF) — from the live {@code MachineState}.</li>
 *   <li>Motherboard tier — from slot 0 of the inventory. Missing motherboard
 *       is shown explicitly so the player isn't left wondering why the
 *       machine won't power on.</li>
 *   <li>CPU / RAM total / storage total summary — cheap pretty-printed sums
 *       of the installed items. Skipped when no motherboard is present.</li>
 *   <li>GPIO pin state: block-relative in/out masks rendered as six LEDs
 *       (FRONT/BACK/LEFT/RIGHT/TOP/BOTTOM).</li>
 * </ul>
 *
 * <h2>Why server-data</h2>
 *
 * <p>Inventory contents and {@code MachineState#isPowered} live on the
 * server only (the client's copy of the BE is a thin shell). Jade's
 * {@code IServerDataProvider#appendServerData} runs server-side and ships a
 * {@link CompoundTag} to the client, keyed into the accessor via
 * {@code getServerData()}. We avoid packing the framebuffer or motherboard
 * {@code ItemStack} blobs — the tooltip only needs summary fields.
 */
public class ComputerCaseProvider implements IBlockComponentProvider,
        IServerDataProvider<BlockAccessor> {

    public static final ComputerCaseProvider INSTANCE = new ComputerCaseProvider();

    private ComputerCaseProvider() {}

    @Override
    public ResourceLocation getUid() { return ScevJadeIds.COMPUTER_CASE; }

    /* ---------------- Server side ---------------- */

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor acc) {
        if (!(acc.getBlockEntity() instanceof ComputerCaseBlockEntity be)) return;

        data.putBoolean("powered", be.isPowered());
        data.putByte("facing", (byte) facing(be).ordinal());
        data.putInt("gpio_out", be.getOutRedstoneSignals());

        MotherboardItem mb = be.getMotherboardItem();
        if (mb == null) {
            data.putBoolean("no_motherboard", true);
            return;
        }
        data.putInt("mb_tier", mb.getLevel());

        // Walk expansion slots (1..N). Summary stats only — don't pack the
        // inventory itself; the client never needs per-slot detail and
        // ItemStack NBT on motherboards gets chunky.
        int totalRamMb = 0;
        int totalStorageMb = 0;
        int cpuLevel = -1;
        int cpuHarts = 0;
        int pciCount = 0;
        for (int i = 1; i < be.getContainerSize(); i++) {
            ItemStack s = be.getItem(i);
            if (s.isEmpty()) continue;
            if (s.getItem() instanceof CpuItem cpu) {
                cpuLevel = cpu.getLevel();
                cpuHarts = cpu.getHartCount();
            } else if (s.getItem() instanceof RamItem ram) {
                totalRamMb += ram.getMegabytes();
            } else if (s.getItem() instanceof NvmeItem nvme) {
                totalStorageMb += (int) Math.min(nvme.getSizeMb(), Integer.MAX_VALUE);
            } else if (s.getItem() instanceof PciCardItem) {
                pciCount++;
            }
        }
        if (cpuLevel >= 0) {
            data.putInt("cpu_tier", cpuLevel);
            data.putInt("cpu_harts", cpuHarts);
        }
        if (totalRamMb > 0)      data.putInt("ram_mb", totalRamMb);
        if (totalStorageMb > 0)  data.putInt("nvme_mb", totalStorageMb);
        if (pciCount > 0)        data.putInt("pci_count", pciCount);
    }

    private static Direction facing(ComputerCaseBlockEntity be) {
        var bs = be.getBlockState();
        if (bs.hasProperty(lekkit.scev.blocks.DirectionalBlock.FACING)) {
            return bs.getValue(lekkit.scev.blocks.DirectionalBlock.FACING);
        }
        return Direction.NORTH;
    }

    @Override
    public boolean shouldRequestData(BlockAccessor acc) {
        return acc.getBlockEntity() instanceof ComputerCaseBlockEntity;
    }

    /* ---------------- Client side ---------------- */

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor acc, IPluginConfig cfg) {
        if (!(acc.getBlockEntity() instanceof ComputerCaseBlockEntity)) return;
        CompoundTag data = acc.getServerData();
        if (data == null || data.isEmpty()) return;

        // Power state LED.
        boolean on = data.getBoolean("powered");
        tooltip.add(Component.translatable("jade.scev.power").append(": ").append(
                Component.literal(on ? "● ON" : "○ OFF")
                        .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.GRAY)));

        if (data.getBoolean("no_motherboard")) {
            tooltip.add(Component.translatable("jade.scev.no_motherboard")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        tooltip.add(Component.translatable("jade.scev.motherboard_tier")
                .append(": ")
                .append(Component.literal(Integer.toString(data.getInt("mb_tier")))
                        .withStyle(ChatFormatting.YELLOW)));

        if (data.contains("cpu_tier")) {
            tooltip.add(Component.translatable("jade.scev.cpu")
                    .append(": ")
                    .append(Component.literal("tier " + data.getInt("cpu_tier")
                                    + " · " + data.getInt("cpu_harts") + " harts")
                            .withStyle(ChatFormatting.YELLOW)));
        }
        if (data.contains("ram_mb")) {
            tooltip.add(Component.translatable("jade.scev.ram")
                    .append(": ")
                    .append(Component.literal(prettyMb(data.getInt("ram_mb")))
                            .withStyle(ChatFormatting.YELLOW)));
        }
        if (data.contains("nvme_mb")) {
            tooltip.add(Component.translatable("jade.scev.storage")
                    .append(": ")
                    .append(Component.literal(prettyMb(data.getInt("nvme_mb")))
                            .withStyle(ChatFormatting.YELLOW)));
        }
        if (data.contains("pci_count")) {
            tooltip.add(Component.translatable("jade.scev.pci_cards")
                    .append(": ")
                    .append(Component.literal(Integer.toString(data.getInt("pci_count")))
                            .withStyle(ChatFormatting.YELLOW)));
        }

        // GPIO output row, rendered block-relative so firmware-authored
        // pin names match what the player sees.
        int gpioOut = data.getInt("gpio_out");
        if (gpioOut != 0) {
            Direction f = Direction.values()[data.getByte("facing") & 0x7];
            int rel = lekkit.scev.machine.GpioPinMap.worldToRelative(
                    gpioOut & lekkit.scev.machine.GpioPinMap.PIN_MASK, f);
            tooltip.add(Component.translatable("jade.scev.gpio")
                    .append(": ")
                    .append(Component.literal(renderPins(rel))
                            .withStyle(ChatFormatting.GOLD)));
        }
    }

    /** Render a 6-bit block-relative pin mask as "F B L R T D" with ● / ○. */
    static String renderPins(int rel) {
        char[] label = {'F', 'B', 'L', 'R', 'T', 'D'};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            sb.append(label[i]);
            sb.append(((rel >> i) & 1) != 0 ? '●' : '○');
            if (i < 5) sb.append(' ');
        }
        return sb.toString();
    }

    /** Pretty-print megabytes as MiB / GiB. */
    static String prettyMb(int mb) {
        if (mb < 1024) return mb + " MiB";
        if (mb % 1024 == 0) return (mb / 1024) + " GiB";
        return String.format("%.1f GiB", mb / 1024.0);
    }
}
