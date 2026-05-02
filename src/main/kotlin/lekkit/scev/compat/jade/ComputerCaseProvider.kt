/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.compat.jade

import lekkit.scev.blockentity.ComputerCaseBlockEntity
import lekkit.scev.blocks.DirectionalBlock
import lekkit.scev.items.CpuItem
import lekkit.scev.items.NvmeItem
import lekkit.scev.items.PciCardItem
import lekkit.scev.items.RamItem
import lekkit.scev.machine.GpioPinMap
import net.minecraft.ChatFormatting
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import snownee.jade.api.BlockAccessor
import snownee.jade.api.IBlockComponentProvider
import snownee.jade.api.IServerDataProvider
import snownee.jade.api.ITooltip
import snownee.jade.api.config.IPluginConfig

/**
 * Jade HUD provider for [ComputerCaseBlockEntity] — workstation,
 * tinkerpad, laptops. One provider instance implements both halves of
 * the handshake: it collects a tiny [CompoundTag] on the server, then
 * reads it back on the client to render the tooltip.
 *
 * ## What's surfaced
 *
 * - Power state (ON / OFF) — from the live `MachineState`.
 * - Motherboard tier — from slot 0 of the inventory. Missing
 *   motherboard is shown explicitly so the player isn't left wondering
 *   why the machine won't power on.
 * - CPU / RAM total / storage total summary — cheap pretty-printed
 *   sums of the installed items. Skipped when no motherboard is
 *   present.
 * - GPIO pin state: block-relative in/out masks rendered as six LEDs
 *   (FRONT/BACK/LEFT/RIGHT/TOP/BOTTOM).
 *
 * ## Why server-data
 *
 * Inventory contents and `MachineState#isPowered` live on the server
 * only (the client's copy of the BE is a thin shell). Jade's
 * `IServerDataProvider#appendServerData` runs server-side and ships a
 * [CompoundTag] to the client, keyed into the accessor via
 * `getServerData()`. We avoid packing the framebuffer or motherboard
 * `ItemStack` blobs — the tooltip only needs summary fields.
 */
