/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.blockentity

import java.util.UUID
import lekkit.scev.blocks.DirectionalBlock
import lekkit.scev.bus.PeripheralBus
import lekkit.scev.bus.PeripheralBusController
import lekkit.scev.items.FlashItem
import lekkit.scev.items.MotherboardItem
import lekkit.scev.items.SocItem
import lekkit.scev.machine.GpioPinMap
import lekkit.scev.machine.MachineSpec
import lekkit.scev.machine.MachineSpecParser
import lekkit.scev.main.ScevRegistry
import lekkit.scev.server.IMachineHandle
import lekkit.scev.server.MachineManager
import lekkit.scev.server.MachineState
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.HolderLookup
import net.minecraft.core.NonNullList
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.Container
import net.minecraft.world.ContainerHelper
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState

/**
 * Block entity for the MCU board — a reduced-form computer with no motherboard.
 *
 * Analogous to [ComputerCaseBlockEntity] but thinner:
 *   - 2-slot inventory: slot 0 = [SocItem], slot 1 = [FlashItem]. The SoC
 *     replaces both CPU and RAM (on-die); the flash holds the firmware.
 *   - No PCI, no NVMe, no display. GPIO is implicit (the SoC itself exposes
 *     redstone pins per [MachineSpec.hasGpio]).
 *   - Right-click: toggle power. Shift-right-click: open the 2-slot
 *     installation menu. OC1's hot-swap feel with a minimal GUI.
 *
 * Deliberately does **not** extend [ComputerCaseBlockEntity]: that class is
 * tightly coupled to the motherboard model (14-slot inventory, MotherboardItem
 * validation, framebuffer broadcast, [IMachineHandle.getMotherboardItem]
 * semantics). Sharing [MachineManager] is enough — the surface area above is
 * small for MCU.
 */
