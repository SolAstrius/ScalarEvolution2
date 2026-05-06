/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server

import java.util.UUID
import lekkit.scev.items.MotherboardItem

/**
 * [IMachineHandle] backed by a UUID alone — used by handheld computer
 * items (phones, tablets, Game-Boy-style consoles). Power/reset go
 * straight to [MachineManager]; the actual VM lifetime is owned by
 * [HandheldTickHost], not this handle.
 *
 * Constructed on both client and server when a handheld's
 * [lekkit.scev.menu.MachineMenu] opens. Doesn't hold a stack reference
 * — the menu only needs the UUID to look up the framebuffer cache and
 * to validate the open menu, and a stack reference would risk going
 * stale across slot moves anyway.
 *
 * **Power semantics for handhelds.** Real power: `powerOff` destroys
 * the running VM (losing RAM) and asks [HandheldTickHost] to *not*
 * rebuild on subsequent ticks; `powerOn` clears that flag so the next
 * tick rebuilds the VM from the stack components + disk image. The
 * menu stays open across the cycle ([isValid] always returns true) so
 * the player can power back on without re-opening the GUI. Suspend
 * (preserve RAM across cycles) is not implemented — would need a
 * separate flag and would not destroy the [MachineState].
 */
class ItemStackMachineHandle(private val uuid: UUID) : IMachineHandle {

    override fun getMachineUUID(): UUID = uuid

    /**
     * Always valid for the lifetime of the menu — the menu must survive
     * power cycles. The actual VM may be absent (powered off or between
     * grace-destroy and re-equip); render code paints a black screen in
     * that case. Player closing the inventory / opening another GUI / etc.
     * tears down the menu through the usual vanilla paths regardless.
     */
    override fun isValid(): Boolean = true

    /**
     * Real power-on: clear the user-powered-off flag in the tick host so
     * the next server tick will (re)build the VM from the stack components
     * + disk image. RAM is fresh; this is destroy/recreate, not suspend.
     */
    override fun powerOn() {
        HandheldTickHost.markPoweredOn(uuid)
    }

    /**
     * Real power-off: destroy the VM (RAM is lost — disk image survives,
     * see GC) and tell the tick host to skip rebuild on subsequent ticks
     * until [powerOn] flips the flag back. Streamer is closed inside
     * [HandheldTickHost.markPoweredOff] so the native encoder is freed.
     */
    override fun powerOff() {
        HandheldTickHost.markPoweredOff(uuid)
        MachineManager.destroyMachineState(uuid)
    }

    override fun power() {
        if (isPowered()) powerOff() else powerOn()
    }

    override fun reset() {
        MachineManager.getMachineState(uuid)?.reset()
    }

    override fun isPowered(): Boolean =
        MachineManager.getMachineState(uuid)?.isPowered ?: false

    /** Handhelds don't expose case slots through this handle. */
    override fun getCaseSlotCount(): Int = 0
    override fun getMaxMotherboardLevel(): Int = 0

    /**
     * Motherboard lookup goes through the stack; this handle has no stack
     * reference, so nothing to return. The VM was already built from the
     * stack's motherboard data — readers that need the live components
     * should walk MachineState/MachineSpec instead.
     */
    override fun getMotherboardItem(): MotherboardItem? = null
}
