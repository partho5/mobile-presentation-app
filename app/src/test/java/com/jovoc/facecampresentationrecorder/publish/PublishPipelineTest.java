package com.jovoc.facecampresentationrecorder.publish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jovoc.facecampresentationrecorder.auth.ApiClient;
import com.jovoc.facecampresentationrecorder.auth.AuthRepository;
import com.jovoc.facecampresentationrecorder.auth.KeyValueStore;
import com.jovoc.facecampresentationrecorder.auth.TokenStore;
import com.jovoc.facecampresentationrecorder.db.PublishJob;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;

/** The publish flow against a local MockWebServer: uploads once, posts once, however it fails. */
public class PublishPipelineTest {

    private static final String UPLOAD_OK =
            "{\"id\":\"m1\",\"name\":\"x.mp4\",\"path\":\"https://cdn.test/x.mp4\"}";
    private static final String POST_OK = "[{\"postId\":\"p1\",\"integration\":\"c1\"}]";
    private static final String PAIR_2 =
            "{\"access_token\":\"acc2\",\"token_type\":\"Bearer\",\"expires_in\":900,"
                    + "\"refresh_token\":\"ref2\",\"session_id\":\"sid\"}";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private MockWebServer server;
    private TokenStore tokens;
    private AuthRepository repo;
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final AtomicInteger forcedSignOuts = new AtomicInteger();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final Map<Long, PublishJob> rows = new HashMap<>();
    private final List<Integer> progressSeen = new ArrayList<>();
    private PublishPipeline pipeline;
    private File video;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        tokens = new TokenStore(new KeyValueStore() {
            private final Map<String, String> d = new HashMap<>();

            @Override
            public synchronized String get(String key) {
                return d.get(key);
            }

            @Override
            public synchronized void putAll(Map<String, String> values) {
                d.putAll(values);
            }

            @Override
            public synchronized void clear() {
                d.clear();
            }
        });
        tokens.saveTokens("acc1", "ref1", "sid", 900, now.get());
        // No silent OkHttp retry: a dropped connection must reach the pipeline, as it would
        // after the client's own retry also failed.
        OkHttpClient http = new OkHttpClient.Builder().retryOnConnectionFailure(false)
                .readTimeout(5, TimeUnit.SECONDS).build();
        repo = new AuthRepository(new ApiClient(server.url("/").toString(), http, true), tokens, now::get);
        repo.setForcedSignOutListener(forcedSignOuts::incrementAndGet);

        video = tmp.newFile("demo.mp4");
        try (FileOutputStream out = new FileOutputStream(video)) {
            out.write(new byte[300 * 1024]);
        }

