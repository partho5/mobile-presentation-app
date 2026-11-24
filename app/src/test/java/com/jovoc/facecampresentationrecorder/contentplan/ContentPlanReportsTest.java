package com.jovoc.facecampresentationrecorder.contentplan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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

public class ContentPlanReportsTest {

    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final long NOW = 100 * DAY;

    private MockWebServer server;
    private ContentPlanApi api;
    private ContentPlanStore store;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        TokenStore tokens = new TokenStore(new MapStore());
        AuthRepository repo = new AuthRepository(new ApiClient(server.url("/").toString(), true), tokens, () -> 1_000_000L);
        tokens.saveTokens("acc", "ref", "sid", 900, 1_000_000L);
        tokens.saveIdentity("a@b.co", "Pixel 8");
        api = new ContentPlanApi(repo);
        store = new ContentPlanStore(new ContentPlanStoreTest.MapStore());
    }

    @After
    public void tearDown() throws IOException {
        server.shutdown();
    }

    private static MockResponse json(int code, String body) {
        return new MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body);
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

    @Test
    public void success_removesThePending() {
        store.savePendingRecorded("d1", "w1", NOW - 1000);
        server.enqueue(json(200, "{\"id\":\"d1\",\"status\":\"RECORDED\"}"));
        ContentPlanReports.flush(store, api, NOW);
        assertTrue(store.pendingRecorded().isEmpty());
        assertEquals(1, server.getRequestCount());
    }

    @Test
    public void networkError_keepsThePending() throws Exception {
        store.savePendingRecorded("d1", "w1", NOW - 1000);
        server.shutdown(); // nothing listens any more
        ContentPlanReports.flush(store, api, NOW);
        assertEquals(1, store.pendingRecorded().size());
    }

    @Test
    public void serverError_keepsThePending() {
        store.savePendingRecorded("d1", "w1", NOW - 1000);
        server.enqueue(json(500, "{}"));
        ContentPlanReports.flush(store, api, NOW);
        assertEquals(1, store.pendingRecorded().size());
    }

    @Test
    public void notFound_removesThePending() {
        store.savePendingRecorded("d1", "w1", NOW - 1000);
        server.enqueue(json(404, "{\"code\":\"DAY_NOT_FOUND\"}"));
        ContentPlanReports.flush(store, api, NOW);
        assertTrue(store.pendingRecorded().isEmpty());
    }

    @Test
    public void oldItems_areDroppedWithoutASend() {
        store.savePendingRecorded("old", "w1", NOW - 15 * DAY);
        store.savePendingRecorded("fresh", "w1", NOW - DAY);
        server.enqueue(json(200, "{\"id\":\"fresh\",\"status\":\"RECORDED\"}"));
        ContentPlanReports.flush(store, api, NOW);
        assertTrue(store.pendingRecorded().isEmpty());
        assertEquals(1, server.getRequestCount());
    }
}
