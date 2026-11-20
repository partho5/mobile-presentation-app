package com.jovoc.facecampresentationrecorder.entitlements;

import static com.jovoc.facecampresentationrecorder.entitlements.TokenFixture.DAY;
import static com.jovoc.facecampresentationrecorder.entitlements.TokenFixture.NOW;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jovoc.facecampresentationrecorder.auth.ApiClient;
import com.jovoc.facecampresentationrecorder.auth.AuthRepository;
import com.jovoc.facecampresentationrecorder.auth.TokenStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** Fetches from a local MockWebServer into a real {@link EntitlementState}; never touches the real backend. */
public class EntitlementsRefresherTest {

    private static final String CATALOGUE = "{\"plans\":["
            + "{\"key\":\"free\",\"name\":\"Free\",\"monthlyPriceCents\":0,\"currency\":\"USD\"},"
            + "{\"key\":\"starter\",\"name\":\"Starter\",\"monthlyPriceCents\":500,\"currency\":\"USD\"},"
            + "{\"key\":\"pro\",\"name\":\"Pro Plus\",\"monthlyPriceCents\":1200,\"currency\":\"USD\"}],"
            + "\"features\":{\"crop_9_16\":\"free\",\"bg_replace\":\"pro\"}}";
    private static final long HOUR_MS = 60 * 60 * 1000L;

