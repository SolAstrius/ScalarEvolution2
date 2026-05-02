/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.component.prebuilt

import lekkit.scev.component.describe.ParamSpec
import lekkit.scev.component.describe.ReturnShape
import lekkit.scev.component.dsl.ComponentBuilder
import lekkit.scev.component.dsl.PluginBuilder

/**
 * Prebuilt plugin contributions for common patterns.
 *
 * Extension functions on [ComponentBuilder] so mod authors can write:
 * ```
 * scevComponent("my_generator") {
 *     fromEnergyStorage("energy", ::getStored, ::getCapacity, ::canReceive, ::canExtract)
 *     fromItemSlots("items", access = ::slotAccess)
 *     fromFluidTanks("fluid", access = ::tankAccess)
 * }
 * ```
 *
 * Each helper lays out the file tree matching the
 * {@code /sys/scev/by-capability/<cap>/} contract we documented — one
 * shape per capability family, so a shell that knows how to read one
 * EnergyStorage-backed component can read all of them.
 *
 * These deliberately do NOT import any NeoForge types — scev's
 * component API is self-contained, and callers bring their own
 * capability wiring (a function reference pointing at the capability
 * handle). That keeps this package free of a NeoForge hard dep and
 * usable from pure JVM tests.
 */

/**
 * Generate the `energy/` subtree from simple accessors.
 *
 * Files:
 * - `stored` — scalar, FE
 * - `capacity` — scalar, FE
 * - `can_receive` — boolean
 * - `can_extract` — boolean
 *
 * The [suitable] gate should return true iff the underlying
 * capability is currently available (typical NeoForge check).
 */
fun ComponentBuilder.fromEnergyStorage(
    slug: String = "energy",
    capability: String = "neoforge:energy_storage",
    suitable: () -> Boolean = { true },
    stored: () -> Long,
    capacityValue: () -> Long,
    canReceive: () -> Boolean = { true },
    canExtract: () -> Boolean = { true },
) {
    plugin(slug, capability = capability, suitable = suitable) {
        readOnly("stored", unit = "FE", luaType = "number") { stored() }
        readOnly("capacity", unit = "FE", luaType = "number") { capacityValue() }
        readOnly("can_receive", luaType = "boolean") { canReceive() }
        readOnly("can_extract", luaType = "boolean") { canExtract() }
    }
}

/**
 * Read-only slot enumeration. Prefer [fromInventory] for inventories
 * that support writes — this helper is for display-only cases
 * (remote views of an inventory a different peer owns, read-only
 * proxies, test doubles).
 *
 * Files per slot:
 * - `slots/<n>/item` — resource id string
 * - `slots/<n>/count` — number
 * - `slots/<n>/components` — JSON data components (when non-empty)
 *
 * Plus `slots/count` — total slot count.
 *
 * Slot counts that change after attach aren't handled yet — that's a
 * runtime concern for hot-plug; for now the slot count is frozen at
 * DSL-build time.
 */
fun ComponentBuilder.fromItemSlots(
    slug: String = "items",
    capability: String = "neoforge:item_handler",
    suitable: () -> Boolean = { true },
    access: () -> SlotAccess,
) {
    plugin(slug, capability = capability, suitable = suitable) {
        readOnly("slots/count", luaType = "number") { access().size }
        val size = access().size
        for (i in 0 until size) {
            val base = "slots/$i"
            readOnly("$base/item", luaType = "string") { access().snapshot(i).item }
            readOnly("$base/count", luaType = "number") { access().snapshot(i).count }
            readOnly("$base/components", luaType = "string") { access().snapshot(i).componentsJson }
        }
    }
}

/**
 * Inventory plugin — the read/write filesystem-shaped version of
 * [fromItemSlots].
 *
 * Exposes a plugin subdirectory whose shape matches the inventory
 * filesystem model the runtime will implement for MOUNT-role
 * components:
 *
 * ```
 * <slug>/
 * ├── size                        ro    total slots
 * ├── kind                        ro    "chest" / "hopper" / ...
 * ├── empty_slots                 ro    derived
 * ├── slots/
 * │   ├── count                   ro
 * │   └── <n>/
 * │       ├── item                ro
 * │       ├── count               ro
 * │       ├── max_count           ro
 * │       ├── components          ro
 * │       └── kind                ro    slot's functional role
 * ├── move                        wo    "from to [count]"
 * ├── move_result                 ro
 * ├── swap                        wo    "a b"
 * ├── swap_result                 ro
 * ├── push                        wo    cross-inventory, runtime-routed
 * └── pull                        wo    cross-inventory, runtime-routed
 * ```
 *
 * `mv` on the filesystem — when the runtime implements it — routes
 * through [InventoryAccess.move] with `count = -1`. The `move` /
 * `swap` action files above are the explicit/partial escape hatch
 * for shells and scripts that want typed return values or partial
 * counts.
 *
 * `push` / `pull` require a runtime peer resolver (not yet present);
 * they're declared here so mod authors bind against stable action
 * paths, and the action throws `ENOSYS` until the runtime lands.
 */
