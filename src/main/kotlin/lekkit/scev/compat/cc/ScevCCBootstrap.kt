/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.compat.cc

import com.mojang.logging.LogUtils
import net.neoforged.fml.ModList

/**
 * Classload gatekeeper for the CC: Tweaked integration.
 *
 * The only job of this class is to check whether CC is present before
 * touching any CC-typed symbol. Everything in its sibling classes
 * (`ScevCCCompat`, `ScevCCComputer`, `ScevCCHandlers`, …) imports
 * `dan200.computercraft.*` directly — keeping those imports out of
 * this file ensures that on a server without CC installed the JVM
 * never tries to resolve them.
 *
 * Invoked from `ScalarEvolution` once, at mod construction.
 */
object ScevCCBootstrap {
    private val LOG = LogUtils.getLogger()

    /**
     * Install the CC integration if the CC: Tweaked mod is present.
     * Safe to call unconditionally — no-op when CC isn't loaded.
     */
    @JvmStatic
    fun registerIfPresent() {
        if (!ModList.get().isLoaded("computercraft")) {
            LOG.info("[scev-cc] CC: Tweaked not installed — skipping integration")
            return
        }
        // Safe to reach into CC-typed code now.
        ScevCCCompat.register()
    }
}
