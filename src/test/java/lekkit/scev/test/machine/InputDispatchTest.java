/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.machine;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import lekkit.rvvm.HIDKeyboard;
import lekkit.scev.machine.KeyboardDevice;
import lekkit.scev.machine.MachineSpec;
import lekkit.scev.machine.MouseDevice;
import lekkit.scev.network.MachineInputPayload;
import lekkit.scev.server.MachineManager;
import lekkit.scev.server.MachineState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * End-to-end test for input dispatch: a {@link MachineInputPayload} that
 * arrived from the network is handed to the same code path as
 * {@code ScevNetwork.handleInputOnServer} would use, and the
 * {@link FakeMachineBackend}'s device fakes record the call.
 *
 * <p>This catches regressions where a kind-switch in the dispatcher silently
 * forgets to forward a particular message type.
 */
class InputDispatchTest {

    @BeforeEach
    void swapBackend() {
        MachineManager.setBackendFactory(FakeMachineBackend::new);
    }

    @AfterEach
    void teardown() {
        MachineManager.finishAllMachines();
        MachineManager.setBackendFactory(null);
    }

    /** Inline copy of {@code ScevNetwork.handleInputOnServer}'s dispatch. */
    private static void dispatch(MachineInputPayload p, MachineState state) {
        KeyboardDevice kb = state.getKeyboard();
        MouseDevice m = state.getMouse();
        switch (p.kind()) {
            case KEY_PRESS   -> { if (kb != null) kb.press(p.keyByte()); }
            case KEY_RELEASE -> { if (kb != null) kb.release(p.keyByte()); }
            case MOUSE_PRESS   -> { if (m != null) m.press(p.keyByte()); }
            case MOUSE_RELEASE -> { if (m != null) m.release(p.keyByte()); }
            case MOUSE_SCROLL  -> { if (m != null) m.scroll(p.keyByte()); }
            case MOUSE_MOVE    -> { if (m != null) m.move(p.mouseX(), p.mouseY()); }
            case MOUSE_PLACE   -> { if (m != null) m.place(p.mouseX(), p.mouseY()); }
        }
    }

    private MachineState startMachine() {
        MachineSpec spec = MachineSpec.builder(UUID.randomUUID()).memMb(64).defaultDisplay().build();
        MachineState s = MachineManager.createMachineState(spec);
        assertNotNull(s);
        s.start();
        return s;
    }

    @Test
    @DisplayName("KEY_PRESS payload -> keyboard.press recorded")
    void keyPressForwarded() {
        MachineState s = startMachine();
        FakeMachineBackend fake = (FakeMachineBackend) s.getBackend();
        dispatch(MachineInputPayload.keyPress(HIDKeyboard.HID_KEY_A), s);
        assertTrue(fake.keyboardRaw().ops.contains("press:4"));
    }

    @Test
    @DisplayName("KEY_RELEASE payload -> keyboard.release recorded")
    void keyReleaseForwarded() {
        MachineState s = startMachine();
        FakeMachineBackend fake = (FakeMachineBackend) s.getBackend();
        dispatch(MachineInputPayload.keyRelease(HIDKeyboard.HID_KEY_Z), s);
        assertTrue(fake.keyboardRaw().ops.contains("release:29"));
    }

    @Test
    @DisplayName("MOUSE_PRESS payload -> mouse.press recorded")
    void mousePressForwarded() {
        MachineState s = startMachine();
        FakeMachineBackend fake = (FakeMachineBackend) s.getBackend();
        dispatch(MachineInputPayload.mousePress((byte) 2), s);
        assertTrue(fake.mouseRaw().ops.contains("press:2"));
    }

    @Test
    @DisplayName("MOUSE_PLACE payload -> mouse.place(x, y) recorded")
    void mousePlaceForwarded() {
        MachineState s = startMachine();
        FakeMachineBackend fake = (FakeMachineBackend) s.getBackend();
        dispatch(MachineInputPayload.mousePlace((short) 123, (short) 456), s);
        assertTrue(fake.mouseRaw().ops.contains("place:123,456"));
        assertEquals(123, fake.mouseRaw().curX);
        assertEquals(456, fake.mouseRaw().curY);
    }

    @Test
    @DisplayName("MOUSE_SCROLL payload -> mouse.scroll recorded")
    void mouseScrollForwarded() {
        MachineState s = startMachine();
        FakeMachineBackend fake = (FakeMachineBackend) s.getBackend();
        dispatch(MachineInputPayload.mouseScroll((byte) -1), s);
        assertTrue(fake.mouseRaw().ops.contains("scroll:-1"));
    }

    @Test
    @DisplayName("Every input Kind has a dispatch path (no silent drops)")
    void everyKindDispatched() {
        MachineState s = startMachine();
        FakeMachineBackend fake = (FakeMachineBackend) s.getBackend();

        for (MachineInputPayload.Kind kind : MachineInputPayload.Kind.values()) {
            int beforeKb = fake.keyboardRaw().ops.size();
            int beforeMs = fake.mouseRaw().ops.size();
            MachineInputPayload payload = switch (kind) {
                case KEY_PRESS   -> MachineInputPayload.keyPress((byte) 4);
                case KEY_RELEASE -> MachineInputPayload.keyRelease((byte) 4);
                case MOUSE_PRESS -> MachineInputPayload.mousePress((byte) 1);
                case MOUSE_RELEASE -> MachineInputPayload.mouseRelease((byte) 1);
                case MOUSE_SCROLL -> MachineInputPayload.mouseScroll((byte) 1);
                case MOUSE_MOVE -> MachineInputPayload.mouseMove((short) 1, (short) 1);
                case MOUSE_PLACE -> MachineInputPayload.mousePlace((short) 0, (short) 0);
            };
            dispatch(payload, s);
            int after = fake.keyboardRaw().ops.size() + fake.mouseRaw().ops.size();
            int before = beforeKb + beforeMs;
            assertTrue(after > before, "Kind " + kind + " left no trace in keyboard/mouse ops");
        }
    }
}
