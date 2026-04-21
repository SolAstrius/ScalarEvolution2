/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.blockentity;

import java.util.UUID;
import lekkit.scev.blocks.DirectionalBlock;
import lekkit.scev.items.FlashItem;
import lekkit.scev.items.MotherboardItem;
import lekkit.scev.items.SocItem;
import lekkit.scev.machine.GpioDevice;
import lekkit.scev.machine.GpioPinMap;
import lekkit.scev.machine.MachineSpec;
import lekkit.scev.machine.MachineSpecParser;
import lekkit.scev.main.ScevRegistry;
import lekkit.scev.server.IMachineHandle;
import lekkit.scev.server.MachineManager;
import lekkit.scev.server.MachineState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block entity for the MCU board — a reduced-form computer with no motherboard.
 *
 * <p>Analogous to {@link ComputerCaseBlockEntity} but thinner:
 * <ul>
 *   <li>2-slot inventory: slot 0 = {@link SocItem}, slot 1 = {@link FlashItem}.
 *       The SoC replaces both CPU and RAM (on-die); the flash holds the
 *       firmware the machine boots.</li>
 *   <li>No PCI, no NVMe, no display. GPIO is implicit (the SoC itself
 *       exposes redstone pins per {@link MachineSpec#hasGpio}).</li>
 *   <li>Right-click the block: toggle power. Shift-right-click: open the
 *       2-slot installation menu. Matches OC1's hot-swap feel with a
 *       minimal GUI for component fiddling.</li>
 * </ul>
 *
 * <p>Deliberately does <b>not</b> extend {@link ComputerCaseBlockEntity}:
 * that class is tightly coupled to the motherboard model (14-slot
 * inventory, {@code MotherboardItem} validation, framebuffer broadcast,
 * {@link IMachineHandle#getMotherboardItem} semantics). Sharing the
 * {@link MachineManager} machinery is enough — the surface area above it
 * is small for MCU.
 */
public class McuBoardBlockEntity extends ScevBlockEntity implements IMachineHandle, Container {
    public static final int SLOT_SOC = 0;
    public static final int SLOT_FLASH = 1;
    public static final int SLOT_COUNT = 2;

    protected NonNullList<ItemStack> items;
    protected UUID machineUuid;

    /** Incremented every serverTick, used for diagnostics / future animation. */
    protected int tickCount;

    public McuBoardBlockEntity(BlockPos pos, BlockState state) {
        super(ScevRegistry.MCU_BOARD_BE.get(), pos, state);
        this.items = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
        this.machineUuid = UUID.randomUUID();
    }

    /* ---------------- IMachineHandle ---------------- */

    @Override
    public UUID getMachineUUID() { return machineUuid; }

    @Override
    public boolean isValid() {
        return !isRemoved() && level != null
                && level.getBlockEntity(worldPosition) == this;
    }

    @Override
    public void powerOn() {
        MachineState state = initMachineState();
        if (state != null) state.start();
    }

    @Override
    public void powerOff() {
        MachineManager.destroyMachineState(getMachineUUID());
    }

    @Override
    public void power() {
        if (isPowered()) powerOff(); else powerOn();
    }

    @Override
    public void reset() {
        MachineState state = MachineManager.getMachineState(getMachineUUID());
        if (state != null) state.reset();
    }

    @Override
    public boolean isPowered() {
        MachineState state = MachineManager.getMachineState(getMachineUUID());
        return state != null && state.isPowered();
    }

    /**
     * MCU has no motherboard — the SoC stands in for one. Returning null
     * here keeps {@link IMachineHandle} contract satisfied without
     * pretending there's a {@link MotherboardItem} in the loop; any screen
     * / code that branches on this null gracefully degrades to "no
     * motherboard mode".
     */
    @Override
    public MotherboardItem getMotherboardItem() { return null; }

    @Override
    public int getCaseSlotCount() { return SLOT_COUNT; }

    @Override
    public int getMaxMotherboardLevel() { return 0; }

    /* ---------------- Redstone / GPIO ---------------- */

    /**
     * World-oriented 6-bit map from {@link DirectionalBlock#neighborChanged}.
     * Rotated to the block-relative layout ({@link GpioPinMap}) before
     * writing to the GPIO device so guest firmware always sees FRONT/BACK/
     * LEFT/RIGHT/TOP/BOTTOM regardless of block facing.
     */
    @Override
    public void onRedstoneInput(int signals) {
        MachineState state = MachineManager.getMachineState(getMachineUUID());
        if (state == null) return;
        GpioDevice gpio = state.getGPIO();
        if (gpio == null) return;
        gpio.writePins(GpioPinMap.worldToRelative(signals & GpioPinMap.PIN_MASK, facing()));
    }

    private Direction facing() {
        BlockState bs = getBlockState();
        if (bs.hasProperty(DirectionalBlock.FACING)) {
            return bs.getValue(DirectionalBlock.FACING);
        }
        return Direction.NORTH;
    }

    /**
     * Sample the GPIO, rotate relative-pins back to world, push to redstone.
     * MCU has no framebuffer to paint / broadcast — that's the only thing
     * missing vs {@link ComputerCaseBlockEntity#serverTick}.
     */
    @Override
    public void serverTick(Level level, BlockPos pos, BlockState state) {
        if (level.isClientSide) return;
        MachineState machineState = MachineManager.getMachineState(getMachineUUID());
        if (machineState == null) return;
        tickCount++;

        GpioDevice gpio = machineState.getGPIO();
        if (gpio == null) {
            if (getOutRedstoneSignals() != 0) setOutRedstoneSignals(0);
        } else {
            int relPins = gpio.readPins() & GpioPinMap.PIN_MASK;
            int worldPins = GpioPinMap.relativeToWorld(relPins, facing());
            if (worldPins != getOutRedstoneSignals()) setOutRedstoneSignals(worldPins);
        }
    }

    /* ---------------- Machine spec ---------------- */

    /** Return the existing machine or build a new one from the installed SoC + flash. */
    protected MachineState initMachineState() {
        MachineState state = MachineManager.getMachineState(getMachineUUID());
        if (state == null) state = buildMachine();
        return state;
    }

    /**
     * Build the MachineState by handing the installed SoC + flash to
     * {@link MachineSpecParser#fromMcu}. Returns {@code null} when either
     * slot is empty — preflight validation (done client-side in the
     * menu's {@code validateForPower}) is expected to catch that earlier;
     * this is the defense-in-depth return.
     */
    protected MachineState buildMachine() {
        ItemStack soc = items.get(SLOT_SOC);
        ItemStack flash = items.get(SLOT_FLASH);
        MachineSpec spec = MachineSpecParser.fromMcu(getMachineUUID(), soc, flash);
        if (spec == null) return null;
        return MachineManager.createMachineState(spec);
    }

    /* ---------------- NBT ---------------- */

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        items = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(tag, items, registries);
        if (tag.hasUUID("MachineUUID")) {
            machineUuid = tag.getUUID("MachineUUID");
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ContainerHelper.saveAllItems(tag, items, registries);
        tag.putUUID("MachineUUID", machineUuid);
    }

    /* ---------------- Container ---------------- */

    @Override
    public int getContainerSize() { return SLOT_COUNT; }

    @Override
    public boolean isEmpty() {
        for (ItemStack s : items) if (!s.isEmpty()) return false;
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return slot >= 0 && slot < SLOT_COUNT ? items.get(slot) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack removed = ContainerHelper.removeItem(items, slot, amount);
        if (!removed.isEmpty()) setChanged();
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return ContainerHelper.takeItem(items, slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot >= 0 && slot < SLOT_COUNT) {
            items.set(slot, stack);
            if (stack.getCount() > getMaxStackSize()) stack.setCount(getMaxStackSize());
            setChanged();
        }
    }

    @Override
    public int getMaxStackSize() { return 1; }

    @Override
    public boolean stillValid(Player player) {
        return isValid() && player.distanceToSqr(
                worldPosition.getX() + 0.5,
                worldPosition.getY() + 0.5,
                worldPosition.getZ() + 0.5) <= 64.0;
    }

    @Override
    public void clearContent() {
        items.clear();
        setChanged();
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return switch (slot) {
            case SLOT_SOC -> stack.getItem() instanceof SocItem;
            case SLOT_FLASH -> stack.getItem() instanceof FlashItem;
            default -> false;
        };
    }
}
