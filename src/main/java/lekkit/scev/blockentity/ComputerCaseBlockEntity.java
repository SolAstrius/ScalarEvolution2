/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.blockentity;

import java.util.UUID;
import lekkit.scev.items.MotherboardItem;
import lekkit.scev.machine.BootSplash;
import lekkit.scev.machine.FramebufferView;
import lekkit.scev.machine.GpioDevice;
import lekkit.scev.machine.MachineSpec;
import lekkit.scev.machine.MachineSpecParser;
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
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Minimal computer-case block entity. Implements {@link Container} so that menu
 * {@link net.minecraft.world.inventory.Slot}s can attach to it directly.
 */
public abstract class ComputerCaseBlockEntity extends ScevBlockEntity
        implements IMachineHandle, Container {

    protected final int caseSlots;
    protected final int maxMotherboardLevel;
    protected NonNullList<ItemStack> items;
    protected UUID machineUuid;
    protected boolean unloaded;

    /** Incremented every serverTick, used for animated splash / diagnostics. */
    protected int tickCount;

    protected ComputerCaseBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state,
                                      int maxMotherboardLevel, int extensionSlots) {
        super(type, pos, state);
        this.maxMotherboardLevel = maxMotherboardLevel;
        this.caseSlots = extensionSlots + 1; // +1 motherboard slot
        this.items = NonNullList.withSize(caseSlots, ItemStack.EMPTY);
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

    /* ---------------- Redstone / GPIO ---------------- */

    /**
     * Forward a packed 6-bit redstone input to the GPIO card (if installed).
     * Bit N = Direction.ordinal() N. Called by the block's neighbour-update path.
     */
    @Override
    public void onRedstoneInput(int signals) {
        MachineState state = MachineManager.getMachineState(getMachineUUID());
        if (state == null) return;
        GpioDevice gpio = state.getGPIO();
        if (gpio == null) return;
        gpio.writePins(signals & 0x3F);
    }

    /**
     * Ticker:
     * <ol>
     *   <li>Poll the GPIO card, push changes out as redstone signals so wires /
     *       lamps / comparators see them.</li>
     *   <li>Paint the animated {@link BootSplash} heartbeat into the framebuffer
     *       so the user has visible proof the server is ticking this machine —
     *       even when no firmware is rendering. Skipped when no display is
     *       attached. Firmware-written pixels outside the heartbeat region are
     *       left alone.</li>
     * </ol>
     * Skipped entirely when no machine is running.
     */
    @Override
    public void serverTick(Level level, BlockPos pos, BlockState state) {
        if (level.isClientSide) return;
        MachineState machineState = MachineManager.getMachineState(getMachineUUID());
        if (machineState == null) return;
        tickCount++;

        // Redstone output sync.
        GpioDevice gpio = machineState.getGPIO();
        if (gpio == null) {
            if (getOutRedstoneSignals() != 0) setOutRedstoneSignals(0);
        } else {
            int pins = gpio.readPins() & 0x3F;
            if (pins != getOutRedstoneSignals()) setOutRedstoneSignals(pins);
        }

        // Animated splash heartbeat.
        FramebufferView fb = machineState.getDisplay();
        if (fb != null) {
            BootSplash.paintHeartbeat(fb, tickCount);
        }
    }

    /** Direction-specific outgoing signal (0 or 15), for block.getSignal. */
    public int getRedstoneSignalFor(Direction dir) {
        return getOutRedstoneSignal(dir);
    }

    @Override
    public MotherboardItem getMotherboardItem() {
        ItemStack mb = items.get(0);
        return mb.getItem() instanceof MotherboardItem m ? m : null;
    }

    @Override
    public int getCaseSlotCount() { return caseSlots; }

    @Override
    public int getMaxMotherboardLevel() { return maxMotherboardLevel; }

    /* ---------------- Machine state ---------------- */

    /**
     * If true, the machine always gets a display attached, even without a VGA
     * PCI card (laptops / tinkerpads ship with a built-in screen). Default:
     * false. Subclasses override to flip the flag.
     */
    protected boolean forceBuiltInDisplay() { return false; }

    /** Return the existing machine or build a new one from the current motherboard. */
    protected MachineState initMachineState() {
        MachineState state = MachineManager.getMachineState(getMachineUUID());
        if (state == null) state = buildMachine();
        return state;
    }

    /**
     * Build the MachineState by parsing the motherboard + components into a
     * {@link MachineSpec}, then asking {@link MachineManager} to instantiate
     * a backend. Returns {@code null} if no motherboard is installed or
     * backend creation fails.
     */
    protected MachineState buildMachine() {
        ItemStack mbStack = items.get(0);
        MachineSpec spec = MachineSpecParser.fromMotherboard(
                getMachineUUID(), mbStack, forceBuiltInDisplay());
        if (spec == null) return null;
        return MachineManager.createMachineState(spec);
    }

    /* ---------------- NBT ---------------- */

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        items = NonNullList.withSize(caseSlots, ItemStack.EMPTY);
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
    public int getContainerSize() { return items.size(); }

    @Override
    public boolean isEmpty() {
        for (ItemStack stack : items) if (!stack.isEmpty()) return false;
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return slot >= 0 && slot < items.size() ? items.get(slot) : ItemStack.EMPTY;
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
        if (slot >= 0 && slot < items.size()) {
            items.set(slot, stack);
            if (stack.getCount() > getMaxStackSize()) stack.setCount(getMaxStackSize());
            setChanged();
        }
    }

    @Override
    public int getMaxStackSize() { return 1; }

    @Override
    public void setChanged() {
        super.setChanged();
    }

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
        if (slot == 0) {
            return stack.getItem() instanceof MotherboardItem mb
                    && mb.getLevel() <= maxMotherboardLevel;
        }
        return true;
    }
}
