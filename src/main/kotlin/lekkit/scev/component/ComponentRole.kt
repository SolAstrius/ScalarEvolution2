/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.component

/**
 * How a component is exposed to the guest.
 *
 * Most components are [PROPERTY_BAG] — a tree of files under
 * `/sys/scev/by-label/<name>/` that read/write values and route
 * actions. The other roles exist for shapes that don't fit the
 * file-as-value model:
 *
 * - [RPC] — a CUSE char device at `/dev/scev/<name>` with a
 *   request/response protocol. The CC-peripheral-exactly analog, for
 *   peripherals whose natural shape is "call these N methods by
 *   name."
 * - [CHAR_STREAM] — a bidirectional byte stream at
 *   `/dev/scev/<name>`. Modems, console peripherals, serial I/O.
 * - [MOUNT] — a filesystem mount at `/mnt/scev/<name>`, backed by
 *   a `ScevMount` implementation. Storage mods, config trees,
 *   multi-file resources.
 *
 * The role is declared at component level — a single component is
 * one shape. Plugins within a [PROPERTY_BAG] component may internally
 * expose action files, but that stays within the role.
 *
 * The enum is matched case-insensitively against the string form used
 * in the [lekkit.scev.component.api.ScevComponent] annotation, so
 * `@ScevComponent(name = "…", role = "property_bag")` and
 * `role = "PROPERTY_BAG"` both resolve to the same enum value.
 */
enum class ComponentRole {
    PROPERTY_BAG,
    RPC,
    CHAR_STREAM,
    MOUNT,
    ;

    companion object {
        /**
         * Parse the role string from the annotation. Case-insensitive.
         * Throws [IllegalArgumentException] with a helpful message on
         * unknown values (better than `valueOf`'s default).
         */
        @JvmStatic
        fun fromString(s: String): ComponentRole {
            return entries.firstOrNull { it.name.equals(s, ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "Unknown ComponentRole: '$s' — expected one of ${entries.joinToString { it.name.lowercase() }}",
                )
        }
    }
}
