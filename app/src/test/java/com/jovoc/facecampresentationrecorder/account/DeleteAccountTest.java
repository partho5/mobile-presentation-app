package com.jovoc.facecampresentationrecorder.account;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import com.jovoc.facecampresentationrecorder.auth.ApiClient;
import com.jovoc.facecampresentationrecorder.auth.AuthRepository;
import com.jovoc.facecampresentationrecorder.auth.KeyValueStore;
import com.jovoc.facecampresentationrecorder.auth.TokenStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** Account deletion request, against a local MockWebServer. */
public class DeleteAccountTest {

    private MockWebServer server;
    private AccountApi api;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        TokenStore store = new TokenStore(new MapStore());
        long now = 1_000_000L;
        AuthRepository repo = new AuthRepository(new ApiClient(server.url("/").toString(), true), store, () -> now);
        store.saveTokens("acc", "ref", "sid", 900, now);
        store.saveIdentity("a@b.co", "Pixel 8");
        store.saveWorkspace("ws1", "Team");
        api = new AccountApi(repo);
    }

    @After
    public void tearDown() throws IOException {
        server.shutdown();
    }

    private AccountException.Kind failureKind() {
        try {
            api.deleteAccount();
        } catch (AccountException e) {
            return e.kind;
        }
        fail("expected AccountException");
        return null;
    }

    @Test
    public void sendsAuthedDeleteWithoutWorkspace() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(204));
        api.deleteAccount();
        RecordedRequest r = server.takeRequest();
        assertEquals("DELETE", r.getMethod());
        assertEquals(AccountApi.PATH_DELETE_ACCOUNT, r.getPath());
        assertEquals("Bearer acc", r.getHeader("Authorization"));
        assertNull(r.getHeader("x-workspace-id"));
    }

    @Test
    public void missingRouteIsUnavailableNotNotFound() {
        server.enqueue(new MockResponse().setResponseCode(404));
        assertEquals(AccountException.Kind.UNAVAILABLE, failureKind());
        server.enqueue(new MockResponse().setResponseCode(405));
        assertEquals(AccountException.Kind.UNAVAILABLE, failureKind());
    }

    @Test
    public void serverErrorAndRateLimitAreMapped() {
        server.enqueue(new MockResponse().setResponseCode(500));
        assertEquals(AccountException.Kind.SERVER, failureKind());
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "30"));
        assertEquals(AccountException.Kind.RATE_LIMITED, failureKind());
    }

    @Test
    public void offlineIsNetworkError() throws IOException {
        server.shutdown();
        assertEquals(AccountException.Kind.NETWORK, failureKind());
    }

    private static final class MapStore implements KeyValueStore {
        private final Map<String, String> data = new HashMap<>();

        @Override
        public synchronized String get(String key) {
            return data.get(key);
        }

        @Override
        public synchronized void putAll(Map<String, String> values) {
            for (Map.Entry<String, String> e : values.entrySet()) {
                if (e.getValue() == null) data.remove(e.getKey());
                else data.put(e.getKey(), e.getValue());
            }
        }

        @Override
        public synchronized void clear() {
            data.clear();
        }
    }
}
