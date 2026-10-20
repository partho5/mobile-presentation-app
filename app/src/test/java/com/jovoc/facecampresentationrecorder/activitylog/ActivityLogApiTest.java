package com.jovoc.facecampresentationrecorder.activitylog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jovoc.facecampresentationrecorder.auth.ApiClient;
import com.jovoc.facecampresentationrecorder.auth.AuthRepository;
import com.jovoc.facecampresentationrecorder.auth.KeyValueStore;
import com.jovoc.facecampresentationrecorder.auth.TokenStore;
import com.jovoc.facecampresentationrecorder.db.ActivityEvent;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** Sends batches to a local MockWebServer; never touches the real backend. */
public class ActivityLogApiTest {

    private static final String BATCH = "11111111-1111-4111-8111-111111111111";
    private static final String DEVICE = "22222222-2222-5222-8222-222222222222";

    private MockWebServer server;
    private ApiClient client;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new ApiClient(server.url("/").toString(), true);
    }

    @After
    public void tearDown() throws Exception {
        try {
            server.shutdown();
        } catch (Exception ignored) {
            // already shut down by the test
        }
    }

    private static ActivityEvent row(long ts, String action, String meta) {
        ActivityEvent e = new ActivityEvent();
        e.ts = ts;
        e.tz = 360;
        e.session = "sess1";
        e.action = action;
        e.metaJson = meta;
        return e;
    }

    private static List<ActivityEvent> rows() {
        List<ActivityEvent> list = new ArrayList<>();
        list.add(row(1_761_000_000_000L, "app_open", null));
        list.add(row(1_761_000_005_000L, "record_stop", "{\"duration_ms\":92000,\"source\":\"button\"}"));
        return list;
    }

    private static final class MapStore implements KeyValueStore {
        final Map<String, String> data = new HashMap<>();

        @Override
        public String get(String key) {
            return data.get(key);
        }

        @Override
        public void putAll(Map<String, String> values) {
            data.putAll(values);
        }

        @Override
        public void clear() {
            data.clear();
        }
    }

    private AuthRepository signedInRepo() {
        TokenStore store = new TokenStore(new MapStore());
        long now = 1_000_000L;
        store.saveTokens("acc-token", "ref-token", "sid", 900, now);
        return new AuthRepository(client, store, () -> now);
    }

    @Test
    public void bodyFollowsTheBackendContract() throws Exception {
        String body = ActivityLogApi.buildBody(BATCH, DEVICE, "1.0", 4, "Android 14 / SDK 34", "Google Pixel 7", rows());
        JSONObject o = new JSONObject(body);
        assertEquals(BATCH, o.getString("batchId"));
        assertEquals(DEVICE, o.getString("deviceId"));
        assertEquals("1.0", o.getJSONObject("app").getString("version"));
        assertEquals(4, o.getJSONObject("app").getInt("build"));
        assertEquals("Google Pixel 7", o.getJSONObject("device").getString("model"));
        JSONArray events = o.getJSONArray("events");
        assertEquals(2, events.length());
        JSONObject first = events.getJSONObject(0);
        assertEquals(1_761_000_000_000L, first.getLong("t"));
        assertEquals(360, first.getInt("tz"));
        assertEquals("sess1", first.getString("s"));
        assertEquals("app_open", first.getString("a"));
        assertTrue(!first.has("m"));
        assertEquals(92000, events.getJSONObject(1).getJSONObject("m").getInt("duration_ms"));
    }

    @Test
    public void unreadableDetailsCostTheDetailsNotTheEvent() throws Exception {
        List<ActivityEvent> one = Collections.singletonList(row(1_761_000_000_000L, "tap", "{not json"));
        JSONObject e = new JSONObject(ActivityLogApi.buildBody(BATCH, DEVICE, "1.0", 4, "os", "m", one))
                .getJSONArray("events").getJSONObject(0);
        assertEquals("tap", e.getString("a"));
        assertTrue(!e.has("m"));
    }

    @Test
    public void anonymousBatchIsPostedWithoutAnAuthorizationHeader() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(204));
        ActivityLogApi.Outcome outcome = new ActivityLogApi(client, null).send(BATCH, DEVICE, rows());
        assertEquals(ActivityLogApi.Outcome.SENT, outcome);
        RecordedRequest r = server.takeRequest();
        assertEquals("POST", r.getMethod());
        assertEquals("/activity/batches", r.getPath());
        assertNull(r.getHeader("Authorization"));
        assertEquals(BATCH, new JSONObject(r.getBody().readUtf8()).getString("batchId"));
    }

    @Test
    public void signedInBatchCarriesTheAccessToken() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(204));
        ActivityLogApi.Outcome outcome = new ActivityLogApi(client, signedInRepo()).send(BATCH, DEVICE, rows());
        assertEquals(ActivityLogApi.Outcome.SENT, outcome);
        assertEquals("Bearer acc-token", server.takeRequest().getHeader("Authorization"));
        assertEquals(1, server.getRequestCount());
    }

    @Test
    public void rejectedBatchesAreDroppedAndTransientFailuresAreRetried() {
        int[] drop = {400, 413};
        int[] retry = {404, 429, 500, 502, 503};
        for (int code : drop) {
            server.enqueue(new MockResponse().setResponseCode(code));
            assertEquals("HTTP " + code, ActivityLogApi.Outcome.DROP,
                    new ActivityLogApi(client, null).send(BATCH, DEVICE, rows()));
        }
        for (int code : retry) {
            server.enqueue(new MockResponse().setResponseCode(code));
            assertEquals("HTTP " + code, ActivityLogApi.Outcome.RETRY,
                    new ActivityLogApi(client, null).send(BATCH, DEVICE, rows()));
        }
    }

    @Test
    public void offlineIsRetried() throws Exception {
        server.shutdown();
        assertEquals(ActivityLogApi.Outcome.RETRY, new ActivityLogApi(client, null).send(BATCH, DEVICE, rows()));
    }
}
