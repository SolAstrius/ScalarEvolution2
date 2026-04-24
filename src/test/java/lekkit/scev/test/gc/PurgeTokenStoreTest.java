/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.gc;

import static org.junit.jupiter.api.Assertions.*;

import lekkit.scev.server.gc.PurgeTokenStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the purge-confirmation token gate. The tokens protect a
 * destructive command — the semantics need to be exactly right.
 */
class PurgeTokenStoreTest {

    private static final long NOW = 1_000_000_000L;
    private static final long TTL = PurgeTokenStore.TOKEN_TTL_MILLIS;

    @Test
    @DisplayName("issue + consume succeeds within TTL")
    void happyPath() {
        PurgeTokenStore s = new PurgeTokenStore();
        String t = s.issue("alice", NOW);
        assertNotNull(t);
        assertTrue(t.length() >= 8, "token should be at least 8 chars");
        assertTrue(s.consume("alice", t, NOW + 1_000));
    }

    @Test
    @DisplayName("consume with wrong token fails")
    void wrongTokenRejected() {
        PurgeTokenStore s = new PurgeTokenStore();
        s.issue("alice", NOW);
        assertFalse(s.consume("alice", "WRONG-TKN", NOW + 1_000));
    }

    @Test
    @DisplayName("consume with wrong issuer fails")
    void wrongIssuerRejected() {
        PurgeTokenStore s = new PurgeTokenStore();
        String t = s.issue("alice", NOW);
        assertFalse(s.consume("bob", t, NOW + 1_000),
                "bob seeing alice's token in chat must not be able to confirm alice's purge");
    }

    @Test
    @DisplayName("consume after TTL expiry fails")
    void expiredRejected() {
        PurgeTokenStore s = new PurgeTokenStore();
        String t = s.issue("alice", NOW);
        assertFalse(s.consume("alice", t, NOW + TTL + 1));
    }

    @Test
    @DisplayName("consume is single-use")
    void singleUse() {
        PurgeTokenStore s = new PurgeTokenStore();
        String t = s.issue("alice", NOW);
        assertTrue(s.consume("alice", t, NOW + 1_000));
        assertFalse(s.consume("alice", t, NOW + 1_000),
                "second consume of the same token must fail");
    }

    @Test
    @DisplayName("re-issuing overwrites the prior token")
    void reIssueOverwrites() {
        PurgeTokenStore s = new PurgeTokenStore();
        String first = s.issue("alice", NOW);
        String second = s.issue("alice", NOW);
        assertNotEquals(first, second, "fresh token each time");
        assertFalse(s.consume("alice", first, NOW + 1_000),
                "old token invalid after re-issue");
        assertTrue(s.consume("alice", second, NOW + 1_000));
    }

    @Test
    @DisplayName("cancel removes pending token")
    void cancelClears() {
        PurgeTokenStore s = new PurgeTokenStore();
        String t = s.issue("alice", NOW);
        s.cancel("alice");
        assertFalse(s.consume("alice", t, NOW + 1_000));
    }

    @Test
    @DisplayName("hasPending reflects live + expired state")
    void hasPending() {
        PurgeTokenStore s = new PurgeTokenStore();
        assertFalse(s.hasPending("alice", NOW));
        s.issue("alice", NOW);
        assertTrue(s.hasPending("alice", NOW));
        assertTrue(s.hasPending("alice", NOW + TTL - 1));
        assertFalse(s.hasPending("alice", NOW + TTL + 1));
    }

    @Test
    @DisplayName("token characters are all from the readable alphabet")
    void tokenAlphabet() {
        PurgeTokenStore s = new PurgeTokenStore();
        String t = s.issue("alice", NOW);
        // Format: XXXX-XXXX (8 alphanumeric + dash)
        assertEquals(9, t.length());
        assertEquals('-', t.charAt(4));
        for (int i = 0; i < t.length(); i++) {
            if (i == 4) continue;
            char c = t.charAt(i);
            assertTrue((c >= 'A' && c <= 'Z') || (c >= '2' && c <= '9'),
                    "token char should be uppercase alphanumeric (no I/O/0/1), was: " + c);
            assertNotEquals('I', c, "I excluded to avoid 1/l confusion");
            assertNotEquals('O', c, "O excluded to avoid 0 confusion");
        }
    }

    @Test
    @DisplayName("wrong token doesn't invalidate the stored pending one")
    void typoDoesntBurnToken() {
        PurgeTokenStore s = new PurgeTokenStore();
        String t = s.issue("alice", NOW);
        assertFalse(s.consume("alice", "TYPO-TYPO", NOW + 1_000));
        // Should still be valid after the typo.
        assertTrue(s.consume("alice", t, NOW + 2_000));
    }

    @Test
    @DisplayName("issue(null) throws NPE")
    void issueNullThrows() {
        PurgeTokenStore s = new PurgeTokenStore();
        assertThrows(NullPointerException.class, () -> s.issue(null, NOW));
    }

    @Test
    @DisplayName("consume is case-insensitive")
    void consumeCaseInsensitive() {
        PurgeTokenStore s = new PurgeTokenStore();
        String t = s.issue("alice", NOW);
        // User might re-type in lowercase by habit — should still work.
        assertTrue(s.consume("alice", t.toLowerCase(), NOW + 1_000));
    }
}