fun ComponentBuilder.fromInventory(
    slug: String = "items",
    capability: String = "neoforge:item_handler",
    suitable: () -> Boolean = { true },
    access: () -> InventoryAccess,
) {
    plugin(slug, capability = capability, suitable = suitable) {
        // ---- reads ----
        readOnly("size", luaType = "number") { access().size }
        readOnly("kind", luaType = "string") { access().kind }
        readOnly("empty_slots", luaType = "number") {
            val a = access()
            (0 until a.size).count { a.snapshot(it).count == 0 }
        }

        val size = access().size
        readOnly("slots/count", luaType = "number") { size }
        for (i in 0 until size) {
            val base = "slots/$i"
            readOnly("$base/item", luaType = "string") { access().snapshot(i).item }
            readOnly("$base/count", luaType = "number") { access().snapshot(i).count }
            readOnly("$base/max_count", luaType = "number") { access().maxSlotSize(i) }
            readOnly("$base/components", luaType = "string") { access().snapshot(i).componentsJson }
            readOnly("$base/kind", luaType = "string") { access().slotKind(i).fsLabel }
        }

        // ---- writes ----
        //
        // The action files accept either two or three numbers
        // (from, to, [count]). Count defaults to -1 (whole stack),
        // matching the filesystem rename(2) semantics.
        action(
            "move",
            params = listOf(
                ParamSpec("number"),
                ParamSpec("number"),
                ParamSpec("number", optional = true),
            ),
            returnShape = ReturnShape.ONE,
        ) { args ->
            val from = (args.getOrNull(0) as? Number)?.toInt()
                ?: throw lekkit.scev.component.api.InvalidArgumentException(
                    "move: first arg must be source slot (number)",
                )
            val to = (args.getOrNull(1) as? Number)?.toInt()
                ?: throw lekkit.scev.component.api.InvalidArgumentException(
                    "move: second arg must be target slot (number)",
                )
            val count = (args.getOrNull(2) as? Number)?.toInt() ?: -1
            resultToReturn(access().move(from, to, count))
        }

        action(
            "swap",
            params = listOf(ParamSpec("number"), ParamSpec("number")),
            returnShape = ReturnShape.ONE,
        ) { args ->
            val a = (args.getOrNull(0) as? Number)?.toInt()
                ?: throw lekkit.scev.component.api.InvalidArgumentException("swap: first arg must be number")
            val b = (args.getOrNull(1) as? Number)?.toInt()
                ?: throw lekkit.scev.component.api.InvalidArgumentException("swap: second arg must be number")
            resultToReturn(access().swap(a, b))
        }

        action(
            "accepts",
            params = listOf(
                ParamSpec("number"),
                ParamSpec("string"),
                ParamSpec("string", optional = true),
            ),
            returnShape = ReturnShape.ONE,
        ) { args ->
            val slot = (args.getOrNull(0) as? Number)?.toInt()
                ?: throw lekkit.scev.component.api.InvalidArgumentException("accepts: first arg must be number")
            val item = args.getOrNull(1) as? String
                ?: throw lekkit.scev.component.api.InvalidArgumentException("accepts: second arg must be string (item id)")
            val comps = args.getOrNull(2) as? String ?: ""
            access().accepts(slot, item, comps)
        }

        // Cross-peripheral stubs — runtime fills in later.
        action(
            "push",
            params = listOf(
                ParamSpec("string"),
                ParamSpec("number"),
                ParamSpec("number"),
                ParamSpec("number", optional = true),
            ),
        ) { _ ->
            throw lekkit.scev.component.api.PeripheralException(
                lekkit.scev.component.api.Errno.ENOSYS,
                "cross-peripheral push not yet implemented — awaiting runtime peer resolver",
            )
        }
        action(
            "pull",
            params = listOf(
                ParamSpec("string"),
                ParamSpec("number"),
                ParamSpec("number"),
                ParamSpec("number", optional = true),
            ),
        ) { _ ->
            throw lekkit.scev.component.api.PeripheralException(
                lekkit.scev.component.api.Errno.ENOSYS,
                "cross-peripheral pull not yet implemented — awaiting runtime peer resolver",
            )
        }

        // Event channel for slot changes.
        event(
            "slot_changed",
            paramShape = listOf(
                ParamSpec("number"),    // slot
                ParamSpec("string"),    // before.item
                ParamSpec("number"),    // before.count
                ParamSpec("string"),    // after.item
                ParamSpec("number"),    // after.count
            ),
        )
    }
}

