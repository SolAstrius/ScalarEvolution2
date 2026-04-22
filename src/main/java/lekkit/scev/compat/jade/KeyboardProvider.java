/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.compat.jade;

import java.util.UUID;
import lekkit.scev.blockentity.KeyboardBlockEntity;
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
 * Jade provider for {@link KeyboardBlockEntity}. Surfaces the machine the
 * keyboard is currently bound to — same "did the bus scan reach this
 * peripheral" diagnostic as CRT / VT100, but critical here because keyboard
 * right-click silently falls back to an action-bar error message when
 * unbound, which is easy to miss.
 *
 * <p>The "has mouse" variant is shown on a separate row so players can tell
 * the two keyboard SKUs apart on peek.
 */
public class KeyboardProvider implements IBlockComponentProvider,
        IServerDataProvider<BlockAccessor> {

    public static final KeyboardProvider INSTANCE = new KeyboardProvider();

    private KeyboardProvider() {}

    @Override
    public ResourceLocation getUid() { return ScevJadeIds.KEYBOARD; }

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor acc) {
        if (!(acc.getBlockEntity() instanceof KeyboardBlockEntity be)) return;
        data.putBoolean("mouse", be.hasMouse());
        UUID bound = be.boundMachineUuid();
        if (bound != null) data.putUUID("linked", bound);
    }

    @Override
    public boolean shouldRequestData(BlockAccessor acc) {
        return acc.getBlockEntity() instanceof KeyboardBlockEntity;
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor acc, IPluginConfig cfg) {
        if (!(acc.getBlockEntity() instanceof KeyboardBlockEntity)) return;
        CompoundTag data = acc.getServerData();
        if (data == null) return;

        if (data.getBoolean("mouse")) {
            tooltip.add(Component.translatable("jade.scev.keyboard.has_mouse")
                    .withStyle(ChatFormatting.GRAY));
        }

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
