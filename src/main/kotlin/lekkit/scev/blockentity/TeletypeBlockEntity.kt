/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.blockentity

import java.nio.charset.StandardCharsets
import java.util.UUID
import lekkit.scev.bus.PeripheralBusElement
import lekkit.scev.bus.PeripheralDeviceKind
import lekkit.scev.main.ScevDataComponents
import lekkit.scev.main.ScevRegistry
import lekkit.scev.rpc.KernelConsoleSink
import lekkit.scev.rpc.ScevRpcManager
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.core.NonNullList
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.nbt.Tag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Container
import net.minecraft.world.ContainerHelper
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState

/**
 * Teletype block — first consumer of [PaperRollItem] + [RibbonItem].
 *
 * Slot layout:
 *  - 0: paper roll  (decrements `PAPER_LINES_REMAINING` per printed line)
 *  - 1: ribbon      (decrements `RIBBON_INK_REMAINING` per printed char)
 *
 * Holds a small in-memory tail of recently-printed lines for the
 * GUI to display ("the page currently visible above the platen").
 * Lines that scroll off the visible window go nowhere — the player
 * sees them on screen as the print happens, then they're gone, just
 * like a real teletype where the printed paper rolls down and
 * eventually onto the floor.
 *
 * **Wire to incoming bytes:** [printText] is the public entry point.
 * For the v1 it's only called from a "Print Test Page" GUI button;
 * follow-up will hook it to [SerialDispatcher] so an upstream
 * machine's UART output can drive the teletype directly.
 */
