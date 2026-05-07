/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.rpc;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import lekkit.scev.rpc.Cobs;
import lekkit.scev.rpc.FrameStream;
import lekkit.scev.core.rpc.MsgValue;
import lekkit.scev.core.rpc.RpcErrors;
import lekkit.scev.rpc.RpcFrame;
import lekkit.scev.core.rpc.RpcProtocol;
import lekkit.scev.rpc.ScevRpcManager;
import lekkit.scev.test.machine.FakeMachineBackend;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end drive of the RPC plumbing with a fake serial: push a COBS-
 * framed MessagePack request into the "guest TX" side, invoke a tick, and
 * assert the response shows up on the "guest RX" side.
 *
 * <p>No RVVM, no Minecraft bus — just the pipeline.
 */
final class ScevRpcManagerTest {
    private final UUID uuid = UUID.randomUUID();

    @AfterEach void cleanup() { ScevRpcManager.unregister(uuid); }

    @Test void pingYieldsPong() {
        FakeMachineBackend.FakeSerial serial = new FakeMachineBackend.FakeSerial();
        ScevRpcManager mgr = ScevRpcManager.create(uuid, serial);

        // Guest sends: [0, 1, "ping", []]
        RpcFrame.Request req = new RpcFrame.Request(1, RpcProtocol.METHOD_PING, List.of());
        serial.produceTx(cobsFrame(RpcProtocol.encode(req)));

        mgr.tick();

        // Response should be sitting in the RX side now.
        byte[] rx = serial.consumeRx();
        assertTrue(rx.length > 0, "a response frame should have been written");
        RpcFrame decoded = decodeFirst(rx);
        assertTrue(decoded instanceof RpcFrame.Response, "expected a Response, got " + decoded);
        RpcFrame.Response rsp = (RpcFrame.Response) decoded;
        assertEquals(1, rsp.id());
        assertNull(rsp.error(), "ping should be error-free");
        assertEquals("pong", rsp.result().asString());

        assertEquals(1, mgr.requestsIn());
        assertEquals(1, mgr.responsesOut());
    }

    @Test void unknownMethodYieldsErrorFrame() {
        FakeMachineBackend.FakeSerial serial = new FakeMachineBackend.FakeSerial();
        ScevRpcManager mgr = ScevRpcManager.create(uuid, serial);

        RpcFrame.Request req = new RpcFrame.Request(7, "no.such.method", List.of());
        serial.produceTx(cobsFrame(RpcProtocol.encode(req)));

        mgr.tick();
        RpcFrame.Response rsp = (RpcFrame.Response) decodeFirst(serial.consumeRx());
        assertEquals(7, rsp.id());
        assertNotNull(rsp.error());
        assertEquals(RpcErrors.NO_SUCH_METHOD, rsp.error().code());
        assertTrue(rsp.error().message().contains("unknown"));
    }

    @Test void listReturnsEmptyArrayWhenNoAdjacentPeripherals() {
        // With CC: Tweaked on the test classpath (runtimeOnly) and the
        // scev mod bootstrapping during unit-test NeoForge init,
        // ScevCCHandlers.install registers real handlers on manager
        // create. The machine has no world location set in this test,
        // so ScevCCComputer's side-adjacent peripheral map is empty —
        // list succeeds with an empty array rather than erroring out.
        //
        // Servers *without* CC installed hit the stub registered by
        // ScevRpcHandlers.registerDefaults, which returns
        // "CC: Tweaked is not installed on this server". That stub is
        // covered by the stub handler's own identity — no need to
        // mock-deinstall CC here.
        FakeMachineBackend.FakeSerial serial = new FakeMachineBackend.FakeSerial();
        ScevRpcManager mgr = ScevRpcManager.create(uuid, serial);

        RpcFrame.Request req = new RpcFrame.Request(42, RpcProtocol.METHOD_LIST, List.of());
        serial.produceTx(cobsFrame(RpcProtocol.encode(req)));

        mgr.tick();
        RpcFrame.Response rsp = (RpcFrame.Response) decodeFirst(serial.consumeRx());
        assertEquals(42, rsp.id());
        assertNull(rsp.error(), "list should succeed with an empty result");
        assertTrue(rsp.result().isArray(), "result should be an array");
        assertEquals(0, rsp.result().asArray().size(), "no peripherals expected");
    }

    @Test void queuedEventShipsOnNextTick() {
        FakeMachineBackend.FakeSerial serial = new FakeMachineBackend.FakeSerial();
        ScevRpcManager mgr = ScevRpcManager.create(uuid, serial);

        mgr.sendEvent(RpcFrame.event("test_event", List.of(MsgValue.of(7L))));
        mgr.tick();

        RpcFrame.Event evt = (RpcFrame.Event) decodeFirst(serial.consumeRx());
        assertEquals("test_event", evt.name());
        assertEquals(7L, evt.args().get(0).asInt());
        assertEquals(1, mgr.eventsOut());
    }

    @Test void unknownChunkStreamReturnsNoSuchPeerError() {
        FakeMachineBackend.FakeSerial serial = new FakeMachineBackend.FakeSerial();
        ScevRpcManager mgr = ScevRpcManager.create(uuid, serial);

        RpcFrame.Request r = new RpcFrame.Request(
                1, RpcProtocol.METHOD_READ_CHUNK,
                List.of(MsgValue.of(99999L), MsgValue.of(0L), MsgValue.of(64L)));
        serial.produceTx(cobsFrame(RpcProtocol.encode(r)));
        mgr.tick();

        RpcFrame.Response rsp = (RpcFrame.Response) decodeFirst(serial.consumeRx());
        assertNotNull(rsp.error());
        assertEquals(lekkit.scev.core.rpc.RpcErrors.NO_SUCH_PEER, rsp.error().code());
    }

    @Test void malformedFrameCountsAsDecodeFailure() {
        FakeMachineBackend.FakeSerial serial = new FakeMachineBackend.FakeSerial();
        ScevRpcManager mgr = ScevRpcManager.create(uuid, serial);
        // Garbage bytes that don't parse as msgpack — send as a complete
        // COBS frame so FrameStream delivers the payload to the decoder.
        serial.produceTx(cobsFrame(new byte[] {(byte) 0xC1})); // 0xC1 is the unused/reserved tag.
        mgr.tick();
        assertEquals(0, serial.consumeRx().length, "no response for malformed input");
        assertTrue(mgr.decodeFailures() >= 1);
    }

    /* ------------------ helpers ------------------ */

    private static byte[] drainAll(FakeMachineBackend.FakeSerial serial) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        while (true) {
            byte[] rx = serial.consumeRx();
            if (rx.length == 0) break;
            out.write(rx, 0, rx.length);
        }
        return out.toByteArray();
    }

    private static byte[] cobsFrame(byte[] plain) {
        byte[] out = new byte[Cobs.maxEncodedSize(plain.length)];
        int n = Cobs.encode(plain, 0, plain.length, out, 0);
        byte[] ret = new byte[n];
        System.arraycopy(out, 0, ret, 0, n);
        return ret;
    }

    private static RpcFrame decodeFirst(byte[] wire) {
        FrameStream s = new FrameStream(8192);
        for (byte[] frame : s.feed(wire, 0, wire.length)) {
            RpcFrame f = RpcProtocol.decode(frame);
            if (f != null) return f;
        }
        fail("no frame recovered from: " + wire.length + " bytes");
        return null;
    }
}
