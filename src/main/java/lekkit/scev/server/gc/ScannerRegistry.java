/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server.gc;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;

/**
 * Extensible registry of {@link DiskImageScanner}s. The GC orchestrator asks
 * this class for "every scanner we should run right now"; every entry gets
 * invoked in registration order.
 *
 * <h2>When to register</h2>
 *
 * <p>Built-in scanners are registered from {@link #registerBuiltins()} during
 * {@code FMLCommonSetupEvent}. Mod compat modules (AE2, RS, Create, Mekanism,
 * …) can register their own scanner from their own common-setup hook. No
 * classpath dep on scev required: as long as the compat mod can see
 * {@link DiskImageScanner}, it can call {@link #register(DiskImageScanner)}.
 *
 * <h2>Pattern mirrors {@code FirmwareRegistry} / {@code DiskTemplateRegistry}</h2>
 *
 * <p>Plain static singleton guarded by a class lock. Registration happens at
 * mod init; queries happen from the GC path. Contention is nil.
 *
 * <h2>Scanner contract</h2>
 *
 * <p>See {@link DiskImageScanner} for the full contract. Summary: be fast, be
 * idempotent, be read-only, and report UUIDs conservatively (err on the side
 * of "live" rather than "orphan").
 */
public final class ScannerRegistry {
    private static final Logger LOG = LogUtils.getLogger();

    /** Backing list. Registration order is preserved so logs are stable. */
    private static final List<DiskImageScanner> SCANNERS = new ArrayList<>();

    private ScannerRegistry() {}

    /**
     * Add {@code scanner} to the active set. Repeat registrations of the same
     * instance are ignored (identity check); different-instance duplicates
     * from the same source are allowed — it's the registrant's job not to
     * register dupes.
     */
    public static synchronized void register(DiskImageScanner scanner) {
        Objects.requireNonNull(scanner, "scanner");
        for (DiskImageScanner existing : SCANNERS) {
            if (existing == scanner) return; // idempotent identity-dedupe
        }
        SCANNERS.add(scanner);
        LOG.debug("Registered scev GC scanner: {}", scanner.getClass().getName());
    }

    /** Defensive snapshot of the current scanner list. */
    public static synchronized List<DiskImageScanner> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(SCANNERS));
    }

    /** For tests + status readouts. */
    public static synchronized int size() {
        return SCANNERS.size();
    }

    /**
     * Test-only: clear all registrations. Avoids state leaking between test
     * methods that swap in fakes.
     */
    public static synchronized void clearForTests() {
        SCANNERS.clear();
    }
}
