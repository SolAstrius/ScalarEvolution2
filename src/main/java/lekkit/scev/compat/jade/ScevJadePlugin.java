/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.compat.jade;

import lekkit.scev.blockentity.ComputerCaseBlockEntity;
import lekkit.scev.blockentity.McuBoardBlockEntity;
import lekkit.scev.blockentity.VT100BlockEntity;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

/**
 * Root Jade plugin for Scalar Evolution. Registers per-block-entity tooltip +
 * server-data providers for our three playerese-facing block kinds:
 *
 * <ul>
 *   <li>{@link ComputerCaseBlockEntity} — workstations, tinkerpads, laptops
 *       (tier ≤ 3 motherboard-based machines).</li>
 *   <li>{@link McuBoardBlockEntity} — microcontroller boards (SoC + flash).</li>
 *   <li>{@link VT100BlockEntity} — standalone terminals, to surface which
 *       machine they auto-linked to.</li>
 * </ul>
 *
 * <p>The {@code @WailaPlugin} annotation has NeoForge-specific behavior: if
 * the Jade mod isn't installed, Jade's scanner never runs and this class
 * isn't loaded — no ClassNotFound chain. The empty annotation value means
 * "plugin ships with the same mod as its providers", i.e. scev itself.
 */
@WailaPlugin
public class ScevJadePlugin implements IWailaPlugin {

    @Override
    public void register(IWailaCommonRegistration r) {
        // Server-side data collection. Run for any ScevBlockEntity subclass
        // we care about — Jade dispatches by the registered class, walking
        // up the hierarchy so a provider registered on a superclass fires
        // for every subclass BE.
        r.registerBlockDataProvider(ComputerCaseProvider.INSTANCE, ComputerCaseBlockEntity.class);
        r.registerBlockDataProvider(McuBoardProvider.INSTANCE, McuBoardBlockEntity.class);
        r.registerBlockDataProvider(Vt100Provider.INSTANCE, VT100BlockEntity.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration r) {
        // Client-side tooltip rendering. Registered on the Block class (not
        // the BE class) because Jade's client dispatch keys off BlockState.
        // We use a catch-all on our common DirectionalBlock superclass and
        // discriminate by BE type inside each provider — simpler than
        // enumerating every block (Workstation, McuBoard, VT100, CRT, ...).
        r.registerBlockComponent(ComputerCaseProvider.INSTANCE, lekkit.scev.blocks.DirectionalBlock.class);
        r.registerBlockComponent(McuBoardProvider.INSTANCE, lekkit.scev.blocks.McuBoardBlock.class);
        r.registerBlockComponent(Vt100Provider.INSTANCE, lekkit.scev.blocks.DirectionalBlock.class);
    }
}