/** Map a [TransferResult] onto an action return. */
private fun resultToReturn(result: TransferResult): Any = when (result) {
    is TransferResult.Success -> true
    is TransferResult.Partial -> result.moved
    is TransferResult.Refused -> throw lekkit.scev.component.api.PeripheralException(
        result.errno,
        "transfer refused: ${result.reason}",
    )
}

/**
 * Generate the `fluid/` subtree from a [TankAccess].
 *
 * Files per tank:
 * - `tanks/<n>/fluid` — resource id string
 * - `tanks/<n>/amount` — number, mB
 * - `tanks/<n>/capacity` — number, mB
 *
 * Plus:
 * - `tanks/count` — number
 */
fun ComponentBuilder.fromFluidTanks(
    slug: String = "fluid",
    capability: String = "neoforge:fluid_handler",
    suitable: () -> Boolean = { true },
    access: () -> TankAccess,
) {
    plugin(slug, capability = capability, suitable = suitable) {
        readOnly("tanks/count", luaType = "number") { access().size }
        val size = access().size
        for (i in 0 until size) {
            val base = "tanks/$i"
            readOnly("$base/fluid", luaType = "string") { access().snapshot(i).fluid }
            readOnly("$base/amount", unit = "mB", luaType = "number") { access().snapshot(i).amount }
            readOnly("$base/capacity", unit = "mB", luaType = "number") { access().snapshot(i).capacity }
        }
    }
}

/**
 * Generate the `terminal/` subtree from a [TerminalAccess].
 *
 * Files:
 * - `terminal/width` — number
 * - `terminal/height` — number
 * - `terminal/cursor_x` / `terminal/cursor_y` — number
 * - `terminal/lines/<n>` — one row's text
 *
 * Writes — turning this read-only view into an interactive buffer —
 * need additional actions layered on top (e.g. a `setCursorPos`
 * action) which belong to the specific peripheral's code, not a
 * prebuilt.
 */
fun ComponentBuilder.fromTerminal(
    slug: String = "vt100",
    capability: String? = null,
    suitable: () -> Boolean = { true },
    access: () -> TerminalAccess,
) {
    plugin(slug, capability = capability, suitable = suitable) {
        readOnly("width", luaType = "number") { access().width }
        readOnly("height", luaType = "number") { access().height }
        readOnly("cursor_x", luaType = "number") { access().cursorX }
        readOnly("cursor_y", luaType = "number") { access().cursorY }
        val height = access().height
        for (row in 0 until height) {
            readOnly("lines/$row", luaType = "string") { access().line(row) }
        }
    }
}

/**
 * Redstone I/O surface.
 *
 * `read` returns the current input signal strength (0..15). `write`
 * sets the output signal strength at this side. Scev's typical usage
 * is one "redstone" plugin per redstone-interacting component, but
 * authors can also attach this to any component that happens to have
 * a redstone aspect.
 */
fun ComponentBuilder.fromRedstoneSide(
    slug: String = "redstone",
    suitable: () -> Boolean = { true },
    readInput: () -> Int,
    readOutput: () -> Int,
    writeOutput: (Int) -> Unit,
) {
    plugin(slug, capability = "scev:redstone", suitable = suitable) {
        readOnly("input", luaType = "number", min = 0.0, max = 15.0) { readInput() }
        readWrite(
            "output",
            luaType = "number", min = 0.0, max = 15.0,
            getter = { readOutput() },
            setter = { v ->
                val i = (v as? Number)?.toInt()
                    ?: throw lekkit.scev.component.api.InvalidArgumentException("redstone output must be 0..15, got $v")
                if (i !in 0..15) {
                    throw lekkit.scev.component.api.OutOfRangeException("redstone output must be 0..15, got $i")
                }
                writeOutput(i)
            },
        )
    }
}

