/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.compat.jade;

import java.util.UUID;
import lekkit.scev.blockentity.VT100BlockEntity;
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
 * Jade provider for {@link VT100BlockEntity} — surfaces which machine the
 * terminal auto-linked to. Makes the "why isn't this terminal showing
 * anything" question answerable at a glance: if the HUD reports no link, no
 * powered framebuffer-carrying machine is within the terminal's discovery
 * radius.
 *
 * <p>The link UUID is already cached + persisted in the BE; we trigger
 * {@code resolveLinkedMachine()} on the server so a fresh scan happens when
 * the player peeks — otherwise the HUD could lag the real world state.
 */
public class Vt100Provider implements IBlockComponentProvider,
        IServerDataProvider<BlockAccessor> {

    public static final Vt100Provider INSTANCE = new Vt100Provider();

    private Vt100Provider() {}

    @Override
    public ResourceLocation getUid() { return ScevJadeIds.VT100; }

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor acc) {
        if (!(acc.getBlockEntity() instanceof VT100BlockEntity be)) return;
        UUID linked = be.resolveLinkedMachine();
        if (linked != null) data.putUUID("linked", linked);
    }

    @Override
    public boolean shouldRequestData(BlockAccessor acc) {
        return acc.getBlockEntity() instanceof VT100BlockEntity;
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor acc, IPluginConfig cfg) {
        if (!(acc.getBlockEntity() instanceof VT100BlockEntity)) return;
        CompoundTag data = acc.getServerData();
        if (data == null) return;

        if (data.hasUUID("linked")) {
            UUID u = data.getUUID("linked");
            tooltip.add(Component.translatable("jade.scev.linked_to")
                    .append(": ")
                    .append(Component.literal(shortUuid(u)).withStyle(ChatFormatting.AQUA)));
        } else {
            tooltip.add(Component.translatable("jade.scev.not_linked")
                    .withStyle(ChatFormatting.GRAY));
        }
    }

    /** First 4 hex chars of the UUID — enough to disambiguate machines a player
     *  keeps multiples of without dumping the full 36-char UUID into the HUD. */
    private static String shortUuid(UUID u) {
        String s = u.toString();
        return s.substring(0, Math.min(8, s.length())) + "…";
    }
}
