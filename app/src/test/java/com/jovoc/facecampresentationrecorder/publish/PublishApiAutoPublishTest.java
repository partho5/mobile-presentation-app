package com.jovoc.facecampresentationrecorder.publish;

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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** PublishApi.autoPublishInfo against a local MockWebServer. */
public class PublishApiAutoPublishTest {

    private static final String CONFIGURED = "{\"configured\":true,\"setName\":\"Reels\","
            + "\"timing\":\"now\",\"channels\":[{\"id\":\"c1\",\"name\":\"YT\","
            + "\"identifier\":\"youtube\",\"picture\":null,\"disabled\":false}],"
            + "\"cost\":2,\"available\":10,\"setupUrl\":\"https://app.example.com/w/o1/settings?tab=sets\"}";

    private MockWebServer server;
    private TokenStore store;
    private PublishApi api;
    private final AtomicLong now = new AtomicLong(1_000_000L);

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        store = new TokenStore(new KeyValueStore() {
            private final Map<String, String> d = new HashMap<>();

            @Override
            public synchronized String get(String key) {
                return d.get(key);
            }

            @Override
            public synchronized void putAll(Map<String, String> values) {
                for (Map.Entry<String, String> e : values.entrySet()) {
                    if (e.getValue() == null) d.remove(e.getKey());
                    else d.put(e.getKey(), e.getValue());
                }
            }

            @Override
            public synchronized void clear() {
                d.clear();
            }
        });
        store.saveTokens("acc", "ref", "sid", 900, now.get());
        store.saveIdentity("a@b.co", "Pixel 8");
        AuthRepository repo = new AuthRepository(
                new ApiClient(server.url("/").toString(), true), store, now::get);
        api = new PublishApi(repo);
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
    }

    private static MockResponse json(int code, String body) {
        return new MockResponse().setResponseCode(code)
                .setHeader("Content-Type", "application/json").setBody(body);
    }

    private PublishException.Kind failureKind() {
        try {
            api.autoPublishInfo();
        } catch (PublishException e) {
            return e.kind;
        }
        fail("expected PublishException");
        return null;
    }

    @Test
    public void info_isReadFromTheRoute_withBearerAndThePickedWorkspace() throws Exception {
        store.saveWorkspace("w1", "Acme");
        server.enqueue(json(200, CONFIGURED));
        AutoPublishInfo i = api.autoPublishInfo();
        assertTrue(i.configured);
        assertEquals(2L, i.cost);
        assertEquals(10L, i.available);
        RecordedRequest r = server.takeRequest();
        assertEquals("GET", r.getMethod());
        assertEquals("/public/v1/auto-publish", r.getPath());
        assertEquals("Bearer acc", r.getHeader("Authorization"));
        assertEquals("w1", r.getHeader("x-workspace-id"));
    }

    @Test
    public void info_forAJobsWorkspace_overridesThePick() throws Exception {
        store.saveWorkspace("w-now", "Now");
        server.enqueue(json(200, CONFIGURED));
        api.autoPublishInfo("w-job");
        assertEquals("w-job", server.takeRequest().getHeader("x-workspace-id"));
    }

    @Test
    public void info_withoutAnyWorkspace_sendsNoWorkspaceHeader() throws Exception {
        server.enqueue(json(200, CONFIGURED));
        api.autoPublishInfo();
        assertNull(server.takeRequest().getHeader("x-workspace-id"));
    }

    @Test
    public void notConfigured_isAnAnswer_notAnError() throws Exception {
        server.enqueue(json(200, "{\"configured\":false,\"channels\":[],\"cost\":0,\"available\":5,"
                + "\"setupUrl\":\"https://app.example.com/w/o1/settings?tab=sets\"}"));
        assertFalse(api.autoPublishInfo().configured);
    }

    @Test
    public void serverError_andUnreadableBody_areServerFailures() {
        server.enqueue(json(500, "{}"));
        assertEquals(PublishException.Kind.SERVER, failureKind());
        server.enqueue(json(200, "not json"));
        assertEquals(PublishException.Kind.SERVER, failureKind());
    }

    @Test
    public void workspaceForbidden_isReported() {
        server.enqueue(json(403, "{\"statusCode\":403,\"code\":\"WORKSPACE_FORBIDDEN\"}"));
        assertEquals(PublishException.Kind.WORKSPACE_FORBIDDEN, failureKind());
    }
}
