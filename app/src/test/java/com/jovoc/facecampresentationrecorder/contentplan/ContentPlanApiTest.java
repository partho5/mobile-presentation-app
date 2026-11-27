package com.jovoc.facecampresentationrecorder.contentplan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import java.util.HashMap;
import java.util.Map;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** Against a local MockWebServer; never touches a real backend. */
public class ContentPlanApiTest {

    private MockWebServer server;
    private ContentPlanApi api;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        TokenStore store = new TokenStore(new MapStore());
        AuthRepository repo = new AuthRepository(new ApiClient(server.url("/").toString(), true), store, () -> 1_000_000L);
        store.saveTokens("acc", "ref", "sid", 900, 1_000_000L);
        store.saveIdentity("a@b.co", "Pixel 8");
        api = new ContentPlanApi(repo);
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
    public void week_getsThePathWithTheBrand_andReturnsTheRawBody() throws Exception {
        String body = "{\"status\":\"NONE\",\"today\":\"2026-11-23\",\"timezone\":\"UTC\",\"week\":null}";
        server.enqueue(json(200, body));
        assertEquals(body, api.week("w1"));
        RecordedRequest r = server.takeRequest();
        assertEquals("GET", r.getMethod());
        assertEquals(ContentPlanApi.PATH_WEEK, r.getPath());
        assertEquals("w1", r.getHeader("x-workspace-id"));
        assertEquals("Bearer acc", r.getHeader("Authorization"));
    }

    @Test
    public void stamp_getsThePathWithTheBrand_andParsesTheStamp() throws Exception {
        server.enqueue(json(200, "{\"stamp\":\"2026-11-27T10:00:00.000Z|3\"}"));
        assertEquals("2026-11-27T10:00:00.000Z|3", api.stamp("w1"));
        RecordedRequest r = server.takeRequest();
        assertEquals("GET", r.getMethod());
        assertEquals(ContentPlanApi.PATH_WEEK_STAMP, r.getPath());
        assertEquals("w1", r.getHeader("x-workspace-id"));
        assertEquals("Bearer acc", r.getHeader("Authorization"));
    }

    @Test
    public void stamp_badBody_isAServerFailure() throws Exception {
        String[] bad = {"not json", "{}", "{\"stamp\":\"\"}", "[]"};
        for (String body : bad) {
            server.enqueue(json(200, body));
            try {
                api.stamp("w1");
                fail("expected ContentPlanException for " + body);
            } catch (ContentPlanException e) {
                assertEquals(ContentPlanException.SERVER, e.code);
            }
        }
    }

    @Test
    public void stamp_404WhenTheRouteIsNotDeployed_isNotFound() throws Exception {
        server.enqueue(json(404, "{\"statusCode\":404}"));
        try {
            api.stamp("w1");
            fail("expected ContentPlanException");
        } catch (ContentPlanException e) {
            assertEquals(ContentPlanException.NOT_FOUND, e.code);
            assertFalse(e.retryable);
        }
    }

    @Test
    public void recorded_postsWithIdempotencyKeyAndBrand() throws Exception {
        server.enqueue(json(200, "{\"id\":\"d1\",\"status\":\"RECORDED\"}"));
        api.recorded("d1", "w1");
        RecordedRequest r = server.takeRequest();
        assertEquals("POST", r.getMethod());
        assertEquals("/public/v1/content-plan/days/d1/recorded", r.getPath());
        assertEquals("w1", r.getHeader("x-workspace-id"));
        assertEquals("plan-recorded-d1", r.getHeader("Idempotency-Key"));
        assertEquals("{}", r.getBody().readUtf8());
    }

    @Test
    public void recorded_404IsNotFound_andNotRetryable() throws Exception {
        server.enqueue(json(404, "{\"statusCode\":404,\"code\":\"DAY_NOT_FOUND\"}"));
        try {
            api.recorded("gone", "w1");
            fail("expected ContentPlanException");
        } catch (ContentPlanException e) {
            assertEquals(ContentPlanException.NOT_FOUND, e.code);
            assertFalse(e.retryable);
        }
    }

    @Test
    public void serverError_isRetryable() throws Exception {
        server.enqueue(json(500, "{}"));
        try {
            api.week("w1");
            fail("expected ContentPlanException");
        } catch (ContentPlanException e) {
            assertEquals(ContentPlanException.SERVER, e.code);
            assertTrue(e.retryable);
        }
    }
}
