package com.jovoc.facecampresentationrecorder.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** A call never goes out without a brand, and a brand that refused the phone is never swapped for another. */
public class WorkspaceResolverTest {

    private MockWebServer server;
    private TokenStore store;
    private AuthRepository repo;
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final AtomicInteger resolverCalls = new AtomicInteger();

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        store = new TokenStore(new MemoryKeyValueStore());
        repo = new AuthRepository(new ApiClient(server.url("/").toString(), true), store, now::get);
        store.saveTokens("acc", "ref", "sid", 900, now.get());
    }

    @After
    public void tearDown() throws IOException {
        server.shutdown();
    }

    private static MockResponse json(int code, String body) {
        return new MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body);
    }

    private static AuthException.Kind kindOf(Call c) {
        try {
            c.run();
        } catch (AuthException e) {
            return e.kind;
        }
        fail("expected AuthException");
        return null;
    }

    private interface Call {
        void run() throws AuthException;
    }

    @Test
    public void noPickResolvesTheDefaultOnceBeforeTheCall() throws Exception {
        repo.setWorkspaceResolver(() -> {
            resolverCalls.incrementAndGet();
            store.saveWorkspace("w9", "Nine");
            return "w9";
        });
        server.enqueue(json(200, "[]"));
        repo.execute("GET", "/public/v1/integrations", null, null);
        assertEquals("w9", server.takeRequest().getHeader("x-workspace-id"));
        assertEquals(1, resolverCalls.get());
    }

    @Test
    public void aStoredPickIsUsedWithoutResolving() throws Exception {
        store.saveWorkspace("w1", "Acme");
        repo.setWorkspaceResolver(() -> {
            throw new AuthException(AuthException.Kind.SERVER);
        });
        server.enqueue(json(200, "[]"));
        repo.execute("GET", "/public/v1/integrations", null, null);
        assertEquals("w1", server.takeRequest().getHeader("x-workspace-id"));
    }

    @Test
    public void whenTheDefaultCannotBeHadNothingIsSent() {
        repo.setWorkspaceResolver(() -> {
            throw new AuthException(AuthException.Kind.NETWORK);
        });
        assertEquals(AuthException.Kind.NETWORK,
                kindOf(() -> repo.execute("GET", "/public/v1/integrations", null, null)));
        assertEquals(0, server.getRequestCount());
    }

    @Test
    public void aResolverThatReturnsNothingCountsAsNoConnection() {
        repo.setWorkspaceResolver(() -> "");
        assertEquals(AuthException.Kind.NETWORK, kindOf(() -> repo.brandForCall()));
    }

    @Test
    public void brandForCallIsNullWhileSignedOut() throws AuthException {
        store.clear();
        repo.setWorkspaceResolver(() -> {
            throw new AssertionError("no resolving without a session");
        });
        assertNull(repo.brandForCall());
    }

    @Test
    public void aForbiddenPickIsMarkedLostAndTheNextCallStillUsesIt() throws Exception {
        store.saveWorkspace("w1", "Acme");
        repo.setWorkspaceResolver(() -> {
            throw new AssertionError("a stored pick is never replaced by resolving");
        });
        server.enqueue(json(403, "{\"statusCode\":403,\"code\":\"WORKSPACE_FORBIDDEN\"}"));
        ApiResponse r = repo.execute("GET", "/public/v1/integrations", null, null);
        assertEquals(403, r.status);
        assertTrue(store.workspaceAccessLost());
        assertEquals("w1", store.workspaceId());

        server.enqueue(json(200, "[]"));
        repo.execute("GET", "/public/v1/integrations", null, null);
        RecordedRequest first = server.takeRequest();
        RecordedRequest second = server.takeRequest();
        assertEquals("w1", first.getHeader("x-workspace-id"));
        assertEquals("w1", second.getHeader("x-workspace-id"));
    }

    @Test
    public void aJobsOwnBrandIsNotChangedByAForbiddenResponse() throws Exception {
        store.saveWorkspace("w-now", "Now");
        server.enqueue(json(403, "{\"statusCode\":403,\"code\":\"WORKSPACE_FORBIDDEN\"}"));
        java.util.Map<String, String> h = new java.util.HashMap<>();
        h.put("x-workspace-id", "w-old");
        repo.execute("GET", "/public/v1/integrations", null, h);
        assertEquals("w-old", server.takeRequest().getHeader("x-workspace-id"));
        assertFalse(store.workspaceAccessLost());
        assertEquals("w-now", store.workspaceId());
    }
}
