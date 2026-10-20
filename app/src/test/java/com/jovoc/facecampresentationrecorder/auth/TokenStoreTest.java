package com.jovoc.facecampresentationrecorder.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TokenStoreTest {

    private final MemoryKeyValueStore kv = new MemoryKeyValueStore();
    private final TokenStore store = new TokenStore(kv);

    @Test
    public void emptyStoreHasNoSession() {
        assertFalse(store.hasSession());
        assertNull(store.accessToken());
        assertTrue(store.accessNeedsRefresh(0));
    }

    @Test
    public void savesAndReadsPairAndIdentity() {
        store.saveTokens("acc", "ref", "sid", 900, 1_000L);
        store.saveIdentity("a@b.co", "Pixel 8");
        assertTrue(store.hasSession());
        assertEquals("acc", store.accessToken());
        assertEquals("ref", store.refreshToken());
        assertEquals("sid", store.sessionId());
        assertEquals("a@b.co", store.email());
        assertEquals("Pixel 8", store.deviceName());
        assertEquals(901_000L, store.accessExpiresAtMs());
    }

    @Test
    public void rotationKeepsIdentity() {
        store.saveTokens("a1", "r1", "s", 900, 0);
        store.saveIdentity("a@b.co", "Pixel 8");
        store.saveTokens("a2", "r2", "s", 900, 0);
        assertEquals("r2", store.refreshToken());
        assertEquals("a@b.co", store.email());
    }

    @Test
    public void refreshDecisionUsesMargin() {
        store.saveTokens("acc", "ref", "sid", 900, 0); // expires at 900_000
        long limit = 900_000L - TokenStore.REFRESH_MARGIN_MS;
        assertFalse(store.accessNeedsRefresh(limit - 1));
        assertTrue(store.accessNeedsRefresh(limit));
        assertTrue(store.accessNeedsRefresh(900_000L));
    }

    @Test
    public void clearRemovesEverything() {
        store.saveTokens("acc", "ref", "sid", 900, 0);
        store.saveIdentity("a@b.co", "Pixel 8");
        store.clear();
        assertFalse(store.hasSession());
        assertNull(store.email());
        assertTrue(kv.data.isEmpty());
    }

    @Test
    public void workspacePick_isStored_clearable_andWipedOnSignOut() {
        assertNull(store.workspaceId());
        store.saveTokens("acc", "ref", "sid", 900, 0);
        store.saveWorkspace("w1", "Acme");
        assertEquals("w1", store.workspaceId());
        assertEquals("Acme", store.workspaceName());
        store.saveTokens("acc2", "ref2", "sid", 900, 0); // a refresh keeps the pick
        assertEquals("w1", store.workspaceId());
        store.clearWorkspace();
        assertNull(store.workspaceId());
        assertTrue(store.hasSession());
        store.saveWorkspace("w2", "Beta");
        store.clear();
        assertNull(store.workspaceId());
    }

    @Test
    public void sessionListenerFiresOnClearAndOnANewSessionIdOnly() {
        int[] calls = {0};
        store.setSessionListener(() -> calls[0]++);
        store.saveTokens("a1", "r1", "s1", 900, 0); // sign-in: a new session
        assertEquals(1, calls[0]);
        store.saveTokens("a2", "r2", "s1", 900, 0); // refresh: same session
        assertEquals(1, calls[0]);
        store.saveTokens("a3", "r3", "s2", 900, 0); // signed in again as someone else
        assertEquals(2, calls[0]);
        store.clear(); // sign-out or a dead session
        assertEquals(3, calls[0]);
    }

    @Test
    public void failingSessionListenerNeverBreaksTheStore() {
        store.setSessionListener(() -> {
            throw new IllegalStateException("boom");
        });
        store.saveTokens("acc", "ref", "sid", 900, 0);
        assertTrue(store.hasSession());
        store.clear();
        assertFalse(store.hasSession());
    }

    @Test
    public void garbageExpiryCountsAsExpired() {
        store.saveTokens("acc", "ref", "sid", 900, 0);
        kv.data.put("access_expires_at", "not-a-number");
        assertEquals(0L, store.accessExpiresAtMs());
        assertTrue(store.accessNeedsRefresh(1));
    }
}
