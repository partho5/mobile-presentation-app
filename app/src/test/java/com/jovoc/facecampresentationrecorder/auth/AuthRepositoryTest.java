package com.jovoc.facecampresentationrecorder.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** Network tests against a local MockWebServer; never touches a real backend. */
public class AuthRepositoryTest {

    private static final String PAIR_1 =
            "{\"access_token\":\"acc1\",\"token_type\":\"Bearer\",\"expires_in\":900,"
                    + "\"refresh_token\":\"ref1\",\"session_id\":\"sid\"}";
    private static final String PAIR_2 =
            "{\"access_token\":\"acc2\",\"token_type\":\"Bearer\",\"expires_in\":900,"
                    + "\"refresh_token\":\"ref2\",\"session_id\":\"sid\"}";

    private MockWebServer server;
    private TokenStore store;
    private AuthRepository repo;
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final AtomicInteger forcedSignOuts = new AtomicInteger();

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        store = new TokenStore(new MemoryKeyValueStore());
        repo = new AuthRepository(new ApiClient(server.url("/").toString(), true), store, now::get);
        repo.setForcedSignOutListener(forcedSignOuts::incrementAndGet);
    }

    @After
    public void tearDown() throws Exception {
        try {
            server.shutdown();
        } catch (IOException ignored) {
            // already shut down by the test
        }
    }

    private void signedIn() {
        store.saveTokens("old", "ref1", "sid", 900, now.get());
        store.saveIdentity("a@b.co", "Pixel 8");
    }

    private static AuthException.Kind kindOf(ThrowingRunnable r) {
        try {
            r.run();
        } catch (AuthException e) {
            return e.kind;
        }
        fail("expected AuthException");
        return null;
    }

    private interface ThrowingRunnable {
        void run() throws AuthException;
    }

    // ---- request code ----

    @Test
    public void requestCodeSendsNormalizedEmail() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(204));
        repo.requestCode("  Me@Example.COM ");
        RecordedRequest r = server.takeRequest();
        assertEquals("POST", r.getMethod());
        assertEquals("/auth/otp/request", r.getPath());
        assertEquals("me@example.com", new JSONObject(r.getBody().readUtf8()).getString("email"));
    }

    @Test
    public void requestCodeRejectsBadEmailWithoutNetwork() {
        assertEquals(AuthException.Kind.BAD_INPUT, kindOf(() -> repo.requestCode("not-an-email")));
        assertEquals(0, server.getRequestCount());
    }

    @Test
    public void requestCodeMapsRateLimitMailFailureAndServerError() {
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "120")
                .setBody("{\"statusCode\":429,\"code\":\"AUTH_RATE_LIMITED\"}"));
        server.enqueue(new MockResponse().setResponseCode(503)
                .setBody("{\"statusCode\":503,\"code\":\"MAIL_DELIVERY_FAILED\"}"));
        server.enqueue(new MockResponse().setResponseCode(500));
        try {
            repo.requestCode("a@b.co");
            fail();
        } catch (AuthException e) {
            assertEquals(AuthException.Kind.RATE_LIMITED, e.kind);
            assertEquals(120, e.retryAfterSeconds);
        }
        assertEquals(AuthException.Kind.MAIL_FAILED, kindOf(() -> repo.requestCode("a@b.co")));
        assertEquals(AuthException.Kind.SERVER, kindOf(() -> repo.requestCode("a@b.co")));
    }

    @Test
    public void offlineIsReportedAsNetwork() throws Exception {
        server.shutdown();
        assertEquals(AuthException.Kind.NETWORK, kindOf(() -> repo.requestCode("a@b.co")));
    }

    // ---- exchange code ----

    @Test
    public void exchangeCodeStoresSessionAndReportsOnboarding() throws Exception {
        server.enqueue(new MockResponse().setBody(PAIR_1).setHeader("onboarding", "true"));
        boolean created = repo.exchangeCode("A@B.co", "123 456", "Pixel 8");
        assertTrue(created);
        assertEquals("acc1", store.accessToken());
        assertEquals("ref1", store.refreshToken());
        assertEquals("sid", store.sessionId());
        assertEquals("a@b.co", store.email());
        assertEquals("Pixel 8", store.deviceName());
        assertFalse(store.accessNeedsRefresh(now.get()));

        JSONObject body = new JSONObject(server.takeRequest().getBody().readUtf8());
        assertEquals("email_otp", body.getString("grant_type"));
        assertEquals("a@b.co", body.getString("email"));
        assertEquals("123456", body.getString("code"));
        assertEquals("Pixel 8", body.getString("device_name"));
    }

    @Test
    public void exchangeCodeForExistingAccountIsNotOnboarding() throws Exception {
        server.enqueue(new MockResponse().setBody(PAIR_1));
        assertFalse(repo.exchangeCode("a@b.co", "123456", "Pixel 8"));
    }

    @Test
    public void wrongOrExpiredCodeIsInvalidCodeAndStoresNothing() {
        server.enqueue(new MockResponse().setResponseCode(401)
                .setBody("{\"statusCode\":401,\"code\":\"INVALID_GRANT\"}"));
        assertEquals(AuthException.Kind.INVALID_CODE,
                kindOf(() -> repo.exchangeCode("a@b.co", "000000", "d")));
        assertFalse(store.hasSession());
    }

    @Test
    public void exchangeCodeRejectsShortCodeWithoutNetwork() {
        assertEquals(AuthException.Kind.BAD_INPUT, kindOf(() -> repo.exchangeCode("a@b.co", "12345", "d")));
        assertEquals(0, server.getRequestCount());
    }

    @Test
    public void exchangeCodeRateLimited() {
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "900")
                .setBody("{\"statusCode\":429,\"code\":\"AUTH_RATE_LIMITED\"}"));
        try {
            repo.exchangeCode("a@b.co", "123456", "d");
            fail();
        } catch (AuthException e) {
            assertEquals(AuthException.Kind.RATE_LIMITED, e.kind);
            assertEquals(900, e.retryAfterSeconds);
        }
    }

    @Test
    public void garbageSuccessBodyIsServerError() {
        server.enqueue(new MockResponse().setBody("{\"nope\":1}"));
        assertEquals(AuthException.Kind.SERVER, kindOf(() -> repo.exchangeCode("a@b.co", "123456", "d")));
        assertFalse(store.hasSession());
    }

    @Test
    public void deviceNameIsClampedTo100() {
        assertEquals(100, AuthRepository.clampDeviceName(new String(new char[300]).replace('\0', 'x')).length());
        assertEquals("Android", AuthRepository.clampDeviceName("  "));
    }

    @Test
    public void sanitizeCodeKeepsFirstSixDigits() {
        assertEquals("123456", AuthRepository.sanitizeCode(" 123-456 789"));
        assertEquals("", AuthRepository.sanitizeCode(null));
        assertTrue(AuthRepository.isValidCode("000000"));
        assertFalse(AuthRepository.isValidCode("00000"));
    }

    // ---- authenticated calls ----

    @Test
    public void executeSendsBearerAndExtraHeaders() throws Exception {
        signedIn();
        server.enqueue(new MockResponse().setBody("[]"));
        Map<String, String> h = new HashMap<>();
        h.put("x-workspace-id", "ws1");
        ApiResponse r = repo.execute("GET", "/public/v1/integrations", null, h);
        assertEquals(200, r.status);
        RecordedRequest req = server.takeRequest();
        assertEquals("Bearer old", req.getHeader("Authorization"));
        assertEquals("ws1", req.getHeader("x-workspace-id"));
    }

    @Test
    public void executeWithoutSessionThrowsNotSignedIn() {
        assertEquals(AuthException.Kind.NOT_SIGNED_IN, kindOf(() -> repo.execute("GET", "/x", null, null)));
        assertEquals(0, server.getRequestCount());
    }

    @Test
    public void expiredTokenRefreshesOnceAndRetriesWithSameHeaders() throws Exception {
        signedIn();
        server.enqueue(new MockResponse().setResponseCode(401)
                .setBody("{\"statusCode\":401,\"code\":\"TOKEN_EXPIRED\"}"));
        server.enqueue(new MockResponse().setBody(PAIR_2));
        server.enqueue(new MockResponse().setBody("{\"ok\":true}"));

        Map<String, String> h = new HashMap<>();
        h.put("Idempotency-Key", "k1");
        ApiResponse r = repo.execute("POST", "/public/v1/posts", "{}", h);

        assertEquals(200, r.status);
        RecordedRequest first = server.takeRequest();
        RecordedRequest refresh = server.takeRequest();
        RecordedRequest retry = server.takeRequest();
        assertEquals("Bearer old", first.getHeader("Authorization"));
        assertEquals("/auth/token", refresh.getPath());
        JSONObject rb = new JSONObject(refresh.getBody().readUtf8());
        assertEquals("refresh_token", rb.getString("grant_type"));
        assertEquals("ref1", rb.getString("refresh_token"));
        assertEquals("Bearer acc2", retry.getHeader("Authorization"));
        assertEquals("k1", retry.getHeader("Idempotency-Key"));
        assertEquals("ref2", store.refreshToken());
    }

    @Test
    public void almostExpiredTokenIsRefreshedBeforeTheCall() throws Exception {
        signedIn();
        now.addAndGet(900_000L - 10_000L); // 10 s left, inside the margin
        server.enqueue(new MockResponse().setBody(PAIR_2));
        server.enqueue(new MockResponse().setBody("{}"));
        repo.execute("GET", "/public/v1/integrations", null, null);
        assertEquals("/auth/token", server.takeRequest().getPath());
        assertEquals("Bearer acc2", server.takeRequest().getHeader("Authorization"));
    }

    @Test
    public void secondTokenExpiredIsReturnedNotLooped() throws Exception {
        signedIn();
        MockResponse expired = new MockResponse().setResponseCode(401)
                .setBody("{\"statusCode\":401,\"code\":\"TOKEN_EXPIRED\"}");
        server.enqueue(expired);
        server.enqueue(new MockResponse().setBody(PAIR_2));
        server.enqueue(expired);
        ApiResponse r = repo.execute("GET", "/x", null, null);
        assertEquals(401, r.status);
        assertEquals(3, server.getRequestCount());
    }

    @Test
    public void sessionRevokedClearsStoreAndFiresListener() {
        signedIn();
        server.enqueue(new MockResponse().setResponseCode(401)
                .setBody("{\"statusCode\":401,\"code\":\"SESSION_REVOKED\"}"));
        assertEquals(AuthException.Kind.SESSION_ENDED, kindOf(() -> repo.execute("GET", "/x", null, null)));
        assertFalse(store.hasSession());
        assertNull(store.email());
        assertEquals(1, forcedSignOuts.get());
    }

    @Test
    public void invalidApiKeyEndsTheSessionLikeARevokedOne() {
        signedIn();
        server.enqueue(new MockResponse().setResponseCode(401).setBody("{\"msg\":\"Invalid API key\"}"));
        assertEquals(AuthException.Kind.SESSION_ENDED, kindOf(() -> repo.execute("GET", "/x", null, null)));
        assertFalse(store.hasSession());
        assertEquals(1, forcedSignOuts.get());
    }

    @Test
    public void otherUnauthorizedAnswersAreReturnedAndKeepTheSession() throws Exception {
        signedIn();
        server.enqueue(new MockResponse().setResponseCode(401).setBody("{\"msg\":\"No API Key found\"}"));
        assertEquals(401, repo.execute("GET", "/x", null, null).status);
        assertTrue(store.hasSession());
        assertEquals(0, forcedSignOuts.get());
    }

    @Test
    public void refreshRefusedEndsSession() {
        signedIn();
        server.enqueue(new MockResponse().setResponseCode(401)
                .setBody("{\"statusCode\":401,\"code\":\"TOKEN_EXPIRED\"}"));
        server.enqueue(new MockResponse().setResponseCode(401)
                .setBody("{\"statusCode\":401,\"code\":\"INVALID_GRANT\"}"));
        assertEquals(AuthException.Kind.SESSION_ENDED, kindOf(() -> repo.execute("GET", "/x", null, null)));
        assertFalse(store.hasSession());
        assertEquals(1, forcedSignOuts.get());
    }

    @Test
    public void refreshWhileOfflineKeepsTheSession() throws Exception {
        signedIn();
        server.shutdown();
        assertEquals(AuthException.Kind.NETWORK, kindOf(() -> repo.refreshIfStale("old")));
        assertTrue(store.hasSession());
        assertEquals(0, forcedSignOuts.get());
    }

    @Test
    public void concurrentCallsShareOneRefresh() throws Exception {
        signedIn();
        final AtomicInteger refreshes = new AtomicInteger();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest req) {
                if ("/auth/token".equals(req.getPath())) {
                    refreshes.incrementAndGet();
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException ignored) {
                        // fall through
                    }
                    return new MockResponse().setBody(PAIR_2);
                }
                if ("Bearer old".equals(req.getHeader("Authorization"))) {
                    return new MockResponse().setResponseCode(401)
                            .setBody("{\"statusCode\":401,\"code\":\"TOKEN_EXPIRED\"}");
                }
                return new MockResponse().setBody("{}");
            }
        });

        int n = 6;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return repo.execute("GET", "/x", null, null).status;
            }));
        }
        go.countDown();
        for (Future<Integer> f : results) assertEquals(200, (int) f.get(10, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(1, refreshes.get());
        assertEquals("ref2", store.refreshToken());
    }

    // ---- sign out ----

    @Test
    public void signOutRevokesThenClears() throws Exception {
        signedIn();
        server.enqueue(new MockResponse().setResponseCode(204));
        assertTrue(repo.signOut());
        RecordedRequest r = server.takeRequest();
        assertEquals("/auth/token/revoke", r.getPath());
        assertEquals("ref1", new JSONObject(r.getBody().readUtf8()).getString("refresh_token"));
        assertFalse(store.hasSession());
        assertEquals(0, forcedSignOuts.get()); // user-initiated, not forced
    }

    @Test
    public void signOutOfflineStillClearsLocally() throws Exception {
        signedIn();
        server.shutdown();
        assertFalse(repo.signOut());
        assertFalse(store.hasSession());
    }
}
