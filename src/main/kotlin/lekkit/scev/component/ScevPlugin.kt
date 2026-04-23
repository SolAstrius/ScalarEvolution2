/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.component

/**
 * Marker interface for plugin classes attached via
 * [lekkit.scev.component.api.Plugin] annotations or the DSL
 * `plugin { ... }` builder.
 *
 * Plugins are independent bundles of `@Property` / `@Action` methods
 * surfaced as a subdirectory of their parent component. A single
 * component may have many plugins, typically one per capability
 * (energy, fluid, items, redstone, …).
 *
 * Implementing this interface is optional — the scanner does not
 * require it. It is useful for two things:
 *
 * 1. Declaring the [isSuitable] gate without an extra `@Suitable`
 *    annotation. Returning false makes the plugin's subdirectory
 *    absent from the tree at attach time. Hot-plug re-evaluation
 *    will be added when scev's runtime grows that hook.
 *
 * 2. Providing attach / detach lifecycle hooks. [onAttach] fires
 *    when scev first observes the plugin, [onDetach] when the
 *    enclosing component goes away. Default implementations are
 *    no-ops.
 *
 * For the dead API this interface has no runtime consumer — it only
 * supplies a shape for the scanner to honour.
 */
interface ScevPlugin {
    /**
     * Predicate gating whether this plugin's subtree is visible.
     * Evaluated once at attach time by the scanner; future hot-plug
     * support may re-evaluate.
     */
    fun isSuitable(): Boolean = true

    /** Called when scev attaches to the owning component. */
    fun onAttach() {}

    /** Called when the owning component is removed. */
    fun onDetach() {}
}
