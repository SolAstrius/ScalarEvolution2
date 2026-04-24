/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server.gc;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * Two-step confirmation tokens for {@code /scev gc purge}. Ephemeral,
 * per-issuer, single-use, and short-lived.
 *
 * <h2>Why tokens</h2>
 *
 * <p>Purge is the one GC path that bypasses retention and creation-grace,
 * and it's irreversible (no trash staging). A fat-fingered {@code /scev gc
 * purge} by an OP could delete everything not in a currently-loaded chunk.
 * The token gate:
 *
 * <ol>
 *   <li>Forces the operator to see a dry-run preview before committing.</li>
 *   <li>Can't be muscle-memory'd — the token is unpredictable per call.</li>
 *   <li>Expires on a short fuse (60 s) so a forgotten terminal doesn't leave
 *       a live confirm lying around.</li>
 *   <li>Is tied to the issuer (player name / console) so a token printed
 *       in one OP's chat can't be copy-pasted by another.</li>
 * </ol>
 *
 * <h2>Token format</h2>
 *
 * <p>8 chars, {@code XXXX-XXXX}, drawn from {@code A-Z0-9}. Easy to type
 * in chat from a player's mental buffer. Not secure against an attacker
 * with access to the same terminal (that's assumed OP-level access already),
 * just against accidental re-issue and cross-operator confusion.
 */
public final class PurgeTokenStore {
    private static final SecureRandom RNG = new SecureRandom();
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray(); // no I/O/0/1 (look-alikes)

    /** Token lifespan. Short — a purge should be confirmed immediately. */
    public static final long TOKEN_TTL_MILLIS = 60_000L;

    /** @issuerKey → pending token entry. */
    private final Map<String, Entry> byIssuer = new HashMap<>();

    /**
     * Create + store a fresh token for {@code issuerKey}. Overwrites any
     * prior pending token for the same issuer (re-running {@code /scev gc
     * purge} issues a new token; the old one becomes invalid).
     */
    public synchronized String issue(String issuerKey, long nowMillis) {
        Objects.requireNonNull(issuerKey, "issuerKey");
        String token = randomToken();
        byIssuer.put(issuerKey, new Entry(token, nowMillis + TOKEN_TTL_MILLIS));
        return token;
    }

    /**
     * Attempt to consume {@code token} for {@code issuerKey}. Returns true
     * iff:
     * <ul>
     *   <li>A token was previously issued to this exact issuer.</li>
     *   <li>The stored token matches {@code token} case-insensitively.</li>
     *   <li>The token hasn't expired ({@code nowMillis} ≤ expiry).</li>
     * </ul>
     *
     * <p>On success, removes the token (single-use). On failure, leaves the
     * stored token alone (so a typo doesn't invalidate the pending confirm —
     * the user can try again).
     */
    public synchronized boolean consume(String issuerKey, String token, long nowMillis) {
        Objects.requireNonNull(issuerKey, "issuerKey");
        Objects.requireNonNull(token, "token");
        Entry e = byIssuer.get(issuerKey);
        if (e == null) return false;
        if (nowMillis > e.expiryMillis) {
            // Stale — clean up as a side effect of the consume attempt.
            byIssuer.remove(issuerKey);
            return false;
        }
        if (!e.token.equalsIgnoreCase(token)) return false;
        byIssuer.remove(issuerKey);
        return true;
    }

    /** Clear any pending token for {@code issuerKey}. */
    public synchronized void cancel(String issuerKey) {
        byIssuer.remove(issuerKey);
    }

    /** True iff the issuer has a live (non-expired) pending token. */
    public synchronized boolean hasPending(String issuerKey, long nowMillis) {
        Entry e = byIssuer.get(issuerKey);
        return e != null && nowMillis <= e.expiryMillis;
    }

    /**
     * Expiry timestamp (wall-clock millis) of the issuer's pending token,
     * or {@code null} if none. Used by {@code /scev gc status}.
     */
    public synchronized @Nullable Long expiryOf(String issuerKey) {
        Entry e = byIssuer.get(issuerKey);
        return e == null ? null : e.expiryMillis;
    }

    private static String randomToken() {
        StringBuilder sb = new StringBuilder(9);
        for (int i = 0; i < 8; i++) {
            if (i == 4) sb.append('-');
            sb.append(ALPHABET[RNG.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }

    private record Entry(String token, long expiryMillis) {}
}
