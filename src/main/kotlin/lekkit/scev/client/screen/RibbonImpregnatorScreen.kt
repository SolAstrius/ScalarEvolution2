/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.screen

import lekkit.scev.menu.RibbonImpregnatorMenu
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

class RibbonImpregnatorScreen(menu: RibbonImpregnatorMenu, inv: Inventory, title: Component) :
    ProcessingMachineScreen<RibbonImpregnatorMenu>(menu, inv, title)