class ComputerCaseProvider private constructor() :
    IBlockComponentProvider, IServerDataProvider<BlockAccessor> {

    override fun getUid(): ResourceLocation = ScevJadeIds.COMPUTER_CASE

    /* ---------------- Server side ---------------- */

    override fun appendServerData(data: CompoundTag, acc: BlockAccessor) {
        val be = acc.blockEntity as? ComputerCaseBlockEntity ?: return

        data.putBoolean("powered", be.isPowered())
        data.putByte("facing", facing(be).ordinal.toByte())
        data.putInt("gpio_out", be.outRedstoneSignals)

        val mb = be.getMotherboardItem()
        if (mb == null) {
            data.putBoolean("no_motherboard", true)
            return
        }
        data.putInt("mb_tier", mb.level)

        // Walk expansion slots (1..N). Summary stats only — don't pack the
        // inventory itself; the client never needs per-slot detail and
        // ItemStack NBT on motherboards gets chunky.
        var totalRamMb = 0
        var totalStorageMb = 0
        var cpuLevel = -1
        var cpuHarts = 0
        var pciCount = 0
        for (i in 1 until be.containerSize) {
            val s = be.getItem(i)
            if (s.isEmpty) continue
            when (val item = s.item) {
                is CpuItem -> {
                    cpuLevel = item.level
                    cpuHarts = item.hartCount
                }
                is RamItem -> totalRamMb += item.getMegabytes()
                is NvmeItem -> totalStorageMb += minOf(item.getSizeMb(), Int.MAX_VALUE.toLong()).toInt()
                is PciCardItem -> pciCount++
            }
        }
        if (cpuLevel >= 0) {
            data.putInt("cpu_tier", cpuLevel)
            data.putInt("cpu_harts", cpuHarts)
        }
        if (totalRamMb > 0)     data.putInt("ram_mb", totalRamMb)
        if (totalStorageMb > 0) data.putInt("nvme_mb", totalStorageMb)
        if (pciCount > 0)       data.putInt("pci_count", pciCount)
    }

    override fun shouldRequestData(acc: BlockAccessor): Boolean =
        acc.blockEntity is ComputerCaseBlockEntity

    /* ---------------- Client side ---------------- */

    override fun appendTooltip(tooltip: ITooltip, acc: BlockAccessor, cfg: IPluginConfig) {
        if (acc.blockEntity !is ComputerCaseBlockEntity) return
        val data = acc.serverData ?: return
        if (data.isEmpty) return

        // Power state LED.
        val on = data.getBoolean("powered")
        tooltip.add(Component.translatable("jade.scev.power").append(": ").append(
            Component.literal(if (on) "● ON" else "○ OFF")
                .withStyle(if (on) ChatFormatting.GREEN else ChatFormatting.GRAY)))

        if (data.getBoolean("no_motherboard")) {
            tooltip.add(Component.translatable("jade.scev.no_motherboard").withStyle(ChatFormatting.RED))
            return
        }

        tooltip.add(Component.translatable("jade.scev.motherboard_tier")
            .append(": ")
            .append(Component.literal(data.getInt("mb_tier").toString())
                .withStyle(ChatFormatting.YELLOW)))

        if (data.contains("cpu_tier")) {
            tooltip.add(Component.translatable("jade.scev.cpu")
                .append(": ")
                .append(Component.literal("tier ${data.getInt("cpu_tier")} · ${data.getInt("cpu_harts")} harts")
                    .withStyle(ChatFormatting.YELLOW)))
        }
        if (data.contains("ram_mb")) {
            tooltip.add(Component.translatable("jade.scev.ram")
                .append(": ")
                .append(Component.literal(prettyMb(data.getInt("ram_mb"))).withStyle(ChatFormatting.YELLOW)))
        }
        if (data.contains("nvme_mb")) {
            tooltip.add(Component.translatable("jade.scev.storage")
                .append(": ")
                .append(Component.literal(prettyMb(data.getInt("nvme_mb"))).withStyle(ChatFormatting.YELLOW)))
        }
        if (data.contains("pci_count")) {
            tooltip.add(Component.translatable("jade.scev.pci_cards")
                .append(": ")
                .append(Component.literal(data.getInt("pci_count").toString())
                    .withStyle(ChatFormatting.YELLOW)))
        }

        // GPIO output row, rendered block-relative so firmware-authored
        // pin names match what the player sees.
        val gpioOut = data.getInt("gpio_out")
        if (gpioOut != 0) {
            val f = Direction.values()[data.getByte("facing").toInt() and 0x7]
            val rel = GpioPinMap.worldToRelative(gpioOut and GpioPinMap.PIN_MASK, f)
            tooltip.add(Component.translatable("jade.scev.gpio")
                .append(": ")
                .append(Component.literal(renderPins(rel)).withStyle(ChatFormatting.GOLD)))
        }
    }

    companion object {
        @JvmField val INSTANCE: ComputerCaseProvider = ComputerCaseProvider()

        private fun facing(be: ComputerCaseBlockEntity): Direction {
            val bs = be.blockState
            return if (bs.hasProperty(DirectionalBlock.FACING)) bs.getValue(DirectionalBlock.FACING) else Direction.NORTH
        }

        /** Render a 6-bit block-relative pin mask as "F B L R T D" with ● / ○. */
        @JvmStatic
        fun renderPins(rel: Int): String {
            val label = charArrayOf('F', 'B', 'L', 'R', 'T', 'D')
            val sb = StringBuilder()
            for (i in 0 until 6) {
                sb.append(label[i])
                sb.append(if ((rel shr i) and 1 != 0) '●' else '○')
                if (i < 5) sb.append(' ')
            }
            return sb.toString()
        }

        /** Pretty-print megabytes as MiB / GiB. */
        @JvmStatic
        fun prettyMb(mb: Int): String = when {
            mb < 1024 -> "$mb MiB"
            mb % 1024 == 0 -> "${mb / 1024} GiB"
            else -> "%.1f GiB".format(mb / 1024.0)
        }
    }
}
