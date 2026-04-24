/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.gc;

import static org.junit.jupiter.api.Assertions.*;

import lekkit.scev.server.gc.DiskImageScanner;
import lekkit.scev.server.gc.ScanContext;
import lekkit.scev.server.gc.ScannerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the extensibility SPI — {@link ScannerRegistry}. Sanity-check the
 * registration contract since mod authors will rely on these semantics when
 * writing compat scanners.
 */
class ScannerRegistryTest {

    @BeforeEach
    void reset() { ScannerRegistry.clearForTests(); }

    @AfterEach
    void tearDown() { ScannerRegistry.clearForTests(); }

    @Test
    @DisplayName("register adds a scanner, snapshot returns it")
    void registerAndSnapshot() {
        DiskImageScanner s = ctx -> {};
        ScannerRegistry.register(s);
        assertEquals(1, ScannerRegistry.size());
        assertSame(s, ScannerRegistry.snapshot().get(0));
    }

    @Test
    @DisplayName("registration order is preserved in snapshot")
    void orderPreserved() {
        DiskImageScanner a = ctx -> {};
        DiskImageScanner b = ctx -> {};
        DiskImageScanner c = ctx -> {};
        ScannerRegistry.register(a);
        ScannerRegistry.register(b);
        ScannerRegistry.register(c);
        var snap = ScannerRegistry.snapshot();
        assertSame(a, snap.get(0));
        assertSame(b, snap.get(1));
        assertSame(c, snap.get(2));
    }

    @Test
    @DisplayName("registering the same identity twice is a no-op")
    void identityDedup() {
        DiskImageScanner s = ctx -> {};
        ScannerRegistry.register(s);
        ScannerRegistry.register(s);
        assertEquals(1, ScannerRegistry.size());
    }

    @Test
    @DisplayName("different instances with same behaviour both register")
    void differentInstancesBothRegistered() {
        // Two distinct lambdas are distinct instances even if they do the
        // same thing. The registry trusts the caller here — it dedupes by
        // identity only so an overzealous guard doesn't reject "two mods
        // happened to write similar scanners."
        DiskImageScanner a = ctx -> {};
        DiskImageScanner b = ctx -> {};
        ScannerRegistry.register(a);
        ScannerRegistry.register(b);
        assertEquals(2, ScannerRegistry.size());
    }

    @Test
    @DisplayName("register(null) throws NPE")
    void registerNullThrows() {
        assertThrows(NullPointerException.class, () -> ScannerRegistry.register(null));
    }

    @Test
    @DisplayName("snapshot is defensively copied — mutations don't leak")
    void snapshotIsDefensive() {
        ScannerRegistry.register(ctx -> {});
        var snap = ScannerRegistry.snapshot();
        assertThrows(UnsupportedOperationException.class,
                () -> snap.add(ctx -> {}),
                "snapshot should be unmodifiable");
    }

    @Test
    @DisplayName("scanners receive the ScanContext and can add UUIDs")
    void scannerReceivesContext() {
        java.util.UUID u = java.util.UUID.randomUUID();
        DiskImageScanner s = ctx -> ctx.addLive(u);
        ScannerRegistry.register(s);

        ScanContext ctx = new ScanContext(null);
        for (DiskImageScanner scanner : ScannerRegistry.snapshot()) {
            scanner.scan(ctx);
        }
        assertTrue(ctx.liveUuids().contains(u));
    }
}
