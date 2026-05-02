/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server.gc

import java.security.SecureRandom

/**
 * Two-step confirmation tokens for `/scev gc purge`. Ephemeral, per-issuer,
 * single-use, short-lived.
 *
 * Purge is the one GC path that bypasses retention and creation-grace, and
 * it's irreversible. A fat-fingered `/scev gc purge` by an OP could delete
 * everything not in a currently-loaded chunk. The token gate:
 *   - forces the operator to see a dry-run preview before committing,
 *   - can't be muscle-memory'd (token is unpredictable per call),
 *   - expires on a short fuse so a forgotten terminal doesn't leave a live
 *     confirm lying around,
 *   - is tied to the issuer (player name / console) so a token printed in
 *     one OP's chat can't be copy-pasted by another.
 *
 * Token format: 8 chars `XXXX-XXXX` from `A-Z2-9` (no I/O/0/1 lookalikes).
 * Easy to retype from chat. Not secure against an attacker with terminal
 * access (that's assumed OP-level already) — just against accidental re-
 * issue and cross-operator confusion.
 */
class PurgeTokenStore {
    /** @issuerKey -> pending token entry. */
    private val byIssuer = HashMap<String, Entry>()

    /**
     * Create + store a fresh token for [issuerKey]. Overwrites any prior
     * pending token for the same issuer (re-running `/scev gc purge` issues
     * a new token; the old one becomes invalid).
     */
    @Synchronized
    fun issue(issuerKey: String, nowMillis: Long): String {
        val token = randomToken()
        byIssuer[issuerKey] = Entry(token, nowMillis + TOKEN_TTL_MILLIS)
        return token
    }

    /**
     * Attempt to consume [token] for [issuerKey]. Returns true iff a matching
     * non-expired token was issued; on success removes the token (single-use).
     * Failure leaves the stored token alone so a typo doesn't invalidate the
     * pending confirm.
     */
    @Synchronized
    fun consume(issuerKey: String, token: String, nowMillis: Long): Boolean {
        val e = byIssuer[issuerKey] ?: return false
        if (nowMillis > e.expiryMillis) {
            byIssuer.remove(issuerKey)   // stale — clean up as a side effect
            return false
        }
        if (!e.token.equals(token, ignoreCase = true)) return false
        byIssuer.remove(issuerKey)
        return true
    }

    /** Clear any pending token for [issuerKey]. */
    @Synchronized
    fun cancel(issuerKey: String) { byIssuer.remove(issuerKey) }

    /** True iff the issuer has a live (non-expired) pending token. */
    @Synchronized
    fun hasPending(issuerKey: String, nowMillis: Long): Boolean {
        val e = byIssuer[issuerKey] ?: return false
        return nowMillis <= e.expiryMillis
    }

    /**
     * Expiry timestamp (wall-clock millis) of the issuer's pending token, or
     * `null` if none. Used by `/scev gc status`.
     */
    @Synchronized
    fun expiryOf(issuerKey: String): Long? = byIssuer[issuerKey]?.expiryMillis

    private data class Entry(val token: String, val expiryMillis: Long)

    companion object {
        /** Token lifespan. Short — a purge should be confirmed immediately. */
        @JvmField val TOKEN_TTL_MILLIS: Long = 60_000L

        private val RNG = SecureRandom()
        private val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray()

        private fun randomToken(): String = buildString(9) {
            for (i in 0 until 8) {
                if (i == 4) append('-')
                append(ALPHABET[RNG.nextInt(ALPHABET.size)])
            }
        }
    }
}