        pipeline = new PublishPipeline(new PublishApi(repo), new PublishPipeline.Store() {
            @Override
            public PublishJob load(long id) {
                return rows.get(id);
            }

            @Override
            public void save(PublishJob job) {
                if (rows.containsKey(job.id)) rows.put(job.id, job);
            }
        }, job -> progressSeen.add(job.progress), cancelled::get, 3, now::get);
    }

    @After
    public void tearDown() throws Exception {
        try {
            server.shutdown();
        } catch (IOException ignored) {
            // already down
        }
    }

    private PublishJob newJob() {
        PublishJob j = new PublishJob();
        j.id = 7;
        j.filePath = video.getAbsolutePath();
        j.fileName = video.getName();
        j.caption = "hi";
        j.mode = PublishJob.MODE_NOW;
        j.status = PublishJob.QUEUED;
        j.idempotencyKey = "key-1";
        j.channelsJson = Channel.toJson(Arrays.asList(new Channel("c1", "YT", "youtube", null, false)));
        rows.put(j.id, j);
        return j;
    }

    private PublishPipeline.Outcome run(int attempt) {
        return pipeline.run(7, attempt);
    }

    // ---- happy path ----

    @Test
    public void uploadsThenPostsWithTheKeyAndFinishes() throws Exception {
        PublishJob job = newJob();
        server.enqueue(new MockResponse().setBody(UPLOAD_OK));
        server.enqueue(new MockResponse().setBody(POST_OK));

        assertEquals(PublishPipeline.Outcome.DONE, run(0));

        RecordedRequest up = server.takeRequest();
        assertEquals("/public/v1/upload", up.getPath());
        assertEquals("Bearer acc1", up.getHeader("Authorization"));
        assertTrue(up.getHeader("Content-Type").startsWith("multipart/form-data"));
        assertTrue(up.getBody().size() > 300 * 1024);

        RecordedRequest post = server.takeRequest();
        assertEquals("/public/v1/posts", post.getPath());
        assertEquals("key-1", post.getHeader("Idempotency-Key"));
        assertEquals("Bearer acc1", post.getHeader("Authorization"));
        assertTrue(post.getBody().readUtf8().contains("\"id\":\"m1\""));

        PublishJob saved = rows.get(7L);
        assertEquals(PublishJob.DONE, saved.status);
        assertEquals("m1", saved.mediaId);
        assertEquals("p1", saved.postIds);
        assertEquals(100, saved.progress);
        assertTrue(progressSeen.contains(100));
        assertNotNull(job);
    }

    // ---- dropped connection: no duplicate post, no second upload ----

    @Test
    public void aJobKeepsItsOwnWorkspace_evenAfterTheDevicePickChanged() throws Exception {
        PublishJob job = newJob();
        job.workspaceId = "w-A";
        tokens.saveWorkspace("w-B", "Beta");
        server.enqueue(new MockResponse().setBody(UPLOAD_OK));
        server.enqueue(new MockResponse().setBody(POST_OK));

        assertEquals(PublishPipeline.Outcome.DONE, run(0));

        assertEquals("w-A", server.takeRequest().getHeader("x-workspace-id")); // upload
        assertEquals("w-A", server.takeRequest().getHeader("x-workspace-id")); // post
        assertEquals("w-B", tokens.workspaceId());
    }

    @Test
    public void aJobWithoutWorkspace_followsTheDevicePick() throws Exception {
        newJob();
        tokens.saveWorkspace("w-B", "Beta");
        server.enqueue(new MockResponse().setBody(UPLOAD_OK));
        server.enqueue(new MockResponse().setBody(POST_OK));

        assertEquals(PublishPipeline.Outcome.DONE, run(0));

        assertEquals("w-B", server.takeRequest().getHeader("x-workspace-id"));
    }

    @Test
    public void forbiddenWorkspaceFailsTheJobWithoutRetry() throws Exception {
        PublishJob job = newJob();
        job.workspaceId = "w-gone";
        server.enqueue(new MockResponse().setResponseCode(403)
                .setBody("{\"statusCode\":403,\"code\":\"WORKSPACE_FORBIDDEN\"}"));

        assertEquals(PublishPipeline.Outcome.FAILED, run(0));
        assertEquals("WORKSPACE_FORBIDDEN", rows.get(7L).errorKind);
        assertEquals(1, server.getRequestCount());
    }

    @Test
    public void droppedConnectionOnPostRetriesWithTheSameKeyAndSkipsTheUpload() throws Exception {
        newJob();
        server.enqueue(new MockResponse().setBody(UPLOAD_OK));
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));

        assertEquals(PublishPipeline.Outcome.RETRY, run(0));
        PublishJob afterDrop = rows.get(7L);
        assertEquals(PublishJob.QUEUED, afterDrop.status);
        assertEquals("m1", afterDrop.mediaId);
        assertEquals("key-1", afterDrop.idempotencyKey);

        server.enqueue(new MockResponse().setBody(POST_OK)); // the server replays / creates once
        assertEquals(PublishPipeline.Outcome.DONE, run(1));

        server.takeRequest(); // upload
        String key1 = server.takeRequest().getHeader("Idempotency-Key");
        String key2 = server.takeRequest().getHeader("Idempotency-Key");
        assertEquals("key-1", key1);
        assertEquals(key1, key2);
        assertEquals(3, server.getRequestCount()); // one upload only
        assertEquals(PublishJob.DONE, rows.get(7L).status);
    }

    @Test
    public void droppedConnectionDuringUploadRetriesAndUploadsAgain() throws Exception {
        newJob();
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
        assertEquals(PublishPipeline.Outcome.RETRY, run(0));
        assertNull(rows.get(7L).mediaId);

        server.enqueue(new MockResponse().setBody(UPLOAD_OK));
        server.enqueue(new MockResponse().setBody(POST_OK));
        assertEquals(PublishPipeline.Outcome.DONE, run(1));
    }

    @Test
    public void serverErrorOnPostRetriesWithTheSameKeyUntilTheAttemptsRunOut() throws Exception {
        newJob();
        server.enqueue(new MockResponse().setBody(UPLOAD_OK));
        server.enqueue(new MockResponse().setResponseCode(502));
        server.enqueue(new MockResponse().setResponseCode(502));
        server.enqueue(new MockResponse().setResponseCode(502));

        assertEquals(PublishPipeline.Outcome.RETRY, run(0));
        assertEquals(PublishPipeline.Outcome.RETRY, run(1));
        assertEquals(PublishPipeline.Outcome.FAILED, run(2)); // third and last attempt

        PublishJob j = rows.get(7L);
        assertEquals(PublishJob.FAILED, j.status);
        assertEquals("SERVER", j.errorKind);
        assertEquals("key-1", j.idempotencyKey); // a 5xx is never stored by the server
    }

    @Test
    public void inProgressAnswerIsRetriedWithTheSameKey() throws Exception {
        newJob();
        server.enqueue(new MockResponse().setBody(UPLOAD_OK));
        server.enqueue(new MockResponse().setResponseCode(409)
                .setBody("{\"statusCode\":409,\"code\":\"IDEMPOTENCY_IN_PROGRESS\"}"));
        assertEquals(PublishPipeline.Outcome.RETRY, run(0));
        assertEquals("key-1", rows.get(7L).idempotencyKey);
    }

    // ---- definitive refusals ----

    @Test
    public void insufficientCreditsFailsAndRotatesTheKeySoARetryIsNotReplayed() throws Exception {
        newJob();
        server.enqueue(new MockResponse().setBody(UPLOAD_OK));
        server.enqueue(new MockResponse().setResponseCode(402).setBody(
                "{\"statusCode\":402,\"code\":\"INSUFFICIENT_CREDITS\","
                        + "\"message\":\"This needs 5 credits and you have 2. Top up to continue.\"}"));

        assertEquals(PublishPipeline.Outcome.FAILED, run(0));
        PublishJob failed = rows.get(7L);
        assertEquals("INSUFFICIENT_CREDITS", failed.errorKind);
        assertTrue(failed.errorDetail.contains("5 credits"));
        assertNotEquals("key-1", failed.idempotencyKey);
        assertEquals("m1", failed.mediaId); // the video stays uploaded

        // After a top-up the user retries: new key, no new upload.
        String newKey = failed.idempotencyKey;
        failed.status = PublishJob.QUEUED;
        server.enqueue(new MockResponse().setBody(POST_OK));
        assertEquals(PublishPipeline.Outcome.DONE, run(0));
        server.takeRequest();
        server.takeRequest();
        assertEquals(newKey, server.takeRequest().getHeader("Idempotency-Key"));
        assertEquals(3, server.getRequestCount());
    }

    @Test
    public void validationErrorIsShownAndIsFinal() throws Exception {
        newJob();
        server.enqueue(new MockResponse().setBody(UPLOAD_OK));
        server.enqueue(new MockResponse().setResponseCode(400).setBody(
                "{\"statusCode\":400,\"provider\":\"x\",\"name\":\"X\",\"message\":\"post is too long\"}"));
        assertEquals(PublishPipeline.Outcome.FAILED, run(0));
        assertEquals("REJECTED", rows.get(7L).errorKind);
        assertEquals("post is too long", rows.get(7L).errorDetail);
    }

    @Test
    public void emptyPostListIsAFailureNotASuccess() throws Exception {
        newJob();
        server.enqueue(new MockResponse().setBody(UPLOAD_OK));
        server.enqueue(new MockResponse().setBody("[]"));
        assertEquals(PublishPipeline.Outcome.FAILED, run(0));
        assertEquals("REJECTED", rows.get(7L).errorKind);
    }

    @Test
    public void missingOrOversizedFileFailsWithoutNetwork() throws Exception {
        newJob();
        assertTrue(video.delete());
        assertEquals(PublishPipeline.Outcome.FAILED, run(0));
        assertEquals("FILE", rows.get(7L).errorKind);
        assertEquals(0, server.getRequestCount());
    }

    // ---- cancel ----

    @Test
    public void cancelledBeforeStartSendsNothing() {
        newJob();
        cancelled.set(true);
        assertEquals(PublishPipeline.Outcome.GONE, run(0));
        assertEquals(0, server.getRequestCount());
    }

    @Test
    public void jobDeletedWhileUploadingNeverPosts() throws Exception {
        newJob();
        server.enqueue(new MockResponse().setBody(UPLOAD_OK));
        pipeline = new PublishPipeline(new PublishApi(repo), new PublishPipeline.Store() {
            @Override
            public PublishJob load(long id) {
                return rows.get(id);
            }

            @Override
            public void save(PublishJob job) {
                if (rows.containsKey(job.id)) rows.put(job.id, job);
            }
        }, job -> {
            if (job.mediaId != null) rows.remove(job.id); // the user cancelled just after the upload
        }, cancelled::get, 3, now::get);

        assertEquals(PublishPipeline.Outcome.GONE, run(0));
        assertEquals(1, server.getRequestCount());
    }

    @Test
    public void cancelFlagAbortsAnUploadInFlight() {
        newJob();
        server.enqueue(new MockResponse().setBody(UPLOAD_OK));
        pipeline = new PublishPipeline(new PublishApi(repo), new PublishPipeline.Store() {
            @Override
            public PublishJob load(long id) {
                return rows.get(id);
            }

            @Override
            public void save(PublishJob job) {
                if (rows.containsKey(job.id)) rows.put(job.id, job);
            }
        }, job -> {
            if (PublishJob.UPLOADING.equals(job.status)) cancelled.set(true);
        }, cancelled::get, 3, now::get);

        assertEquals(PublishPipeline.Outcome.GONE, run(0));
        assertNull(rows.get(7L).mediaId);
        assertFalse(PublishJob.DONE.equals(rows.get(7L).status));
    }

    // ---- session ----

    @Test
    public void expiredTokenIsRefreshedAndTheUploadRetriedOnce() throws Exception {
        newJob();
        server.enqueue(new MockResponse().setResponseCode(401)
                .setBody("{\"statusCode\":401,\"code\":\"TOKEN_EXPIRED\"}"));
        server.enqueue(new MockResponse().setBody(PAIR_2)); // refresh
        server.enqueue(new MockResponse().setBody(UPLOAD_OK));
        server.enqueue(new MockResponse().setBody(POST_OK));

        assertEquals(PublishPipeline.Outcome.DONE, run(0));
        assertEquals("Bearer acc1", server.takeRequest().getHeader("Authorization"));
        assertEquals("/auth/token", server.takeRequest().getPath());
        assertEquals("Bearer acc2", server.takeRequest().getHeader("Authorization"));
        assertEquals("Bearer acc2", server.takeRequest().getHeader("Authorization"));
    }

    @Test
    public void revokedSessionFailsTheJobAndSignsOut() {
        newJob();
        server.enqueue(new MockResponse().setResponseCode(401)
                .setBody("{\"statusCode\":401,\"code\":\"SESSION_REVOKED\"}"));
        assertEquals(PublishPipeline.Outcome.FAILED, run(0));
        assertEquals("SIGNED_OUT", rows.get(7L).errorKind);
        assertEquals(1, forcedSignOuts.get());
        assertFalse(tokens.hasSession());
    }
}
