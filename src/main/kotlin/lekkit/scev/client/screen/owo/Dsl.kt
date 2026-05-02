/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * -----------------------------------------------------------------------
 *
 * Portions of this file are adapted from kowo-lib, MIT-licensed:
 *
 *   Copyright (c) 2024-2025 BonfireMC
 *   https://github.com/BonfireMC/kowo-lib
 *
 *   Permission is hereby granted, free of charge, to any person obtaining
 *   a copy of this software and associated documentation files (the
 *   "Software"), to deal in the Software without restriction, including
 *   without limitation the rights to use, copy, modify, merge, publish,
 *   distribute, sublicense, and/or sell copies of the Software, and to
 *   permit persons to whom the Software is furnished to do so, subject to
 *   the following conditions: the above copyright notice and this
 *   permission notice shall be included in all copies or substantial
 *   portions of the Software.
 *
 * Why vendored: the upstream kowo-lib jar is published as Yarn-mapped
 * bytecode (calls into `class_2561`, `class_5250`, …). Our build runs on
 * Mojmap mappings, so the resolved classpath has `Component` /
 * `MutableComponent` etc. and the published kowo jar fails to link.
 * Re-implementing the small DSL surface we actually use here (~50 LOC)
 * sidesteps yarn-mappings-patch entirely and removes the cross-version
 * coupling — kowo upstream targets MC 1.21.5 while we're on 1.21.1.
 *
 * Scope kept deliberately narrow: only the `+child`, `verticalFlow { … }`,
 * `100.fill` ergonomics that the screen ports need. Each new helper here
 * should pay for itself in real screen code.
 */
package lekkit.scev.client.screen.owo

import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.GridLayout
import io.wispforest.owo.ui.container.StackLayout
import io.wispforest.owo.ui.core.Component
import io.wispforest.owo.ui.core.ParentComponent
import io.wispforest.owo.ui.core.Sizing
import net.minecraft.network.chat.Component as McComponent
import net.minecraft.network.chat.MutableComponent

/* ---------------- text helpers ---------------- */

/** Mirrors kowo's `String.literal` — produce a [MutableComponent] from a literal string. */
inline val String.literal: MutableComponent get() = McComponent.literal(this)

/** Mirrors kowo's `String.translatable` — produce a translatable [MutableComponent]. */
inline val String.translatable: MutableComponent get() = McComponent.translatable(this)

/* ---------------- sizing helpers ----------------
 *
 * Kotlin lets us put receiver-style names on Int so `100.fill` reads
 * naturally as "fill 100% of parent" inside the DSL. Same shape as
 * kowo-lib's `getFill(int)` / `getContent(int)` / etc.
 */

/** `Sizing.fixed(this)` — exact pixel size. */
inline val Int.fixed: Sizing get() = Sizing.fixed(this)

/** `Sizing.fill(this)` — % of parent. */
inline val Int.fill: Sizing get() = Sizing.fill(this)

/** `Sizing.content()` — fit children + this much padding. */
inline val Int.content: Sizing get() = Sizing.content(this)

/** `Sizing.expand()` — flex-grow style; share remaining space with weight. */
inline val Int.expand: Sizing get() = Sizing.expand(this)

/* ---------------- container builders ----------------
 *
 * Thin wrappers over `Containers.X` so the DSL reads as
 * `verticalFlow(100.fill, content) { … }` instead of
 * `Containers.verticalFlow(...).also { … }`.
 */

inline fun verticalFlow(width: Sizing, height: Sizing): FlowLayout =
    io.wispforest.owo.ui.container.Containers.verticalFlow(width, height)

inline fun horizontalFlow(width: Sizing, height: Sizing): FlowLayout =
    io.wispforest.owo.ui.container.Containers.horizontalFlow(width, height)

inline fun stack(width: Sizing, height: Sizing): StackLayout =
    io.wispforest.owo.ui.container.Containers.stack(width, height)

inline fun grid(width: Sizing, height: Sizing, rows: Int, columns: Int): GridLayout =
    io.wispforest.owo.ui.container.Containers.grid(width, height, rows, columns)

/* ---------------- child appending ----------------
 *
 * `+component` inside a ParentComponent's apply / context appends. Same
 * shape as kowo's `unaryPlus` extension on Component, except we use a
 * context receiver instead of `context(parent: …)` because Kotlin 2.1
 * stable doesn't support context parameters yet (the DSL already requires
 * `-Xcontext-parameters` — see build.gradle).
 *
 * The dispatch fallback for FlowLayout / StackLayout is a single call;
 * GridLayout handling is omitted until a screen actually uses it.
 */

context(parent: ParentComponent)
operator fun <C : Component> C.unaryPlus(): C {
    when (parent) {
        is FlowLayout -> parent.child(this)
        is StackLayout -> parent.child(this)
        else -> error("unaryPlus: ${parent::class.simpleName} container has no 1-arg child(); " +
                "call .child(component, row, col) directly for grid layouts")
    }
    return this
}
