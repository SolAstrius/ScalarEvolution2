/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.compat.jade

import lekkit.scev.blockentity.CRTBlockEntity
import lekkit.scev.blockentity.ComputerCaseBlockEntity
import lekkit.scev.blockentity.FlashProgrammerBlockEntity
import lekkit.scev.blockentity.KeyboardBlockEntity
import lekkit.scev.blockentity.McuBoardBlockEntity
import lekkit.scev.blockentity.InkMixerBlockEntity
import lekkit.scev.blockentity.RibbonImpregnatorBlockEntity
import lekkit.scev.blocks.DirectionalBlock
import lekkit.scev.blocks.FlashProgrammerBlock
import lekkit.scev.blocks.KeyboardBlock
import lekkit.scev.blocks.McuBoardBlock
import lekkit.scev.blocks.InkMixerBlock
import lekkit.scev.blocks.RibbonImpregnatorBlock
import snownee.jade.api.IWailaClientRegistration
import snownee.jade.api.IWailaCommonRegistration
import snownee.jade.api.IWailaPlugin
import snownee.jade.api.WailaPlugin

/**
 * Root Jade plugin for Scalar Evolution. Registers per-block-entity
 * tooltip + server-data providers for our three player-facing block
 * kinds:
 *
 * - [ComputerCaseBlockEntity] — workstations, tinkerpads, laptops
 *   (tier ≤ 3 motherboard-based machines).
 * - [McuBoardBlockEntity] — microcontroller boards (SoC + flash).
 *
 * The `@WailaPlugin` annotation has NeoForge-specific behavior: if the
 * Jade mod isn't installed, Jade's scanner never runs and this class
 * isn't loaded — no ClassNotFound chain. The empty annotation value
 * means "plugin ships with the same mod as its providers", i.e. scev
 * itself.
 */
@WailaPlugin
class ScevJadePlugin : IWailaPlugin {

    override fun register(r: IWailaCommonRegistration) {
        // Server-side data collection. Run for any ScevBlockEntity subclass
        // we care about — Jade dispatches by the registered class, walking
        // up the hierarchy so a provider registered on a superclass fires
        // for every subclass BE.
        r.registerBlockDataProvider(ComputerCaseProvider.INSTANCE,    ComputerCaseBlockEntity::class.java)
        r.registerBlockDataProvider(McuBoardProvider.INSTANCE,        McuBoardBlockEntity::class.java)
        r.registerBlockDataProvider(CrtMonitorProvider.INSTANCE,      CRTBlockEntity::class.java)
        r.registerBlockDataProvider(KeyboardProvider.INSTANCE,        KeyboardBlockEntity::class.java)
        r.registerBlockDataProvider(FlashProgrammerProvider.INSTANCE, FlashProgrammerBlockEntity::class.java)
        // Same provider serves every ProcessingMachineBlockEntity
        // subclass — it inspects fields on the base class so each
        // concrete machine gets identical tooltip behavior.
        r.registerBlockDataProvider(ProcessingMachineProvider.INSTANCE, InkMixerBlockEntity::class.java)
        r.registerBlockDataProvider(ProcessingMachineProvider.INSTANCE, RibbonImpregnatorBlockEntity::class.java)
    }

    override fun registerClient(r: IWailaClientRegistration) {
        // Client-side tooltip rendering. Registered on the Block class (not
        // the BE class) because Jade's client dispatch keys off BlockState.
        // We use a catch-all on our common DirectionalBlock superclass and
        // discriminate by BE type inside each provider — simpler than
        // enumerating every block (Workstation, McuBoard, VT100, CRT, ...).
        r.registerBlockComponent(ComputerCaseProvider.INSTANCE,    DirectionalBlock::class.java)
        r.registerBlockComponent(McuBoardProvider.INSTANCE,        McuBoardBlock::class.java)
        r.registerBlockComponent(CrtMonitorProvider.INSTANCE,      DirectionalBlock::class.java)
        r.registerBlockComponent(KeyboardProvider.INSTANCE,        KeyboardBlock::class.java)
        r.registerBlockComponent(FlashProgrammerProvider.INSTANCE, FlashProgrammerBlock::class.java)
        r.registerBlockComponent(ProcessingMachineProvider.INSTANCE, InkMixerBlock::class.java)
        r.registerBlockComponent(ProcessingMachineProvider.INSTANCE, RibbonImpregnatorBlock::class.java)
    }
}
