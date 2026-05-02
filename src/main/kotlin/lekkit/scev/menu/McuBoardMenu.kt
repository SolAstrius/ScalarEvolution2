/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.menu

import java.util.Locale
import java.util.function.Predicate
import lekkit.scev.blockentity.McuBoardBlockEntity
import lekkit.scev.items.FlashItem
import lekkit.scev.items.SocItem
import lekkit.scev.main.ScevRegistry
import lekkit.scev.server.MachineManager
import lekkit.scev.server.NativeLoader
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.world.Container
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.DataSlot
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

/**
 * Single MCU menu — combines component install + readout + power
 * control.
 *
 * Layout coords follow the drone-style GUI reused at
 * `textures/gui/mcu_board.png`: black console panel in the top-left
 * (rendered-in-screen), two slots in the top-right for SoC and Flash
 * at (98, 8) and (116, 8), player inventory starting at (8, 66). The
 * `McuBoardScreen` paints live state text into the console panel and
 * GPIO LEDs onto the middle strip.
 *
 * Three [DataSlot]s carry server→client state each tick:
 * - [powerState] — 0/1 for off/on, drives the power button visual.
 * - [gpioIn]  — guest's INPUT register low 6 bits (block-relative).
 * - [gpioOut] — guest's OUTPUT register low 6 bits.
 */
class McuBoardMenu(
    id: Int,
    inv: Inventory,
    val mcu: McuBoardBlockEntity,
) : AbstractContainerMenu(ScevRegistry.MCU_BOARD_MENU.get(), id) {

    private val powerState: DataSlot = DataSlot.standalone()
    private val gpioIn: DataSlot = DataSlot.standalone()
    private val gpioOut: DataSlot = DataSlot.standalone()

    init {
        // Kind-gated component slots. Accept only the right item class, so
        // a player can't drop a CPU into the SoC slot by mistake.
        addSlot(KindGatedSlot(mcu, McuBoardBlockEntity.SLOT_SOC, SOC_SLOT_X, SOC_SLOT_Y) {
            s -> s.item is SocItem
        })
        addSlot(KindGatedSlot(mcu, McuBoardBlockEntity.SLOT_FLASH, FLASH_SLOT_X, FLASH_SLOT_Y) {
            s -> s.item is FlashItem
        })

        addPlayerInventory(inv)

        addDataSlot(powerState)
        addDataSlot(gpioIn)
        addDataSlot(gpioOut)
    }

    private fun addPlayerInventory(inv: Inventory) {
        for (row in 0 until 3) {
            for (col in 0 until 9) {
                addSlot(Slot(inv, col + row * 9 + 9,
                    PLAYER_INV_X + col * 18, PLAYER_INV_Y + row * 18))
            }
        }
        for (col in 0 until 9) {
            addSlot(Slot(inv, col, PLAYER_INV_X + col * 18, PLAYER_HOTBAR_Y))
        }
    }


    fun isMachinePowered(): Boolean = powerState.get() != 0
    fun getGpioInputMask(): Int = gpioIn.get() and 0x3F
    fun getGpioOutputMask(): Int = gpioOut.get() and 0x3F

    /**
     * Preflight for power-on. Each non-OK value maps to
     * `text.scev.power.fail.<name>` lang key for the on-screen error.
     */
    enum class ValidationResult {
        OK,
        NATIVE_NOT_LOADED,
        NO_SOC,
        NO_FLASH;

        fun langKey(): String = "text.scev.power.fail." + name.lowercase(Locale.ROOT)
    }

    fun validateForPower(): ValidationResult {
        if (!NativeLoader.isLoaded()) return ValidationResult.NATIVE_NOT_LOADED
        if (mcu.getItem(McuBoardBlockEntity.SLOT_SOC).item !is SocItem) return ValidationResult.NO_SOC
        if (mcu.getItem(McuBoardBlockEntity.SLOT_FLASH).item !is FlashItem) return ValidationResult.NO_FLASH
        return ValidationResult.OK
    }

    override fun broadcastChanges() {
        powerState.set(if (mcu.isPowered()) 1 else 0)

        // Pull live GPIO state from the running machine for the LED row.
        // When the VM isn't running we zero both — the LEDs dim out so the
        // player can tell the machine is halted at a glance.
        val ms = MachineManager.getMachineState(mcu.getMachineUUID())
        val gpio = ms?.gpio
        if (gpio == null) {
            gpioIn.set(0)
            gpioOut.set(0)
        } else {
            // The GPIO device exposes a single 6-bit view of current pin
            // state. Separating INPUT vs OUTPUT requires a bigger API; for
            // now both LED rows reflect the same mask (what the wire carries
            // at this instant). When we add GpioDevice.readInputs a follow-up
            // can split the two into distinct values.
            val state = gpio.readPins() and 0x3F
            gpioIn.set(state)
            gpioOut.set(state)
        }

        super.broadcastChanges()
    }

    override fun quickMoveStack(player: Player, index: Int): ItemStack {
        val mcuSlotCount = McuBoardBlockEntity.SLOT_COUNT
        val slot = slots[index]
        if (!slot.hasItem()) return ItemStack.EMPTY
        val stack = slot.item
        val original = stack.copy()
        if (index < mcuSlotCount) {
            // From MCU slot → player inventory.
            if (!moveItemStackTo(stack, mcuSlotCount, slots.size, true)) return ItemStack.EMPTY
        } else {
            // From player inventory → MCU slots. Kind-gated slots only accept
            // the matching item class; a non-SoC/non-Flash shift-click returns
            // empty so the stack stays in the player inventory.
            if (!moveItemStackTo(stack, 0, mcuSlotCount, false)) return ItemStack.EMPTY
        }
        if (stack.isEmpty) slot.set(ItemStack.EMPTY) else slot.setChanged()
        return original
    }

    override fun stillValid(player: Player): Boolean = mcu.stillValid(player)

    /** Slot that only accepts items passing a predicate. Max stack size 1. */
    private class KindGatedSlot(
        container: Container,
        slotIndex: Int,
        x: Int,
        y: Int,
        private val accept: Predicate<ItemStack>,
    ) : Slot(container, slotIndex, x, y) {
        override fun mayPlace(stack: ItemStack): Boolean = accept.test(stack)
        override fun getMaxStackSize(): Int = 1
    }

    companion object {
        /** SoC slot coords — top-right, first cell of the drone-style grid. */
        private const val SOC_SLOT_X = 98
        private const val SOC_SLOT_Y = 8
        /** Flash slot coords — 18-px to the right of SoC. */
        private const val FLASH_SLOT_X = 116
        private const val FLASH_SLOT_Y = 8

        /** Drone-style player inventory positions carried through from the PNG. */
        private const val PLAYER_INV_X = 8
        private const val PLAYER_INV_Y = 66
        private const val PLAYER_HOTBAR_Y = 124

        @JvmStatic
        fun fromNetwork(id: Int, inv: Inventory, buf: RegistryFriendlyByteBuf): McuBoardMenu {
            val pos = buf.readBlockPos()
            val be = inv.player.level().getBlockEntity(pos)
            if (be is McuBoardBlockEntity) return McuBoardMenu(id, inv, be)
            throw IllegalStateException("No McuBoardBlockEntity at $pos")
        }
    }
}
