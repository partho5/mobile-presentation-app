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

    @Test
    public void autoPublish_sendsTheGuidesBodyAndHeaders_andReadsPostIdsAndChannels() throws Exception {
        com.jovoc.facecampresentationrecorder.db.PublishJob job =
                new com.jovoc.facecampresentationrecorder.db.PublishJob();
        job.workspaceId = "w-job";
        job.idempotencyKey = "11111111-2222-3333-4444-555555555555";
        job.fileName = "Lecture 1_9x16.mp4";
        job.mediaId = "m1";
        job.mediaPath = "https://cdn.example.com/m1.mp4";
        server.enqueue(json(201, "[{\"postId\":\"p1\",\"integration\":\"c1\"},"
                + "{\"postId\":\"p2\",\"integration\":\"c2\"}]"));

        PublishApi.Posted posted = api.autoPublish(job);

        assertEquals(java.util.Arrays.asList("p1", "p2"), posted.ids);
        assertEquals(java.util.Arrays.asList("c1", "c2"), posted.integrations);
        RecordedRequest r = server.takeRequest();
        assertEquals("POST", r.getMethod());
        assertEquals("/public/v1/auto-publish", r.getPath());
        assertEquals("Bearer acc", r.getHeader("Authorization"));
        assertEquals("w-job", r.getHeader("x-workspace-id"));
        assertEquals("11111111-2222-3333-4444-555555555555", r.getHeader("Idempotency-Key"));
        assertTrue(r.getHeader("Content-Type").startsWith("application/json"));
        org.json.JSONObject body = new org.json.JSONObject(r.getBody().readUtf8());
        assertEquals("m1", body.getJSONObject("media").getString("id"));
        assertEquals("https://cdn.example.com/m1.mp4", body.getJSONObject("media").getString("path"));
        assertEquals("Lecture 1", body.getString("title"));
        assertEquals(2, body.length());
    }

    @Test
    public void postStatus_asksWithCommaSeparatedIds_andTheJobsWorkspace() throws Exception {
        server.enqueue(json(200, "[{\"id\":\"a\",\"state\":\"PUBLISHED\","
                + "\"publishDate\":\"2026-10-14T09:30:00.000Z\",\"releaseURL\":\"https://y.test/w\"}]"));
        java.util.List<PostStatus> out = api.postStatus(java.util.Arrays.asList("a", "b"), "w-job");
        assertEquals(1, out.size());
        assertEquals(PostStatus.State.PUBLISHED, out.get(0).state);
        RecordedRequest r = server.takeRequest();
        assertEquals("GET", r.getMethod());
        assertEquals("/public/v1/posts/status?ids=a,b", r.getPath());
        assertEquals("w-job", r.getHeader("x-workspace-id"));
        assertEquals("Bearer acc", r.getHeader("Authorization"));
    }

    @Test
    public void postStatus_acceptsFiftyIds_andNeverSendsMore() throws Exception {
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < PublishApi.MAX_STATUS_IDS; i++) ids.add("p" + i);
        server.enqueue(json(200, "[]"));
        assertTrue(api.postStatus(ids, null).isEmpty());
        ids.add("one-too-many");
        try {
            api.postStatus(ids, null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // the server would answer 400 INVALID_IDS; the app never asks
        }
        try {
            api.postStatus(new java.util.ArrayList<>(), null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // an empty list is also INVALID_IDS on the server
        }
    }

    @Test
    public void postStatus_invalidIds_isARefusal() {
        server.enqueue(json(400, "{\"statusCode\":400,\"code\":\"INVALID_IDS\","
                + "\"message\":\"ids must name 1 to 50 posts\"}"));
        try {
            api.postStatus(java.util.Collections.singletonList("a"), null);
            fail("expected PublishException");
        } catch (PublishException e) {
            assertEquals(PublishException.Kind.REJECTED, e.kind);
            assertEquals(400, e.status);
        }
    }
}
