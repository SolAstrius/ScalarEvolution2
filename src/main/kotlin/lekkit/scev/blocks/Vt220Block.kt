/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.blocks

import lekkit.scev.blockentity.TerminalKind

/**
 * DEC VT220 (1983). Cooler-gray plastic, soft fonts (DRCS), selective
 * erase, 8-color ANSI SGR. Same physical form factor as the VT100;
 * the model + texture differ only in case color.
 */
class Vt220Block(props: Properties) : TerminalVariantBlock(props, TerminalKind.VT220)
