/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.menu;

import java.util.UUID;
import lekkit.scev.blockentity.ComputerCaseBlockEntity;
import lekkit.scev.items.CpuItem;
import lekkit.scev.items.FlashItem;
import lekkit.scev.items.MotherboardInventory;
import lekkit.scev.items.MotherboardItem;
import lekkit.scev.items.RamItem;
import lekkit.scev.main.ScevRegistry;
import lekkit.scev.server.NativeLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Menu for a computer case block (workstation / powermark / tinkerpad placed in world).
 *
 * <p>Layout: 1 case motherboard slot + 14 component slots (CPU / flash / RAM / NVMe / PCI)
 * at the exact coordinates from the 1.7.10 {@code ContainerComputerCase}, followed by
 * the player's inventory (27 slots) and hotbar (9 slots) — 51 slots total.
 *
 * <p>The 14 component slots are backed by the motherboard item's
 * {@code MOTHERBOARD_INVENTORY} data component via {@link MotherboardInventory}.
 * Contents persist when the menu closes, the motherboard is removed, or the
 * case is broken — the components follow the motherboard around.
 *
 * <p>A {@link DataSlot} carries the machine's power state (0 = off, 1 = on)
 * from the server to the client each tick. The screen's power button reads
 * it via {@link #isMachinePowered} so the visual reflects actual state,
 * not just the last click.
 */
public class ComputerCaseMenu extends AbstractContainerMenu {
    private final ComputerCaseBlockEntity caseBE;
    private final MotherboardInventory motherboardSlots;
    /** Mirrors caseBE.isPowered() on the server; read on the client via {@link #isMachinePowered}. */
    private final DataSlot powerState = DataSlot.standalone();

    public ComputerCaseMenu(int id, Inventory inv, ComputerCaseBlockEntity caseBE) {
        super(ScevRegistry.COMPUTER_CASE_MENU.get(), id);
        this.caseBE = caseBE;
        // Component slots 1..14 read/write through the motherboard stack that
        // lives in the case's own slot 0. If the motherboard is removed, the
        // view becomes empty and mutations are dropped (the motherboard would
        // need to be re-seated to store components).
        this.motherboardSlots = new MotherboardInventory(
                () -> caseBE.getItem(0),
                caseBE::setChanged);

        addMenuSlots();
        addPlayerInventory(inv);
        addDataSlot(powerState);
    }

    private void addMenuSlots() {
        // SlotDef.COMPUTER_CASE lists all 15 slots:
        //   index 0 : motherboard slot on the case BE (container slot 0)
        //   index 1..14 : motherboard component slots (own container slots 0..13)
        for (SlotDef def : SlotDef.COMPUTER_CASE) {
            if (def.index() == 0) {
                addSlot(new Slot(caseBE, 0, def.x(), def.y()));
            } else {
                addSlot(new MotherboardComponentSlot(
                        motherboardSlots, def.index() - 1, def.x(), def.y(), caseBE));
            }
        }
    }

    private void addPlayerInventory(Inventory inv) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inv, col + row * 9 + 9,
                        8 + col * 18, SlotDef.FAT_PLAYER_INV_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inv, col, 8 + col * 18, SlotDef.FAT_HOTBAR_Y));
        }
    }

    public static ComputerCaseMenu fromNetwork(int id, Inventory inv, RegistryFriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        BlockEntity be = inv.player.level().getBlockEntity(pos);
        if (be instanceof ComputerCaseBlockEntity cc) return new ComputerCaseMenu(id, inv, cc);
        throw new IllegalStateException("No ComputerCaseBlockEntity at " + pos);
    }

    public UUID getMachineUuid() { return caseBE.getMachineUUID(); }
    public ComputerCaseBlockEntity getCaseBE() { return caseBE; }

    /**
     * @return {@code true} iff the server last reported the machine as
     *         powered. Safe to call on both sides; on the server always
     *         reflects live state, on the client it's a one-tick-lagged
     *         mirror from the last {@link #broadcastChanges}.
     */
    public boolean isMachinePowered() {
        return powerState.get() != 0;
    }

    /**
     * Possible outcomes of {@link #validateForPower}. Each non-OK value
     * corresponds to a specific missing-component lang key
     * ({@code text.scev.power.fail.<enum.name.lower()>}) for localized UI
     * feedback.
     */
    public enum ValidationResult {
        OK,
        NATIVE_NOT_LOADED,
        NO_MOTHERBOARD,
        NO_CPU,
        NO_FLASH,
        NO_RAM;

        public String langKey() {
            return "text.scev.power.fail." + name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * Client-side preflight: confirms the minimum components are installed
     * before firing a power-on packet. The server does its own validation
     * (tier-gating, slot-kind checks) independently; this is purely a UX
     * gate to avoid sending packets that would silently no-op.
     *
     * <p>Checked in order; the first failing condition wins so the player
     * can fix one thing at a time:
     * <ol>
     *   <li><b>native-loaded</b> — {@link NativeLoader#isLoaded} must have
     *       reported success. Without librvvm, the backend initialization
     *       returns null and every power-on click silently no-ops; surfacing
     *       this as a visible error beats logs-only diagnostic.</li>
     *   <li>motherboard → CPU → flash → at least one RAM.</li>
     * </ol>
     *
     * <p>In multiplayer, the client's {@link NativeLoader#isLoaded} reflects
     * its own JVM's state (FMLCommonSetupEvent runs on both sides), which is
     * normally the same as the server's when both installed the mod
     * correctly. If a mis-deployed server somehow has the native loaded but
     * the client doesn't, the client gets a spurious "native not loaded"
     * error — that's a config problem worth surfacing regardless.
     */
    public ValidationResult validateForPower() {
        if (!NativeLoader.isLoaded()) return ValidationResult.NATIVE_NOT_LOADED;

        ItemStack mbStack = caseBE.getItem(0);
        if (!(mbStack.getItem() instanceof MotherboardItem)) {
            return ValidationResult.NO_MOTHERBOARD;
        }
        ItemStack cpu = motherboardSlots.getItem(MotherboardItem.SLOT_CPU);
        if (!(cpu.getItem() instanceof CpuItem)) return ValidationResult.NO_CPU;

        ItemStack flash = motherboardSlots.getItem(MotherboardItem.SLOT_FLASH);
        if (!(flash.getItem() instanceof FlashItem)) return ValidationResult.NO_FLASH;

        boolean anyRam = false;
        for (int i = MotherboardItem.SLOT_RAM_START; i <= MotherboardItem.SLOT_RAM_END; i++) {
            if (motherboardSlots.getItem(i).getItem() instanceof RamItem) {
                anyRam = true;
                break;
            }
        }
        if (!anyRam) return ValidationResult.NO_RAM;

        return ValidationResult.OK;
    }

    @Override
    public void broadcastChanges() {
        // Push live power state to the data-slot before the base class
        // diffs and sends container updates. Called from the server's
        // ServerGamePacketListener tick; safe to query caseBE.isPowered()
        // here — on the server that talks to MachineManager directly.
        powerState.set(caseBE.isPowered() ? 1 : 0);
        super.broadcastChanges();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        final int caseSlotCount = SlotDef.COMPUTER_CASE.size();
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (index < caseSlotCount) {
            if (!moveItemStackTo(stack, caseSlotCount, slots.size(), true)) return ItemStack.EMPTY;
        } else {
            if (!moveItemStackTo(stack, 0, caseSlotCount, false)) return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY); else slot.setChanged();
        return original;
    }

    @Override
    public boolean stillValid(Player player) {
        return caseBE.stillValid(player);
    }
}