open class McuBoardBlockEntity(pos: BlockPos, state: BlockState) :
    ScevBlockEntity(ScevRegistry.MCU_BOARD_BE.get(), pos, state),
    IMachineHandle, Container {

    protected var items: NonNullList<ItemStack> = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY)
    protected var machineUuid: UUID = UUID.randomUUID()

    /** Incremented every server tick — diagnostics / future animation. */
    protected var tickCount: Int = 0

    /** Peripheral bus controller. Lazily created on first server tick. */
    protected var peripheralBus: PeripheralBusController? = null

    /* ---------------- IMachineHandle ---------------- */

    override fun getMachineUUID(): UUID = machineUuid

    override fun isValid(): Boolean =
        !isRemoved && level != null && level!!.getBlockEntity(blockPos) === this

    override fun powerOn() {
        initMachineState()?.start()
    }

    override fun powerOff() {
        MachineManager.destroyMachineState(machineUuid)
    }

    override fun power() {
        if (isPowered()) powerOff() else powerOn()
    }

    override fun reset() {
        MachineManager.getMachineState(machineUuid)?.reset()
    }

    override fun isPowered(): Boolean =
        MachineManager.getMachineState(machineUuid)?.isPowered ?: false

    /**
     * MCU has no motherboard — the SoC stands in for one. Returning null keeps
     * the [IMachineHandle] contract satisfied without pretending there's a
     * [MotherboardItem]; screens branching on null degrade to "no motherboard".
     */
    override fun getMotherboardItem(): MotherboardItem? = null

    override fun getCaseSlotCount(): Int = SLOT_COUNT
    override fun getMaxMotherboardLevel(): Int = 0

    fun peripheralBus(): PeripheralBus? = peripheralBus?.getBus()

    override fun onNeighborBlockChanged(fromPos: BlockPos) {
        peripheralBus?.invalidate()
    }

    override fun setRemoved() {
        super.setRemoved()
        peripheralBus?.dispose()
    }

    /* ---------------- Redstone / GPIO ---------------- */

    /**
     * World-oriented 6-bit map from [DirectionalBlock.neighborChanged].
     * Rotated to the block-relative layout ([GpioPinMap]) before writing to
     * the GPIO device, so guest firmware always sees FRONT/BACK/LEFT/RIGHT/
     * TOP/BOTTOM regardless of block facing.
     */
    override fun onRedstoneInput(signals: Int) {
        val state = MachineManager.getMachineState(machineUuid) ?: return
        val gpio = state.gpio ?: return
        gpio.writePins(GpioPinMap.worldToRelative(signals and GpioPinMap.PIN_MASK, facing()))
    }

    private fun facing(): Direction {
        val bs = blockState
        return if (bs.hasProperty(DirectionalBlock.FACING)) bs.getValue(DirectionalBlock.FACING)
               else Direction.NORTH
    }

    /**
     * Sample the GPIO, rotate relative-pins back to world, push to redstone.
     * MCU has no framebuffer to paint / broadcast — that's the only thing
     * missing vs [ComputerCaseBlockEntity.serverTick].
     */
    override fun serverTick(level: Level, pos: BlockPos, state: BlockState) {
        if (level.isClientSide) return

        val bus = peripheralBus ?: PeripheralBusController(level, pos, machineUuid).also { peripheralBus = it }
        bus.tick()

        val machineState = MachineManager.getMachineState(machineUuid) ?: return
        tickCount++

        val gpio = machineState.gpio
        if (gpio == null) {
            if (outRedstoneSignals != 0) outRedstoneSignals = 0
        } else {
            val relPins = gpio.readPins() and GpioPinMap.PIN_MASK
            val worldPins = GpioPinMap.relativeToWorld(relPins, facing())
            if (worldPins != outRedstoneSignals) outRedstoneSignals = worldPins
        }
    }

    /* ---------------- Machine spec ---------------- */

    /** Return the existing machine or build a new one from the installed SoC + flash. */
    protected fun initMachineState(): MachineState? =
        MachineManager.getMachineState(machineUuid) ?: buildMachine()

    /**
     * Hand the installed SoC + flash to [MachineSpecParser.fromMcu]. Returns
     * null when either slot is empty — preflight validation in the menu's
     * `validateForPower` is expected to catch that earlier; this is the
     * defense-in-depth return.
     */
    protected fun buildMachine(): MachineState? {
        val soc = items[SLOT_SOC]
        val flash = items[SLOT_FLASH]
        val spec = MachineSpecParser.fromMcu(machineUuid, soc, flash) ?: return null
        // Parser may have mutated flash (STORAGE_UUID allocation); flag the
        // BE dirty so NBT save captures the new component.
        setChanged()
        return MachineManager.createMachineState(spec)
    }

    /* ---------------- NBT ---------------- */

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        items = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY)
        ContainerHelper.loadAllItems(tag, items, registries)
        if (tag.hasUUID("MachineUUID")) machineUuid = tag.getUUID("MachineUUID")
    }

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        ContainerHelper.saveAllItems(tag, items, registries)
        tag.putUUID("MachineUUID", machineUuid)
    }

    /* ---------------- Container ---------------- */

    override fun getContainerSize(): Int = SLOT_COUNT

    override fun isEmpty(): Boolean = items.all { it.isEmpty }

    override fun getItem(slot: Int): ItemStack =
        if (slot in 0 until SLOT_COUNT) items[slot] else ItemStack.EMPTY

    override fun removeItem(slot: Int, amount: Int): ItemStack {
        val removed = ContainerHelper.removeItem(items, slot, amount)
        if (!removed.isEmpty) setChanged()
        return removed
    }

    override fun removeItemNoUpdate(slot: Int): ItemStack =
        ContainerHelper.takeItem(items, slot)

    override fun setItem(slot: Int, stack: ItemStack) {
        if (slot in 0 until SLOT_COUNT) {
            items[slot] = stack
            if (stack.count > maxStackSize) stack.count = maxStackSize
            setChanged()
        }
    }

    override fun getMaxStackSize(): Int = 1

    override fun stillValid(player: Player): Boolean =
        isValid() && player.distanceToSqr(
            blockPos.x + 0.5,
            blockPos.y + 0.5,
            blockPos.z + 0.5
        ) <= 64.0

    override fun clearContent() {
        items.clear()
        setChanged()
    }

    override fun canPlaceItem(slot: Int, stack: ItemStack): Boolean = when (slot) {
        SLOT_SOC -> stack.item is SocItem
        SLOT_FLASH -> stack.item is FlashItem
        else -> false
    }

    companion object {
        const val SLOT_SOC = 0
        const val SLOT_FLASH = 1
        const val SLOT_COUNT = 2
    }
}
