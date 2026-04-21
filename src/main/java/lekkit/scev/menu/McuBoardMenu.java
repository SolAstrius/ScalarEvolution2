/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.menu;

import java.util.Locale;
import java.util.UUID;
import lekkit.scev.blockentity.McuBoardBlockEntity;
import lekkit.scev.items.FlashItem;
import lekkit.scev.items.SocItem;
import lekkit.scev.machine.GpioDevice;
import lekkit.scev.main.ScevRegistry;
import lekkit.scev.server.MachineManager;
import lekkit.scev.server.MachineState;
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
 * Single MCU menu — combines component install + readout + power control.
 *
 * <p>Layout coords follow the drone-style GUI reused at
 * {@code textures/gui/mcu_board.png}: black console panel in the top-left
 * (rendered-in-screen), two slots in the top-right for SoC and Flash at
 * (98, 8) and (116, 8), player inventory starting at (8, 66). The
 * {@link McuBoardScreen} paints live state text into the console panel
 * and GPIO LEDs onto the middle strip.
 *
 * <p>Three {@link DataSlot}s carry server→client state each tick:
 * <ul>
 *   <li>{@link #powerState} — 0/1 for off/on, drives the power button visual.</li>
 *   <li>{@link #gpioIn}  — guest's INPUT register low 6 bits (block-relative).</li>
 *   <li>{@link #gpioOut} — guest's OUTPUT register low 6 bits.</li>
 * </ul>
 */
public class McuBoardMenu extends AbstractContainerMenu {
    /** SoC slot coords — top-right, first cell of the drone-style grid. */
    private static final int SOC_SLOT_X   = 98;
    private static final int SOC_SLOT_Y   = 8;
    /** Flash slot coords — 18-px to the right of SoC. */
    private static final int FLASH_SLOT_X = 116;
    private static final int FLASH_SLOT_Y = 8;

    /** Drone-style player inventory positions carried through from the PNG. */
    private static final int PLAYER_INV_X = 8;
    private static final int PLAYER_INV_Y = 66;
    private static final int PLAYER_HOTBAR_Y = 124;

    private final McuBoardBlockEntity mcu;
    private final DataSlot powerState = DataSlot.standalone();
    private final DataSlot gpioIn = DataSlot.standalone();
    private final DataSlot gpioOut = DataSlot.standalone();

    public McuBoardMenu(int id, Inventory inv, McuBoardBlockEntity mcu) {
        super(ScevRegistry.MCU_BOARD_MENU.get(), id);
        this.mcu = mcu;

        // Kind-gated component slots. Accept only the right item class, so
        // a player can't drop a CPU into the SoC slot by mistake.
        addSlot(new KindGatedSlot(mcu, McuBoardBlockEntity.SLOT_SOC, SOC_SLOT_X, SOC_SLOT_Y,
                s -> s.getItem() instanceof SocItem));
        addSlot(new KindGatedSlot(mcu, McuBoardBlockEntity.SLOT_FLASH, FLASH_SLOT_X, FLASH_SLOT_Y,
                s -> s.getItem() instanceof FlashItem));

        addPlayerInventory(inv);

        addDataSlot(powerState);
        addDataSlot(gpioIn);
        addDataSlot(gpioOut);
    }

    private void addPlayerInventory(Inventory inv) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inv, col + row * 9 + 9,
                        PLAYER_INV_X + col * 18, PLAYER_INV_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inv, col, PLAYER_INV_X + col * 18, PLAYER_HOTBAR_Y));
        }
    }

    public static McuBoardMenu fromNetwork(int id, Inventory inv, RegistryFriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        BlockEntity be = inv.player.level().getBlockEntity(pos);
        if (be instanceof McuBoardBlockEntity mcu) return new McuBoardMenu(id, inv, mcu);
        throw new IllegalStateException("No McuBoardBlockEntity at " + pos);
    }

    public UUID getMachineUuid() { return mcu.getMachineUUID(); }
    public McuBoardBlockEntity getMcu() { return mcu; }

    public boolean isMachinePowered() { return powerState.get() != 0; }
    public int getGpioInputMask()  { return gpioIn.get() & 0x3F; }
    public int getGpioOutputMask() { return gpioOut.get() & 0x3F; }

    /**
     * Preflight for power-on. Each non-OK value maps to
     * {@code text.scev.power.fail.<name>} lang key for the on-screen error.
     */
    public enum ValidationResult {
        OK,
        NATIVE_NOT_LOADED,
        NO_SOC,
        NO_FLASH;

        public String langKey() {
            return "text.scev.power.fail." + name().toLowerCase(Locale.ROOT);
        }
    }

    public ValidationResult validateForPower() {
        if (!NativeLoader.isLoaded()) return ValidationResult.NATIVE_NOT_LOADED;
        ItemStack soc = mcu.getItem(McuBoardBlockEntity.SLOT_SOC);
        if (!(soc.getItem() instanceof SocItem)) return ValidationResult.NO_SOC;
        ItemStack flash = mcu.getItem(McuBoardBlockEntity.SLOT_FLASH);
        if (!(flash.getItem() instanceof FlashItem)) return ValidationResult.NO_FLASH;
        return ValidationResult.OK;
    }

    @Override
    public void broadcastChanges() {
        powerState.set(mcu.isPowered() ? 1 : 0);

        // Pull live GPIO state from the running machine for the LED row.
        // When the VM isn't running we zero both — the LEDs dim out so the
        // player can tell the machine is halted at a glance.
        MachineState ms = MachineManager.getMachineState(mcu.getMachineUUID());
        GpioDevice gpio = ms != null ? ms.getGPIO() : null;
        if (gpio == null) {
            gpioIn.set(0);
            gpioOut.set(0);
        } else {
            // The GPIO device exposes a single 6-bit view of current pin
            // state. Separating INPUT vs OUTPUT requires a bigger API; for
            // now both LED rows reflect the same mask (what the wire carries
            // at this instant). When we add GpioDevice.readInputs a follow-up
            // can split the two into distinct values.
            int state = gpio.readPins() & 0x3F;
            gpioIn.set(state);
            gpioOut.set(state);
        }

        super.broadcastChanges();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        final int mcuSlotCount = McuBoardBlockEntity.SLOT_COUNT;
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (index < mcuSlotCount) {
            // From MCU slot → player inventory.
            if (!moveItemStackTo(stack, mcuSlotCount, slots.size(), true)) return ItemStack.EMPTY;
        } else {
            // From player inventory → MCU slots. Kind-gated slots only accept
            // the matching item class; a non-SoC/non-Flash shift-click returns
            // empty so the stack stays in the player inventory.
            if (!moveItemStackTo(stack, 0, mcuSlotCount, false)) return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY); else slot.setChanged();
        return original;
    }

    @Override
    public boolean stillValid(Player player) {
        return mcu.stillValid(player);
    }

    /** Slot that only accepts items passing a predicate. Max stack size 1. */
    private static final class KindGatedSlot extends Slot {
        private final java.util.function.Predicate<ItemStack> accept;

        KindGatedSlot(net.minecraft.world.Container container, int slotIndex, int x, int y,
                      java.util.function.Predicate<ItemStack> accept) {
            super(container, slotIndex, x, y);
            this.accept = accept;
        }

        @Override
        public boolean mayPlace(ItemStack stack) { return accept.test(stack); }

        @Override
        public int getMaxStackSize() { return 1; }
    }
}