    private final TokenFixture fx = new TokenFixture();
    private final TokenFixture.MemoryKv prefsKv = new TokenFixture.MemoryKv();
    private final TokenStore tokens = new TokenStore(new TokenFixture.MemoryKv());
    private final AtomicLong nowMs = new AtomicLong(NOW * 1000);
    private final EntitlementState state = new EntitlementState(new EntitlementsPrefs(prefsKv), fx.keys(),
            nowMs::get, tokens::sessionId, () -> EntitlementsApi.brandInUse(tokens), Runnable::run);
    private final List<String> changes = new CopyOnWriteArrayList<>();
    private final List<ExecutorService> workers = new ArrayList<>();
    private MockWebServer server;
    private ApiClient client;
    private AuthRepository repo;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new ApiClient(server.url("/").toString(), true);
        repo = new AuthRepository(client, tokens, nowMs::get);
    }

    @After
    public void tearDown() {
        for (ExecutorService w : workers) w.shutdownNow();
        try {
            server.shutdown();
        } catch (Exception ignored) {
            // already shut down by the test
        }
    }

    private void signIn() {
        tokens.saveTokens("acc", "ref", "s1", 900, nowMs.get());
    }

    private EntitlementsRefresher refresher() {
        return refresher(EntitlementsRefresher.BURST_DELAYS_MS);
    }

    private EntitlementsRefresher refresher(long[] burstDelaysMs) {
        ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
        workers.add(worker);
        EntitlementsRefresher.PlanStore store = new EntitlementsRefresher.PlanStore() {
            @Override
            public boolean accept(String token, String catalogueJson, String workspaceId) {
                return state.accept(token, catalogueJson, workspaceId);
            }

            @Override
            public String plan() {
                return state.plan();
            }

            @Override
            public long lastFetchAtMs() {
                return state.lastFetchAtMs();
            }

            @Override
            public long tokenIssuedAtSec() {
                return state.tokenIssuedAtSec();
            }

            @Override
            public long tokenExpiresAtSec() {
                return state.tokenExpiresAtSec();
            }
        };
        return new EntitlementsRefresher(() -> new EntitlementsApi(client, repo), store, nowMs::get, worker,
                burstDelaysMs, (from, to) -> changes.add(from + ">" + to));
    }

    private String pro(String sid) {
        return fx.token("pro", sid, "crop_9_16", "floating_cam", "crop_4_5", "crop_1_1", "bg_replace", "bg_custom");
    }

    private String anonymousFree() {
        return fx.token("free", null, "crop_9_16", "floating_cam", "drawing_board", "slide_ink", "auto_publish");
    }

    private static MockResponse ok(String token) {
        return new MockResponse().setResponseCode(200).setBody("{\"token\":\"" + token + "\",\"plan\":\"x\","
                + "\"refreshAfter\":0,\"catalogue\":" + CATALOGUE + "}");
    }

    private static MockResponse error(int code, String errorCode) {
        return new MockResponse().setResponseCode(code).setBody("{\"statusCode\":" + code + ",\"code\":\"" + errorCode + "\"}");
    }

    private static void waitFor(BooleanSupplier condition) throws InterruptedException {
        long end = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < end) Thread.sleep(10);
        assertTrue(condition.getAsBoolean());
    }

    /** Returns once everything the worker was handed before this call has run. */
    private static void drain(ExecutorService worker) throws Exception {
        worker.submit(() -> { }).get(10, TimeUnit.SECONDS);
    }

    // ---- what a fetch does ----

    @Test
    public void aVerifiedAnswerIsStoredAndSentAuthenticatedWithTheWorkspace() throws Exception {
        signIn();
        tokens.saveWorkspace("w1", "Team");
        server.enqueue(ok(pro("s1")));
        server.enqueue(ok(pro("s1")));
        EntitlementsRefresher r = refresher();
        r.fetchOnce();

        RecordedRequest req = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(req);
        assertEquals("GET", req.getMethod());
        assertEquals("/entitlements", req.getPath());
        assertEquals("Bearer acc", req.getHeader("Authorization"));
        assertEquals("w1", req.getHeader("x-workspace-id"));
        assertTrue(state.has(Feature.BG_REPLACE));
        assertEquals("pro", state.plan());
        assertEquals("Pro Plus", state.planName());
        assertEquals(NOW * 1000, state.lastFetchAtMs());
        assertEquals(1200L, state.catalogue().plan("pro").monthlyPriceCents);
        assertEquals(Collections.singletonList("free>pro"), changes);

        r.fetchOnce(); // the same plan again is not a change
        assertEquals(Collections.singletonList("free>pro"), changes);
    }

    @Test
    public void noWorkspaceHeaderWithoutAPick() throws Exception {
        signIn();
        server.enqueue(ok(pro("s1")));
        refresher().fetchOnce();
        RecordedRequest req = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(req);
        assertEquals("Bearer acc", req.getHeader("Authorization"));
        assertNull(req.getHeader("x-workspace-id"));
    }

    @Test
    public void aTokenWithABadSignatureIsIgnored() {
        signIn();
        assertTrue(state.accept(fx.token("starter", "s1", "crop_9_16", "crop_4_5"), CATALOGUE));
        String stored = prefsKv.data.get("token");
        String forged = new TokenFixture().token("agency", "s1", "crop_9_16", "bg_replace");
        server.enqueue(ok(forged));
        refresher().fetchOnce();
        assertEquals(1, server.getRequestCount());
        assertEquals("starter", state.plan());
        assertFalse(state.has(Feature.BG_REPLACE));
        assertEquals(stored, prefsKv.data.get("token"));
        assertTrue(changes.isEmpty());
    }

    @Test
    public void everyFailureKeepsTheStoredToken() throws Exception {
        signIn();
        assertTrue(state.accept(pro("s1"), CATALOGUE));
        String stored = prefsKv.data.get("token");
        List<MockResponse> answers = Arrays.asList(
                error(401, "TOKEN_EXPIRED"),
                error(401, "SESSION_REVOKED"),
                error(403, "WORKSPACE_FORBIDDEN"),
                error(429, "AUTH_RATE_LIMITED"),
                error(500, "INTERNAL"),
                error(503, "UNAVAILABLE"),
                new MockResponse().setResponseCode(200).setBody("not json"),
                new MockResponse().setResponseCode(200).setBody("{\"plan\":\"agency\"}"),
                ok("garbage"));
        for (MockResponse a : answers) server.enqueue(a);
        EntitlementsRefresher r = refresher();
        for (int i = 0; i < answers.size(); i++) r.fetchOnce();

        assertEquals(answers.size(), server.getRequestCount());
        assertEquals("pro", state.plan());
        assertTrue(state.has(Feature.BG_REPLACE));
        assertEquals(stored, prefsKv.data.get("token"));
        assertTrue("a background call must not end the session", tokens.hasSession());
        assertTrue(changes.isEmpty());

        server.shutdown(); // offline
        r.fetchOnce();
        assertEquals("pro", state.plan());
        assertEquals(stored, prefsKv.data.get("token"));
    }

    @Test
    public void aWorkspaceTheAccountLostIsDroppedAndTheTokenKept() {
        signIn();
        tokens.saveWorkspace("w1", "Team");
        assertTrue(state.accept(pro("s1"), CATALOGUE));
        String stored = prefsKv.data.get("token");
        server.enqueue(error(403, "WORKSPACE_FORBIDDEN"));
        refresher().fetchOnce();

        assertNull("the next fetch uses the server's default", tokens.workspaceId());
        assertTrue(tokens.hasSession());
        assertEquals("pro", state.plan());
        assertEquals(stored, prefsKv.data.get("token"));
    }

    @Test
    public void signedOutSendsNoAuthorizationAndNoWorkspace() throws Exception {
        tokens.saveWorkspace("w1", "Team"); // a leftover pick must not leak into an anonymous call
        server.enqueue(ok(anonymousFree()));
        refresher().fetchOnce();

        RecordedRequest req = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(req);
        assertEquals("/entitlements", req.getPath());
        assertNull(req.getHeader("Authorization"));
        assertNull(req.getHeader("x-workspace-id"));
        assertEquals(1, server.getRequestCount());
        assertEquals("free", state.plan());
        assertEquals(NOW * 1000, state.lastFetchAtMs());
        assertEquals("Pro Plus", state.catalogue().plan("pro").name);
    }

    @Test
    public void signedInWithoutAUsableAccessTokenSendsNoEntitlementsRequest() throws Exception {
        signIn();
        nowMs.addAndGet(2 * HOUR_MS); // the access token has run out; renewing it is refused
        server.enqueue(error(401, "INVALID_GRANT"));
        refresher().fetchOnce();

        assertEquals(1, server.getRequestCount());
        RecordedRequest req = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(req);
        assertEquals("/auth/token", req.getPath());
        assertTrue("the quiet renewal must not end the session", tokens.hasSession());
        assertEquals("free", state.plan());
    }

    // ---- single in flight ----

    @Test
    public void triggersThatPileUpBehindARunningFetchBecomeOneMoreFetch() throws Exception {
        CountDownLatch firstArrived = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger seen = new AtomicInteger();
        String token = anonymousFree();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                if (seen.incrementAndGet() == 1) {
                    firstArrived.countDown();
                    release.await(5, TimeUnit.SECONDS);
                }
                return ok(token);
            }
        });
        EntitlementsRefresher r = refresher();

        assertTrue(r.requestRefresh());
        assertTrue(firstArrived.await(5, TimeUnit.SECONDS));
        assertTrue("the first waiting trigger is kept", r.requestRefresh());
        for (int i = 0; i < 5; i++) {
            assertFalse("later ones join it", r.requestRefresh());
        }
        release.countDown();
        drain(workers.get(0));

        assertEquals(2, server.getRequestCount());
        assertTrue(r.requestRefresh()); // idle again
        drain(workers.get(0));
        assertEquals(3, server.getRequestCount());
    }

    // ---- bursts ----

    @Test
    public void aBurstStopsAsSoonAsThePlanChanges() throws Exception {
        signIn();
        server.enqueue(ok(fx.token("free", "s1", "crop_9_16"))); // not yet
        server.enqueue(ok(pro("s1")));                           // the purchase arrived
        server.enqueue(ok(pro("s1")));                           // must never be asked for
        refresher(new long[]{0, 50, 100}).requestBurst();

        waitFor(() -> server.getRequestCount() >= 2);
        Thread.sleep(400);
        assertEquals(2, server.getRequestCount());
        assertEquals("pro", state.plan());
        assertEquals(Collections.singletonList("free>pro"), changes);
    }

    @Test
    public void aBurstThatSeesNoChangeTriesEveryStep() throws Exception {
        signIn();
        for (int i = 0; i < 3; i++) server.enqueue(ok(fx.token("free", "s1", "crop_9_16")));
        refresher(new long[]{0, 30, 60}).requestBurst();

        waitFor(() -> server.getRequestCount() >= 3);
        Thread.sleep(200);
        assertEquals(3, server.getRequestCount());
        assertTrue(changes.isEmpty());
    }

    // ---- when the foreground check fetches ----

    @Test
    public void dueWhenNothingWasFetchedYet() {
        EntitlementsRefresher r = refresher();
        assertTrue(r.isDue());
    }

    @Test
    public void notDueRightAfterAFetchDueAfterSixHours() {
        signIn();
        assertTrue(state.accept(pro("s1"), CATALOGUE));
        EntitlementsRefresher r = refresher();
        assertFalse(r.isDue());
        assertFalse(r.requestIfDue());
        nowMs.addAndGet(5 * HOUR_MS);
        assertFalse(r.isDue());
        nowMs.addAndGet(2 * HOUR_MS);
        assertTrue(r.isDue());
    }

    @Test
    public void dueOnceTheTokenIsPastHalfItsLife() {
        signIn();
        // Fetched just now, but issued 10 days ago and gone in 4: more than half its life is over.
        assertTrue(state.accept(fx.token("pro", "s1", NOW - 10 * DAY, NOW + 4 * DAY, "bg_replace"), CATALOGUE));
        assertTrue(refresher().isDue());
        assertTrue(state.accept(fx.token("pro", "s1", NOW - DAY, NOW + 13 * DAY, "bg_replace"), CATALOGUE));
        assertFalse(refresher().isDue());
    }

    @Test
    public void dueWhenTheClockIsBehindTheLastFetch() {
        signIn();
        assertTrue(state.accept(pro("s1"), CATALOGUE));
        nowMs.addAndGet(-HOUR_MS);
        assertTrue(refresher().isDue());
    }

    @Test
    public void aFailedAttemptIsNotRepeatedByTheForegroundCheckForFiveMinutes() {
        server.enqueue(error(503, "UNAVAILABLE"));
        EntitlementsRefresher r = refresher();
        assertTrue(r.isDue());
        r.fetchOnce();
        assertFalse(r.isDue());
        nowMs.addAndGet(EntitlementsRefresher.RETRY_GAP_MS - 1);
        assertFalse(r.isDue());
        nowMs.addAndGet(1);
        assertTrue(r.isDue());
    }
}
