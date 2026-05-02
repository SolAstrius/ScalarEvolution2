/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.blocks

import lekkit.scev.blockentity.TerminalKind

/**
 * DEC VT100 (1978). The original 80×24 monochrome serial terminal —
 * cream / tan plastic, no color SGR, no graphics extensions. Mlterm
 * sees `term_type=vt100` and replies to DA accordingly.
 *
 * All terminal-block behavior lives in [TerminalVariantBlock]; this
 * class is just a thin "I'm the VT100 variant" tag so the registry
 * and the asset pipeline can address it by name.
 */
class Vt100Block(props: Properties) : TerminalVariantBlock(props, TerminalKind.VT100)
