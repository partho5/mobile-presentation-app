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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** The pin rules (plain) and the blocking pin against a local MockWebServer. */
public class BrandPinTest {

    private static final String SELF = "{\"id\":\"u1\",\"orgId\":\"w1\",\"role\":\"ADMIN\"}";
    private static final String BRANDS = "[{\"id\":\"w1\",\"name\":\"Acme\",\"users\":[{\"role\":\"ADMIN\",\"disabled\":false}]},"
            + "{\"id\":\"w2\",\"name\":\"Beta\",\"users\":[{\"role\":\"USER\",\"disabled\":false}]}]";

    private MockWebServer server;
    private TokenStore store;
    private AccountApi api;
    private final AtomicLong now = new AtomicLong(1_000_000L);

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        store = new TokenStore(new MapStore());
        AuthRepository repo = new AuthRepository(new ApiClient(server.url("/").toString(), true), store, now::get);
        store.saveTokens("acc", "ref", "sid", 900, now.get());
        api = new AccountApi(repo);
    }

    @After
    public void tearDown() throws IOException {
        server.shutdown();
    }

    private static MockResponse json(int code, String body) {
        return new MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body);
    }

    // ---- the rules ----

    @Test
    public void signInAlwaysPins() {
        assertTrue(BrandPin.shouldPin("old", true));
        assertTrue(BrandPin.shouldPin(null, true));
    }

    @Test
    public void startupPinsOnlyAnEmptyPick() {
        assertTrue(BrandPin.shouldPin(null, false));
        assertTrue(BrandPin.shouldPin("", false));
        assertFalse(BrandPin.shouldPin("w1", false));
    }

    @Test
    public void nameOfFindsTheBrandOrIsEmpty() {
        java.util.List<Workspace> list = Arrays.asList(
                new Workspace("w1", "Acme", "ADMIN"), new Workspace("w2", null, "USER"));
        assertEquals("Acme", BrandPin.nameOf(list, "w1"));
        assertEquals("", BrandPin.nameOf(list, "w2"));
        assertEquals("", BrandPin.nameOf(list, "w9"));
        assertEquals("", BrandPin.nameOf(Collections.emptyList(), "w1"));
    }

    @Test
    public void aNewJobKeepsItsOwnBrandOrTheStoredPick() {
        // A job keeps the brand it was made in after a switch: its own brand wins over the pick.
        assertEquals("w1", BrandPin.jobBrand("w1", "w2"));
        assertEquals("w2", BrandPin.jobBrand(null, "w2"));
        assertEquals("w2", BrandPin.jobBrand("", "w2"));
    }

    @Test
    public void aNewJobWithNoBrandStoredIsNeverSavedWithANullOrEmptyBrand() {
        // Null means "resolve it first"; the caller must not save the job with it.
        assertNull(BrandPin.jobBrand(null, null));
        assertNull(BrandPin.jobBrand("", ""));
        assertNull(BrandPin.jobBrand(null, ""));
    }

    @Test
    public void autoPublishStaysOffUntilABrandIsPickedAndKeepsItsAccess() {
        assertEquals("w1", BrandPin.autoPublishBrand("w1", false));
        // No pick yet: nothing starts (the recording is kept, the pick is asked for).
        assertNull(BrandPin.autoPublishBrand(null, false));
        assertNull(BrandPin.autoPublishBrand("", false));
        // The picked brand lost access: auto-publish stays off even with a pick.
        assertNull(BrandPin.autoPublishBrand("w1", true));
    }

    @Test
    public void lostAccessNamesOnlyTheBrandInUse() {
        assertEquals("Acme", BrandPin.lostBrandName("w1", "w1", "Acme"));
        // A job for another brand gets the unnamed wording.
        assertNull(BrandPin.lostBrandName("w2", "w1", "Acme"));
        // No name to show (missing, blank, or no job brand): no name.
        assertNull(BrandPin.lostBrandName("w1", "w1", null));
        assertNull(BrandPin.lostBrandName("w1", "w1", "   "));
        assertNull(BrandPin.lostBrandName(null, "w1", "Acme"));
        assertNull(BrandPin.lostBrandName("w1", null, "Acme"));
    }

    // ---- the blocking pin ----

    @Test
    public void signInPinsTheServerDefaultWithItsName() throws Exception {
        server.enqueue(json(200, SELF));
        server.enqueue(json(200, BRANDS));
        assertEquals("w1", BrandPin.resolve(api, store, true));
        assertEquals("w1", store.workspaceId());
        assertEquals("Acme", store.workspaceName());
        // The default is asked without any brand header: it is the discovery of the pin.
        assertNull(server.takeRequest().getHeader("x-workspace-id"));
    }

    @Test
    public void signInReplacesAnEarlierPick() throws Exception {
        store.saveWorkspace("w2", "Beta");
        server.enqueue(json(200, SELF));
        server.enqueue(json(200, BRANDS));
        assertEquals("w1", BrandPin.resolve(api, store, true));
        assertEquals("w1", store.workspaceId());
    }

    @Test
    public void anUnpinnedInstallIsPinnedOnceAndThenKeepsItsPick() throws Exception {
        server.enqueue(json(200, SELF));
        server.enqueue(json(200, BRANDS));
        assertEquals("w1", BrandPin.resolve(api, store, false));
        assertEquals(2, server.getRequestCount());

        assertEquals("w1", BrandPin.resolve(api, store, false));
        assertEquals(2, server.getRequestCount()); // no second look-up
    }

    @Test
    public void aPickIsKeptAtStartup() throws Exception {
        store.saveWorkspace("w2", "Beta");
        assertEquals("w2", BrandPin.resolve(api, store, false));
        assertEquals(0, server.getRequestCount());
    }

    @Test
    public void aFailedLookupSavesNothing() {
        server.enqueue(json(500, "{}"));
        try {
            BrandPin.resolve(api, store, false);
            fail("the lookup failed, so nothing is pinned");
        } catch (AccountException expected) {
            assertEquals(AccountException.Kind.SERVER, expected.kind);
        }
        assertNull(store.workspaceId());
    }

    @Test
    public void aLookupThatFindsNoListedBrandStillPinsTheIdWithNoName() throws Exception {
        server.enqueue(json(200, SELF));
        server.enqueue(json(200, "[]"));
        assertEquals("w1", BrandPin.resolve(api, store, false));
        assertEquals("", store.workspaceName());
    }

    /** In-memory store for these tests. */
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
