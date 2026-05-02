/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.blocks

import lekkit.scev.blockentity.TerminalKind

/**
 * DEC VT340 (1987). Platinum-gray plastic, sixel + ReGIS graphics
 * planes, 16 colors. The peak DEC terminal — what every retrocomputing
 * fan actually wants on their desk. Same form factor; darker case.
 */
class Vt340Block(props: Properties) : TerminalVariantBlock(props, TerminalKind.VT340)