/**
 * A simple multi-line document — matches the Railcraft routing
 * detector shape. [pageSeparator] is what delimits pages when you
 * read the whole document as one string; default matches the "blank
 * line" convention.
 *
 * Files:
 * - `table` (or custom [slug]) — rw text document
 * - `table_title` — rw string
 * - `table_present` / `table_locked` — ro booleans
 */
fun ComponentBuilder.documentWithTitle(
    slug: String = "table",
    capability: String? = null,
    suitable: () -> Boolean = { true },
    @Suppress("UNUSED_PARAMETER") pageSeparator: String = "\n\n",
    getText: () -> String,
    setText: (String) -> Unit,
    getTitle: () -> String,
    setTitle: (String) -> Unit,
    isPresent: () -> Boolean = { true },
    isLocked: () -> Boolean = { false },
) {
    // We lay these out at the component root (not a plugin) because
    // they ARE the component for peripherals like the Routing
    // Detector — not one facet among many.
    readOnly("${slug}_present", luaType = "boolean") { isPresent() }
    readOnly("${slug}_locked", luaType = "boolean") { isLocked() }
    readWrite(
        slug,
        luaType = "string",
        getter = { getText() },
        setter = { v ->
            val s = v as? String ?: throw lekkit.scev.component.api.InvalidArgumentException(
                "$slug must be a string, got ${v?.javaClass?.simpleName ?: "null"}",
            )
            setText(s)
        },
    )
    readWrite(
        "${slug}_title",
        luaType = "string",
        getter = { getTitle() },
        setter = { v ->
            val s = v as? String ?: throw lekkit.scev.component.api.InvalidArgumentException(
                "${slug}_title must be a string",
            )
            setTitle(s)
        },
    )
    @Suppress("UNUSED_VARIABLE")
    val unused_capability = capability // reserved for when we emit by-capability/ symlinks
}

/**
 * Shape a scalar sensor with a writable threshold pair (common for
 * Energy Detector / Player Detector style peripherals).
 *
 * - `<slug>/value` — current reading (ro)
 * - `<slug>/threshold_low` — rw
 * - `<slug>/threshold_high` — rw
 */
fun ComponentBuilder.sensorWithThresholds(
    slug: String,
    unit: String? = null,
    capability: String? = null,
    suitable: () -> Boolean = { true },
    read: () -> Number,
    readLow: () -> Number,
    writeLow: (Double) -> Unit,
    readHigh: () -> Number,
    writeHigh: (Double) -> Unit,
) {
    plugin(slug, capability = capability, suitable = suitable) {
        readOnly("value", unit = unit, luaType = "number") { read() }
        readWrite(
            "threshold_low", unit = unit, luaType = "number",
            getter = { readLow() },
            setter = { v -> writeLow(coerceDouble(v, "$slug/threshold_low")) },
        )
        readWrite(
            "threshold_high", unit = unit, luaType = "number",
            getter = { readHigh() },
            setter = { v -> writeHigh(coerceDouble(v, "$slug/threshold_high")) },
        )
    }
}

/**
 * Send-style action: takes exactly one string-like arg, returns a
 * single result. Fits Chat Box `sendMessage`, Speaker `say`, etc.
 */
fun PluginBuilder.messageAction(
    path: String,
    doc: String? = null,
    handler: (String) -> Any?,
) {
    action(
        path = path,
        doc = doc,
        params = listOf(ParamSpec(luaType = "string")),
        returnShape = ReturnShape.ONE,
        handler = { args ->
            val msg = args.getOrNull(0) as? String
                ?: throw lekkit.scev.component.api.InvalidArgumentException(
                    "$path expects one string argument",
                )
            handler(msg)
        },
    )
}

/** Internal: Number|String → Double coercion. */
private fun coerceDouble(v: Any?, path: String): Double {
    return when (v) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
            ?: throw lekkit.scev.component.api.InvalidArgumentException(
                "$path must be numeric, got \"$v\"",
            )
        else -> throw lekkit.scev.component.api.InvalidArgumentException(
            "$path must be numeric, got ${v?.javaClass?.simpleName ?: "null"}",
        )
    }
}
