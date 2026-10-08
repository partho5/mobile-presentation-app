package com.jovoc.facecampresentationrecorder.account;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jovoc.facecampresentationrecorder.auth.ApiClient;
import com.jovoc.facecampresentationrecorder.auth.AuthRepository;
import com.jovoc.facecampresentationrecorder.auth.KeyValueStore;
import com.jovoc.facecampresentationrecorder.auth.TokenStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** Against a local MockWebServer; never touches a real backend. */
public class AccountApiTest {

    private MockWebServer server;
    private TokenStore store;
    private AccountApi api;
    private AuthRepository repo;
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final AtomicInteger forcedSignOuts = new AtomicInteger();

    @Before
    public void setUp() throws Exception {
        AccountApi.resetBalanceProbe();
        server = new MockWebServer();
        server.start();
        store = new TokenStore(new MapStore());
        repo = new AuthRepository(new ApiClient(server.url("/").toString(), true), store, now::get);
        repo.setForcedSignOutListener(forcedSignOuts::incrementAndGet);
        store.saveTokens("acc", "ref", "sid", 900, now.get());
        store.saveIdentity("a@b.co", "Pixel 8");
        api = new AccountApi(repo);
    }

    @After
    public void tearDown() throws IOException {
        server.shutdown();
    }

    private static MockResponse json(int code, String body) {
        return new MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body);
    }

    private static AccountException.Kind kindOf(Runnable2 r) {
        try {
            r.run();
        } catch (AccountException e) {
            return e.kind;
        }
        fail("expected AccountException");
        return null;
    }

    private interface Runnable2 {
        void run() throws AccountException;
    }

    /** In-memory store (the auth tests' one is package-private). */
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

    @Test
    public void workspaceList_goesWithoutThePickedWorkspace_butCarriesBearer() throws Exception {
        store.saveWorkspace("w-stale", "Old");
        server.enqueue(json(200, "[{\"id\":\"w1\",\"name\":\"Acme\",\"users\":[{\"role\":\"ADMIN\",\"disabled\":false}]}]"));
        assertEquals(1, api.listWorkspaces().size());
        RecordedRequest r = server.takeRequest();
        assertEquals("/user/organizations", r.getPath());
        assertEquals("Bearer acc", r.getHeader("Authorization"));
        assertNull(r.getHeader("x-workspace-id"));
    }

    @Test
    public void currentWorkspace_readsOrgId_andSendsThePick() throws Exception {
        store.saveWorkspace("w1", "Acme");
        server.enqueue(json(200, "{\"id\":\"u1\",\"orgId\":\"w1\",\"role\":\"ADMIN\"}"));
        assertEquals("w1", api.currentWorkspaceId());
        assertEquals("w1", server.takeRequest().getHeader("x-workspace-id"));
    }

    @Test
    public void balance_prefersContractRoute() throws Exception {
        store.saveWorkspace("w1", "Acme");
        server.enqueue(json(200, "{\"available\":90,\"reserved\":10,\"low\":false}"));
        CreditBalance b = api.balance();
        assertEquals(90, b.available);
        assertEquals(10, b.reserved);
        RecordedRequest r = server.takeRequest();
        assertEquals("/credits/balance", r.getPath());
        assertEquals("w1", r.getHeader("x-workspace-id"));
        assertEquals(1, server.getRequestCount());
    }

    @Test
    public void balance_fallsBackToCopilotCredits_andRemembersTheMissingRoute() throws Exception {
        server.enqueue(json(404, "{\"statusCode\":404,\"message\":\"Cannot GET /credits/balance\"}"));
        server.enqueue(json(200, "{\"credits\":7}"));
        CreditBalance b = api.balance();
        assertEquals(7, b.available);
        assertFalse(b.hasReserved());
        assertEquals("/credits/balance", server.takeRequest().getPath());
        assertEquals("/copilot/credits", server.takeRequest().getPath());

        server.enqueue(json(200, "{\"credits\":8}"));
        assertEquals(8, api.balance().available);
        assertEquals("/copilot/credits", server.takeRequest().getPath()); // no second probe
    }

    @Test
    public void balance_serverError_isNotMistakenForMissingRoute() throws Exception {
        server.enqueue(json(500, "{}"));
        assertEquals(AccountException.Kind.SERVER, kindOf(() -> api.balance()));
        server.enqueue(json(200, "{\"available\":1}"));
        assertEquals(1, api.balance().available);
        assertEquals("/credits/balance", server.takeRequest().getPath());
        assertEquals("/credits/balance", server.takeRequest().getPath());
    }

    @Test
    public void workspaceForbidden_dropsThePick_andIsReported() throws Exception {
        store.saveWorkspace("w-gone", "Gone");
        server.enqueue(json(403, "{\"statusCode\":403,\"code\":\"WORKSPACE_FORBIDDEN\"}"));
        assertEquals(AccountException.Kind.WORKSPACE_FORBIDDEN, kindOf(() -> api.balance()));
        assertNull(store.workspaceId());
        assertTrue(store.hasSession());
    }

    @Test
    public void workspaceForbidden_forAJobsOwnWorkspace_keepsTheDevicePick() throws Exception {
        store.saveWorkspace("w-now", "Now");
        server.enqueue(json(403, "{\"statusCode\":403,\"code\":\"WORKSPACE_FORBIDDEN\"}"));
        repo.execute("GET", "/public/v1/integrations", null,
                Collections.singletonMap("x-workspace-id", "w-old"));
        assertEquals("w-now", store.workspaceId());
        assertEquals("w-old", server.takeRequest().getHeader("x-workspace-id"));
    }

    @Test
    public void devices_listIsParsed_andNeverSendsAWorkspace() throws Exception {
        store.saveWorkspace("w1", "Acme");
        server.enqueue(json(200, "[{\"id\":\"s1\",\"name\":\"Pixel 8\",\"current\":true,"
                + "\"lastSeenAt\":\"2026-10-07T12:30:00.000Z\",\"createdAt\":\"2026-10-01T00:00:00Z\"}]"));
        assertTrue(api.listDevices().get(0).current);
        RecordedRequest r = server.takeRequest();
        assertEquals("/auth/sessions", r.getPath());
        assertNull(r.getHeader("x-workspace-id"));
    }

    @Test
    public void revokeDevice_usesDelete_and404MeansDone() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(204));
        api.revokeDevice("abc-123");
        RecordedRequest r = server.takeRequest();
        assertEquals("DELETE", r.getMethod());
        assertEquals("/auth/sessions/abc-123", r.getPath());

        server.enqueue(json(404, "{\"statusCode\":404}"));
        api.revokeDevice("abc-123");
    }

    @Test
    public void revokeDevice_refusesUnsafeIds_withoutCallingTheServer() {
        assertEquals(AccountException.Kind.NOT_FOUND, kindOf(() -> api.revokeDevice("../../user/self")));
        assertEquals(0, server.getRequestCount());
    }

    @Test
    public void revokeOthers_sendsExceptCurrent() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(204));
        api.revokeOtherDevices();
        RecordedRequest r = server.takeRequest();
        assertEquals("DELETE", r.getMethod());
        assertEquals("/auth/sessions?except=current", r.getPath());
    }

    @Test
    public void revokedSession_signsOut_andIsReportedAsSignedOut() throws Exception {
        server.enqueue(json(401, "{\"statusCode\":401,\"code\":\"SESSION_REVOKED\"}"));
        assertEquals(AccountException.Kind.SIGNED_OUT, kindOf(() -> api.listDevices()));
        assertFalse(store.hasSession());
        assertEquals(1, forcedSignOuts.get());
    }

    @Test
    public void offline_isReportedAsNetwork() throws Exception {
        server.shutdown();
        assertEquals(AccountException.Kind.NETWORK, kindOf(() -> api.listWorkspaces()));
    }

    @Test
    public void rateLimit_carriesRetryAfter() {
        server.enqueue(json(429, "{\"statusCode\":429,\"code\":\"AUTH_RATE_LIMITED\"}").setHeader("Retry-After", "12"));
        try {
            api.listDevices();
            fail("expected");
        } catch (AccountException e) {
            assertEquals(AccountException.Kind.RATE_LIMITED, e.kind);
            assertEquals(12, e.retryAfterSeconds);
        }
    }
}
