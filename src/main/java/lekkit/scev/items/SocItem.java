/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.items;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * System-on-chip — an integrated package combining CPU, RAM, and minimal
 * peripherals on a single die, distinct from {@link CpuItem}, which is a
 * socketed processor installed on a full motherboard alongside separate RAM
 * sticks and PCI expansion cards.
 *
 * <p>The two lineages differ both in worldspace (SoCs plug into small
 * dedicated boards; CPUs require a full motherboard) and in capability
 * envelope: SoCs span from microcontroller-class (rv32, kilobytes of
 * on-die RAM, bare-metal firmware) to small embedded-Linux SBC-class
 * (rv64, tens of MiB), whereas CpuItems target workstation-class full
 * Linux with arbitrary external RAM.
 *
 * <h2>Tier model</h2>
 *
 * <p>Each registered instance captures a specific spec tuple:
 *
 * <pre>
 *   tier | isa      | harts | embeddedRamKiB | workload class
 *   -----+----------+-------+----------------+---------------------------
 *    1   | rv32im   |   1   | 4              | bare-metal / tiny firmware
 *    2   | rv32imac |   1   | 256            | MCU + RTOS (NuttX, FreeRTOS)
 *    3   | rv64imac |   2   | 32768 (32 MiB) | embedded Linux, no PCI
 * </pre>
 *
 * <p>No SocItem is accepted into any motherboard slot today — the matching
 * "MCU board" block that consumes them is a follow-up PR. This class
 * establishes the item family + spec surface so that block can be built
 * against a stable contract.
 */
public class SocItem extends Item {
    private final int tier;
    private final String isa;
    private final int hartCount;
    private final int embeddedRamKib;

    public SocItem(Properties props, int tier, String isa, int hartCount, int embeddedRamKib) {
        super(props);
        this.tier = tier;
        this.isa = isa;
        this.hartCount = hartCount;
        this.embeddedRamKib = embeddedRamKib;
    }

    public int getTier() { return tier; }
    public String getIsa() { return isa; }
    public int getHartCount() { return hartCount; }
    public int getEmbeddedRamKib() { return embeddedRamKib; }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("text.scev.cores")
                .append(Component.literal(": "))
                .append(Component.literal(Integer.toString(hartCount)).withStyle(ChatFormatting.YELLOW)));
        tooltip.add(Component.translatable("text.scev.isa")
                .append(Component.literal(": "))
                .append(Component.literal(isa).withStyle(ChatFormatting.YELLOW)));
        tooltip.add(Component.translatable("text.scev.embedded_ram")
                .append(Component.literal(": "))
                .append(Component.literal(formatRam(embeddedRamKib)).withStyle(ChatFormatting.YELLOW)));
        super.appendHoverText(stack, ctx, tooltip, flag);
    }

    /**
     * Pretty-print on-die RAM as {@code "N KiB"} for sub-MiB values and
     * {@code "N MiB"} otherwise. Keeps the 4 KiB microcontroller tier
     * readable ("4 KiB") while letting the 32-MiB Linux-capable tier
     * present sensibly ("32 MiB" instead of "32768 KiB").
     */
    public static String formatRam(int kib) {
        if (kib < 1024) return kib + " KiB";
        if (kib % 1024 == 0) return (kib / 1024) + " MiB";
        return String.format("%.1f MiB", kib / 1024.0);
    }
}
