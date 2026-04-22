/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.compat.jade;

import java.util.UUID;
import lekkit.scev.blockentity.CRTBlockEntity;
import lekkit.scev.bus.PeripheralBusElement;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

/**
 * Jade provider for {@link CRTBlockEntity}. CRT shares the {@link
 * lekkit.scev.blocks.DirectionalBlock} superclass with VT100 terminals, but
 * its BE class and linking model are different: CRT is a pure
 * peripheral-bus display and only learns its owning machine through the
 * bus scan (there's no proximity auto-discovery like {@code VT100}).
 *
 * <p>Surfacing the bus-bound UUID on peek means players can quickly tell
 * whether a CRT has latched onto the computer they wired to — a common
 * "why is this screen black" question before this provider existed was
 * "is the bus even reaching the monitor?".
 */
public class CrtMonitorProvider implements IBlockComponentProvider,
        IServerDataProvider<BlockAccessor> {

    public static final CrtMonitorProvider INSTANCE = new CrtMonitorProvider();

    private CrtMonitorProvider() {}

    @Override
    public ResourceLocation getUid() { return ScevJadeIds.CRT_MONITOR; }

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor acc) {
        if (!(acc.getBlockEntity() instanceof CRTBlockEntity be)) return;
        UUID bound = be.boundMachineUuid();
        if (bound != null) data.putUUID("linked", bound);
    }

    @Override
    public boolean shouldRequestData(BlockAccessor acc) {
        return acc.getBlockEntity() instanceof CRTBlockEntity;
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor acc, IPluginConfig cfg) {
        if (!(acc.getBlockEntity() instanceof CRTBlockEntity)) return;
        CompoundTag data = acc.getServerData();
        if (data == null) return;

        if (data.hasUUID("linked")) {
            UUID u = data.getUUID("linked");
            String short_ = u.toString().substring(0, 8) + "…";
            tooltip.add(Component.translatable("jade.scev.linked_to")
                    .append(": ")
                    .append(Component.literal(short_).withStyle(ChatFormatting.AQUA)));
        } else {
            tooltip.add(Component.translatable("jade.scev.not_linked")
                    .withStyle(ChatFormatting.GRAY));
        }
    }
}
