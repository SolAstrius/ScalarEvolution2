/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.items;

import java.util.List;
import lekkit.scev.menu.MotherboardMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

public class MotherboardItem extends Item {
    public static final int SLOT_CPU = 0;
    public static final int SLOT_FLASH = 1;
    /** Inclusive first RAM slot. */
    public static final int SLOT_RAM_START = 2;
    /** Inclusive last RAM slot. */
    public static final int SLOT_RAM_END = 5;
    /** Inclusive first NVMe (m.2) slot. */
    public static final int SLOT_NVME_START = 6;
    /** Inclusive last NVMe (m.2) slot. */
    public static final int SLOT_NVME_END = 7;
    /** Inclusive first PCIe slot. */
    public static final int SLOT_PCI_START = 8;
    /** Inclusive last PCIe slot. */
    public static final int SLOT_PCI_END = 13;

    public static final int INVENTORY_SIZE = 14;

    private final int level;

    public MotherboardItem(Properties props, int level) {
        super(props);
        this.level = level;
    }

    public int getLevel() { return level; }

    public int ramSlots() {
        return switch (level) { case 1 -> 2; case 2 -> 3; case 3 -> 4; default -> 0; };
    }
    public int pciSlots() {
        return switch (level) { case 1 -> 2; case 2 -> 4; case 3 -> 6; default -> 0; };
    }
    public int m2Slots() {
        return switch (level) { case 1 -> 1; case 2 -> 1; case 3 -> 2; default -> 0; };
    }

    public boolean isSlotEnabled(int index) {
        return switch (index) {
            case 0, 1, 2, 3 -> level >= 1;
            case 4 -> level >= 2;
            case 5 -> level >= 3;
            case 6 -> level >= 1;
            case 7 -> level >= 3;
            case 8, 9 -> level >= 1;
            case 10, 11 -> level >= 2;
            case 12, 13 -> level >= 3;
            default -> false;
        };
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide && player instanceof ServerPlayer sp) {
            // The motherboard must live at a determinable inventory slot so the
            // client-side menu can follow it. For MAIN_HAND, that's the selected
            // hotbar slot. OFF_HAND isn't used yet — fall through with PASS.
            if (hand != InteractionHand.MAIN_HAND) {
                return InteractionResultHolder.pass(player.getItemInHand(hand));
            }
            final int selected = player.getInventory().selected;
            sp.openMenu(new MenuProvider() {
                @Override
                public Component getDisplayName() {
                    return Component.translatable("container.scev.motherboard");
                }

                @Override
                public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) {
                    return new MotherboardMenu(id, inv, selected);
                }
            }, buf -> buf.writeVarInt(selected));
            return InteractionResultHolder.consume(player.getItemInHand(hand));
        }
        return InteractionResultHolder.success(player.getItemInHand(hand));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        ScevTooltips.kv(tooltip, "text.scev.tier", Integer.toString(level));
        ScevTooltips.kv(tooltip, "text.scev.ram_slots", String.valueOf(ramSlots()));
        ScevTooltips.kv(tooltip, "text.scev.pci_slots", String.valueOf(pciSlots()));
        ScevTooltips.kv(tooltip, "text.scev.m2_slots", String.valueOf(m2Slots()));
        super.appendHoverText(stack, ctx, tooltip, flag);
    }
}
