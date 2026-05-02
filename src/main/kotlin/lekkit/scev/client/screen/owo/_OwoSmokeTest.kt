/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.screen.owo

import io.wispforest.owo.ui.component.Components
import io.wispforest.owo.ui.container.FlowLayout

/**
 * Build-time smoke check that the owo-lib dependency resolves, the
 * Mojmap-translated owo classes are visible from Kotlin, and our small
 * in-tree DSL ([Dsl.kt]) wires up correctly. Deleted once the first
 * real port lands; until then this is the canary for "did the dep
 * wiring break."
 */
@Suppress("unused")
internal object OwoSmokeTest {
    fun build(): FlowLayout = verticalFlow(100.fill, 100.fill).apply {
        // ButtonComponent extends ButtonWidget extends AbstractWidget. The
        // owo-lib interface-injection metadata (gradle/owo-interfaces.json)
        // makes AbstractWidget implement owo's Component supertype at compile
        // time — without that, this `+button(…)` wouldn't typecheck.
        +Components.label("hi from owo".literal)
        +Components.button("smoke".literal) { /* no-op */ }
    }
}
