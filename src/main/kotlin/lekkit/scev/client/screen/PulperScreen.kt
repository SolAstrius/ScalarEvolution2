/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.screen

import lekkit.scev.menu.PulperMenu
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

class PulperScreen(menu: PulperMenu, inv: Inventory, title: Component) :
    ProcessingMachineScreen<PulperMenu>(menu, inv, title)
