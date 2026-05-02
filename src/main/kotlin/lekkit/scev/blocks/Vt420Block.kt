/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.blocks

import lekkit.scev.blockentity.TerminalKind

/**
 * DEC VT420 (1990). 132-column mode, multi-session, soft-reset
 * improvements; the workhorse of the early-90s VAX/VMS shop. No
 * sixel/ReGIS — DEC pushed graphics to the dedicated VTxxx-G line.
 * Industrial dark-gray case.
 */
class Vt420Block(props: Properties) : TerminalVariantBlock(props, TerminalKind.VT420)
