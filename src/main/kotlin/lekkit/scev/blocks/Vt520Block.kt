/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.blocks

import lekkit.scev.blockentity.TerminalKind

/**
 * DEC VT520 (1993). The last DEC-branded terminal before the company
 * was dismantled. 8-bit C1 controls, ANSI conformance level 4, and
 * TEK 4014 emulation in the ROM. Charcoal case — about as close to
 * "looks like a modern monitor" as a real CRT terminal ever got.
 */
class Vt520Block(props: Properties) : TerminalVariantBlock(props, TerminalKind.VT520)