class TeletypeBlockEntity(pos: BlockPos, state: BlockState) :
    ScevBlockEntity(ScevRegistry.TELETYPE_BE.get(), pos, state),
    Container,
    PeripheralBusElement {

    private val items: NonNullList<ItemStack> = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY)

    /* ---------------- Peripheral-bus binding (serial sink) ---------------- */

    private var bound: UUID? = null
    private var boundPos: BlockPos? = null
    private var sinkRegisteredFor: UUID? = null
    private var serverLevelRef: ServerLevel? = null

    /** Pending bytes from the kernel-console drain. Capped — at the
     *  rate-limit drain rate this comfortably holds 1+ minute of
     *  typical kernel printk; bytes beyond the cap are dropped (real
     *  teletypes did the same when they couldn't keep up). */
    private val printQueue: ArrayDeque<Byte> = ArrayDeque(PRINT_QUEUE_CAP)

    private val consoleSink = object : KernelConsoleSink {
        override fun onConsoleBytes(bytes: ByteArray, len: Int) {
            // Append to print queue — the per-tick drain in
            // [serverTick] consumes them at a teletype-realistic
            // rate. Drop on overflow rather than backpressuring
            // the kernel TX (the guest doesn't know it's being
            // throttled).
            for (i in 0 until len) {
                if (printQueue.size >= PRINT_QUEUE_CAP) break
                printQueue.addLast(bytes[i])
            }
        }
    }

    override fun peripheralKinds(): Set<PeripheralDeviceKind> = setOf(PeripheralDeviceKind.SERIAL)

    override fun boundMachineUuid(): UUID? = bound
    override fun setBoundMachineUuid(uuid: UUID?) { bound = uuid }
    override fun boundMachinePos(): BlockPos? = boundPos
    override fun setBoundMachinePos(pos: BlockPos?) { boundPos = pos }

    override fun serverTick(level: Level, pos: BlockPos, state: BlockState) {
        if (level !is ServerLevel) return
        serverLevelRef = level

        // Sync sink registration with current bind.
        val want = bound
        val have = sinkRegisteredFor
        if (want != have) {
            if (have != null) {
                ScevRpcManager.get(have)?.removeConsoleSink(consoleSink)
                sinkRegisteredFor = null
            }
            if (want != null) {
                val mgr = ScevRpcManager.get(want)
                if (mgr != null) {
                    mgr.addConsoleSink(consoleSink)
                    sinkRegisteredFor = want
                }
            }
        }

        // Rate-limited drain — at most CHARS_PER_TICK chars hit the
        // physical print this tick, mimicking ASR-33's ~10 char/sec
        // mechanical throughput. Excess stays in the queue.
        if (printQueue.isEmpty()) return
        val toDrain = minOf(printQueue.size, CHARS_PER_TICK)
        if (toDrain == 0) return
        val chunk = ByteArray(toDrain)
        for (i in 0 until toDrain) chunk[i] = printQueue.removeFirst()
        // Pass through printText so paper/ribbon decrement + line
        // wrap + visible-page tracking all happen the same as for
        // the manual Print Test Page button.
        printText(String(chunk, StandardCharsets.UTF_8))
    }

    override fun setRemoved() {
        val have = sinkRegisteredFor
        if (have != null) {
            ScevRpcManager.get(have)?.removeConsoleSink(consoleSink)
            sinkRegisteredFor = null
        }
        super.setRemoved()
    }

    /* ---------------- Storage ---------------- */

    /** Most-recent N printed lines, for the GUI's "current page" view. */
    private val recentLines: ArrayDeque<String> = ArrayDeque(MAX_VISIBLE_LINES)

    /** Currently-being-built line — accumulates chars until LF. */
    private val currentLineBuf: StringBuilder = StringBuilder(80)

    /** True if the last print attempt failed (out of paper / ink). */
    var lastError: PrintError? = null
        private set

    enum class PrintError { OUT_OF_PAPER, OUT_OF_INK, NO_PAPER_LOADED, NO_RIBBON_LOADED }

    /* ---------------- NBT ---------------- */

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        ContainerHelper.loadAllItems(tag, items, registries)
        recentLines.clear()
        if (tag.contains("recent_lines", Tag.TAG_LIST.toInt())) {
            val list = tag.getList("recent_lines", Tag.TAG_STRING.toInt())
            for (i in 0 until list.size) recentLines.addLast(list.getString(i))
        }
    }

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        ContainerHelper.saveAllItems(tag, items, registries)
        if (recentLines.isNotEmpty()) {
            val list = ListTag()
            for (line in recentLines) list.add(StringTag.valueOf(line))
            tag.put("recent_lines", list)
        }
    }

    /* ---------------- Container ---------------- */

    override fun getContainerSize(): Int = SLOT_COUNT
    override fun isEmpty(): Boolean = items.all { it.isEmpty }
    override fun getItem(slot: Int): ItemStack =
        if (slot in 0 until SLOT_COUNT) items[slot] else ItemStack.EMPTY

    override fun removeItem(slot: Int, amount: Int): ItemStack {
        val out = ContainerHelper.removeItem(items, slot, amount)
        if (!out.isEmpty) setChanged()
        return out
    }

    override fun removeItemNoUpdate(slot: Int): ItemStack = ContainerHelper.takeItem(items, slot)

    override fun setItem(slot: Int, stack: ItemStack) {
        if (slot in 0 until SLOT_COUNT) {
            items[slot] = stack
            if (stack.count > maxStackSize) stack.count = maxStackSize
            setChanged()
        }
    }

    override fun getMaxStackSize(): Int = 1

    override fun stillValid(player: Player): Boolean =
        !isRemoved && level != null && level!!.getBlockEntity(blockPos) === this &&
        player.distanceToSqr(blockPos.x + 0.5, blockPos.y + 0.5, blockPos.z + 0.5) <= 64.0

    override fun clearContent() {
        items.clear()
        recentLines.clear()
        currentLineBuf.setLength(0)
        setChanged()
    }

    override fun canPlaceItem(slot: Int, stack: ItemStack): Boolean = when (slot) {
        SLOT_PAPER  -> stack.item === ScevRegistry.PAPER_ROLL.get()
        SLOT_RIBBON -> stack.item === ScevRegistry.RIBBON.get()
        else -> false
    }

    /* ---------------- Print API ---------------- */

    /**
     * Print a chunk of text. Each character decrements ribbon ink;
     * every LF (or end-of-buffer flush) decrements one paper line.
     * Returns true if the entire chunk printed successfully, false if
     * we ran out of paper / ribbon partway through (lastError is set).
     *
     * BEL (0x07) plays a sound stub — wired to a vanilla pling for
     * now. CR + LF both terminate the current line; CR alone is
     * treated as a no-op (the real ASR-33 returned the carriage but
     * didn't advance the line; we model the simpler "always combined
     * with LF" convention).
     */
    fun printText(text: String): Boolean {
        val paper = items[SLOT_PAPER]
        val ribbon = items[SLOT_RIBBON]
        if (paper.isEmpty) { lastError = PrintError.NO_PAPER_LOADED; return false }
        if (ribbon.isEmpty) { lastError = PrintError.NO_RIBBON_LOADED; return false }

        for (ch in text) {
            when (ch) {
                '\n' -> {
                    if (!commitLine()) return false
                }
                '\r' -> { /* swallow */ }
                else -> {
                    // Decrement ribbon ink. If empty, the line still
                    // advances mechanically — just in invisible ink.
                    val inkLeft = ribbon.getOrDefault(
                        ScevDataComponents.RIBBON_INK_REMAINING.get(),
                        ScevDataComponents.RIBBON_INITIAL_INK)
                    if (inkLeft > 0) {
                        ribbon.set(ScevDataComponents.RIBBON_INK_REMAINING.get(), inkLeft - 1)
                    }
                    currentLineBuf.append(ch)
                    if (currentLineBuf.length >= MAX_LINE_CHARS) {
                        if (!commitLine()) return false
                    }
                }
            }
        }
        if (currentLineBuf.isNotEmpty()) commitLine()
        setChanged()
        lastError = null
        return true
    }

    /** Append the in-progress line to recentLines and decrement
     *  paper. Returns false on out-of-paper. */
    private fun commitLine(): Boolean {
        val paper = items[SLOT_PAPER]
        val linesLeft = paper.getOrDefault(
            ScevDataComponents.PAPER_LINES_REMAINING.get(),
            ScevDataComponents.PAPER_ROLL_INITIAL_LINES)
        if (linesLeft <= 0) {
            lastError = PrintError.OUT_OF_PAPER
            return false
        }
        paper.set(ScevDataComponents.PAPER_LINES_REMAINING.get(), linesLeft - 1)
        recentLines.addLast(currentLineBuf.toString())
        currentLineBuf.setLength(0)
        while (recentLines.size > MAX_VISIBLE_LINES) recentLines.removeFirst()
        setChanged()
        return true
    }

    /** Snapshot of currently-visible lines for the GUI. */
    fun visibleLines(): List<String> = recentLines.toList()

    companion object {
        const val SLOT_PAPER: Int = 0
        const val SLOT_RIBBON: Int = 1
        const val SLOT_COUNT: Int = 2

        /** How many lines fit in the GUI's "currently visible page" pane. */
        const val MAX_VISIBLE_LINES: Int = 12
        /** Hard wrap when a single line exceeds this many chars
         *  (matches ASR-33's 72 chars/line). */
        const val MAX_LINE_CHARS: Int = 72

        /** Hard cap on the byte queue — beyond this, kernel TX
         *  bytes are dropped on the floor (real teletype behavior
         *  when its buffer is full). */
        const val PRINT_QUEUE_CAP: Int = 4096

        /** Characters drained per server tick (20 TPS), mimicking
         *  the ASR-33's ~10 char/sec mechanical throughput. Bumped
         *  to 4 char/tick so 80-char lines print in ~1 sec — slow
         *  enough to feel teletype-y, fast enough to not be tedious. */
        const val CHARS_PER_TICK: Int = 4

        /** A short canned demo string for the "Print Test Page" button —
         *  exercises ribbon decrement + line wrap + paper decrement. */
        @JvmField
        val TEST_PAGE: String = """
            SCALAR EVOLUTION TELETYPE
            ─────────────────────────
            Loaded paper roll, ribbon
            inserted, ready to print.

            HELLO WORLD

            The quick brown fox jumps
            over the lazy dog 0123456789

            (end of test page)
        """.trimIndent()
    }
}
