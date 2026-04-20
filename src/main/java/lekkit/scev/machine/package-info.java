/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
/**
 * Backend-agnostic machine abstractions: {@link lekkit.scev.machine.MachineSpec}
 * (a pure value describing requested hardware), {@link lekkit.scev.machine.MachineBackend}
 * (the interface every backend implements), and device sub-interfaces
 * ({@link lekkit.scev.machine.FramebufferView}, {@link lekkit.scev.machine.KeyboardDevice},
 * {@link lekkit.scev.machine.MouseDevice}, {@link lekkit.scev.machine.GpioDevice}).
 *
 * <p>The production backend lives in {@code lekkit.scev.machine.rvvm}. The test
 * backend lives in {@code lekkit.scev.machine.test}.
 */
package lekkit.scev.machine;
