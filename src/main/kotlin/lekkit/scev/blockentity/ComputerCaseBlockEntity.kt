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
import lekkit.scev.items.MotherboardItem
import lekkit.scev.machine.BootSplash
import lekkit.scev.machine.GpioPinMap
import lekkit.scev.machine.MachineSpec
import lekkit.scev.machine.MachineSpecParser
import lekkit.scev.server.IMachineHandle
import lekkit.scev.server.MachineDisplayStreamer
import lekkit.scev.server.MachineManager
import lekkit.scev.server.MachineState
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.HolderLookup
import net.minecraft.core.NonNullList
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Container
import net.minecraft.world.ContainerHelper
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState

/**
 * Computer-case BE. Implements [Container] so that menu slots can attach
 * directly. The H.264 + framebuffer broadcast pipeline lives in
 * [MachineDisplayStreamer]; this class focuses on inventory, IMachineHandle,
 * and the redstone↔GPIO bridge.
 */
abstract class ComputerCaseBlockEntity protected constructor(
    type: BlockEntityType<*>,
    pos: BlockPos,
    state: BlockState,
    @JvmField protected val maxMotherboardLevel: Int,
    extensionSlots: Int,
) : ScevBlockEntity(type, pos, state), IMachineHandle, Container {

    @JvmField protected val caseSlots: Int = extensionSlots + 1   // +1 motherboard slot
    @JvmField protected var items: NonNullList<ItemStack> = NonNullList.withSize(caseSlots, ItemStack.EMPTY)
    @JvmField protected var machineUuid: UUID = UUID.randomUUID()
    @JvmField protected var unloaded: Boolean = false

    /** Incremented every serverTick, used for animated splash / diagnostics. */
    @JvmField protected var tickCount: Int = 0

    /**
     * Encodes the framebuffer to H.264 and broadcasts it to nearby players.
     * Lifetime-bound to this BE; one instance per machine. Internal encoder
     * is lazy-init on the first frame.
     */
    private val displayStreamer = MachineDisplayStreamer()

    /**
     * Peripheral bus controller — lazily created on first tick so we have a
     * valid level reference. Null until then. See [PeripheralBus] for the
     * scan model.
     */
    @JvmField protected var peripheralBus: PeripheralBusController? = null

    /* ---------------- IMachineHandle ---------------- */

    override fun getMachineUUID(): UUID = machineUuid

    override fun isValid(): Boolean =
        !isRemoved && level != null && level!!.getBlockEntity(blockPos) === this

    override fun powerOn() {
        val state = initMachineState() ?: return
        // Hand the machine its world location so systems that broadcast
        // per-machine packets (e.g. SoundStreamManager) know where to send
        // them. Only the server-side ServerLevel is meaningful for packet
        // dispatch; on the client-side integrated server both sides agree
        // and this is still ServerLevel.
        (level as? ServerLevel)?.let { state.setLocation(it, blockPos) }
        state.start()
    }

    override fun powerOff() {
        val uuid = machineUuid
        MachineManager.destroyMachineState(uuid)
        // Send the dispose sentinel to evict client DisplayStates and
        // close the encoder in one call.
        val sl = level as? ServerLevel
        if (sl != null) displayStreamer.dispose(sl, blockPos, uuid) else displayStreamer.close()
    }

    override fun power() {
        if (isPowered()) powerOff() else powerOn()
    }

    override fun reset() {
        MachineManager.getMachineState(machineUuid)?.reset()
    }

    override fun isPowered(): Boolean =
        MachineManager.getMachineState(machineUuid)?.isPowered ?: false

    /* ---------------- Redstone / GPIO ---------------- */

    /** Accessor for the peripheral-bus scan result. Null until first tick. */
    fun peripheralBus(): PeripheralBus? = peripheralBus?.getBus()

    override fun setRemoved() {
        super.setRemoved()
        // Release bus bindings on all elements so peripherals don't keep
        // pointing at a dead computer. Idempotent — safe on chunk unload.
        peripheralBus?.dispose()
        // Free the per-machine H.264 encoder if we had one — otherwise a
        // chunk-unload-while-running leaks the native encoder state until
        // destroyMachineState runs. No broadcast: clients will re-render
        // once the chunk reloads and the BE comes back.
        displayStreamer.close()
    }

    /** Notify the bus controller that something nearby changed. */
    fun invalidatePeripheralBus() {
        peripheralBus?.invalidate()
    }

    override fun onNeighborBlockChanged(fromPos: BlockPos) = invalidatePeripheralBus()

    /**
     * Forward a packed 6-bit redstone input to the GPIO card (if installed).
     *
     * `signals` arrives in world-oriented form: bit N = `Direction.ordinal()` N.
     * The VM expects block-relative pins (FRONT/BACK/LEFT/RIGHT/TOP/BOTTOM)
     * so firmware authors get a stable layout regardless of which way the
     * case was placed. Remap via [GpioPinMap.worldToRelative] before handing
     * to the GPIO device.
     */
    override fun onRedstoneInput(signals: Int) {
        val state = MachineManager.getMachineState(machineUuid) ?: return
        val gpio = state.gpio ?: return
        gpio.writePins(GpioPinMap.worldToRelative(signals and GpioPinMap.PIN_MASK, facing()))
    }

    /**
     * Block's current horizontal facing. Falls back to NORTH when the block
     * state has no FACING property — defensive guard for any future
     * non-directional case.
     */
    private fun facing(): Direction {
        val bs = blockState
        return if (bs.hasProperty(DirectionalBlock.FACING)) bs.getValue(DirectionalBlock.FACING) else Direction.NORTH
    }

    /**
     * Tick:
     *   1. Poll the GPIO card; push pin changes out as redstone signals so
     *      wires / lamps / comparators see them.
     *   2. Paint the animated [BootSplash] heartbeat into the framebuffer
     *      so the user has visible proof the server is ticking this
     *      machine — even when no firmware is rendering.
     *   3. Encode + broadcast the framebuffer via [MachineDisplayStreamer].
     */
    override fun serverTick(level: Level, pos: BlockPos, state: BlockState) {
        if (level.isClientSide) return

        // Peripheral-bus scan runs every tick (cheap — short-circuits when
        // the bus is clean) regardless of VM state. We want keyboards /
        // displays to discover the computer whether it's powered or not;
        // attachment is "you belong to this machine", not "I'm running".
        val bus = peripheralBus ?: PeripheralBusController(level, pos, machineUuid).also { peripheralBus = it }
        bus.tick()

        val machineState = MachineManager.getMachineState(machineUuid) ?: return
        tickCount++

        // Redstone output sync. The GPIO device speaks block-relative pins;
        // project back to world-oriented before storing (setOutRedstoneSignals
        // expects bit N = Direction.ordinal() N, since that's what
        // DirectionalBlock#getSignal indexes into per-face).
        val gpio = machineState.gpio
        if (gpio == null) {
            if (outRedstoneSignals != 0) outRedstoneSignals = 0
        } else {
            val relPins = gpio.readPins() and GpioPinMap.PIN_MASK
            val worldPins = GpioPinMap.relativeToWorld(relPins, facing())
            if (worldPins != outRedstoneSignals) outRedstoneSignals = worldPins
        }

        // Animated splash heartbeat + broadcast.
        val fb = machineState.display
        if (fb != null) {
            // The heartbeat dot at framebuffer (20, 20) only exists to
            // reassure the player while no firmware is rendering yet —
            // 10 s after power-on, either the guest is up (and the dot
            // is now stomping on its top-left corner every tick), or
            // the guest is hung and an animated dot won't help. Either
            // way: stop painting.
            if (tickCount < HEARTBEAT_TIMEOUT_TICKS) {
                BootSplash.paintHeartbeat(fb, tickCount)
            }
            // Broadcast every server tick (20 Hz). Singleplayer also broadcasts
            // because DisplayManager.OPTIMIZE_SINGLEPLAYER is off while the
            // codec stabilises — the integrated server's memory-pipe transport
            // keeps this near-free regardless.
            (level as? ServerLevel)?.let {
                displayStreamer.tick(it, pos, machineUuid, fb, machineState.clock)
            }
        }
    }

    /** Direction-specific outgoing signal (0 or 15), for `block.getSignal`. */
    fun getRedstoneSignalFor(dir: Direction): Int = getOutRedstoneSignal(dir)

    override fun getMotherboardItem(): MotherboardItem? =
        items[0].item as? MotherboardItem

    override fun getCaseSlotCount(): Int = caseSlots
    override fun getMaxMotherboardLevel(): Int = maxMotherboardLevel

    /* ---------------- Machine state ---------------- */

    /**
     * If true, the machine always gets a display attached, even without a
     * VGA PCI card (laptops / tinkerpads ship with a built-in screen).
     * Subclasses override to flip the flag.
     */
    protected open fun forceBuiltInDisplay(): Boolean = false

    /** Existing machine, or build a new one from the current motherboard. */
    protected fun initMachineState(): MachineState? =
        MachineManager.getMachineState(machineUuid) ?: buildMachine()

    /**
     * Parse the motherboard + components into a [MachineSpec], then ask
     * [MachineManager] to instantiate a backend. Returns null if no
     * motherboard is installed or backend creation fails.
     */
    protected fun buildMachine(): MachineState? {
        val mbStack = items[0]
        val spec: MachineSpec = MachineSpecParser.fromMotherboard(
            machineUuid, mbStack, forceBuiltInDisplay()
        ) ?: return null
        // Parser may have mutated sub-stacks inside the motherboard's
        // MOTHERBOARD_INVENTORY data component (STORAGE_UUID allocation on
        // flash / NVMe). Persist those mutations by flagging the BE dirty
        // so the next save round-trip captures them. Without this,
        // allocated UUIDs get re-generated every boot, template bytes get
        // re-copied, and player-written data is orphaned under a stale id.
        setChanged()
        return MachineManager.createMachineState(spec)
    }

    /* ---------------- NBT ---------------- */

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        items = NonNullList.withSize(caseSlots, ItemStack.EMPTY)
        ContainerHelper.loadAllItems(tag, items, registries)
        if (tag.hasUUID("MachineUUID")) machineUuid = tag.getUUID("MachineUUID")
    }

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        ContainerHelper.saveAllItems(tag, items, registries)
        tag.putUUID("MachineUUID", machineUuid)
    }

    /* ---------------- Container ---------------- */

    override fun getContainerSize(): Int = items.size

    override fun isEmpty(): Boolean = items.all { it.isEmpty }

    override fun getItem(slot: Int): ItemStack =
        if (slot in 0 until items.size) items[slot] else ItemStack.EMPTY

    override fun removeItem(slot: Int, amount: Int): ItemStack {
        val removed = ContainerHelper.removeItem(items, slot, amount)
        if (!removed.isEmpty) setChanged()
        return removed
    }

    override fun removeItemNoUpdate(slot: Int): ItemStack =
        ContainerHelper.takeItem(items, slot)

    override fun setItem(slot: Int, stack: ItemStack) {
        if (slot in 0 until items.size) {
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
            blockPos.z + 0.5,
        ) <= 64.0

    override fun clearContent() {
        items.clear()
        setChanged()
    }

    override fun canPlaceItem(slot: Int, stack: ItemStack): Boolean {
        if (slot == 0) {
            val mb = stack.item as? MotherboardItem ?: return false
            return mb.level <= maxMotherboardLevel
        }
        return true
    }

    companion object {
        /**
         * Stop painting the BootSplash heartbeat dot after this many ticks
         * post power-on. 200 ticks @ 20 Hz = 10 s, which covers any sane
         * RVVM cold boot to the point where the guest's first framebuffer
         * write happens; past that the dot just stomps on the guest's
         * top-left corner every tick.
         */
        private const val HEARTBEAT_TIMEOUT_TICKS: Int = 200
    }
}
