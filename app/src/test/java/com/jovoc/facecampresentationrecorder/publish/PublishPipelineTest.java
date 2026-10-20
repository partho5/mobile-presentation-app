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
        assertEquals("c1", saved.postIntegrations);
        assertNull(saved.remoteState);
        assertEquals(100, saved.progress);
        assertTrue(progressSeen.contains(100));
        assertNotNull(job);
    }

    @Test
    public void aRetryOfAChannelFailure_getsANewKey_andClearsTheOldAnswer() throws Exception {
        PublishJob job = newJob();
        job.status = PublishJob.DONE;
        job.postIds = "p1";
        job.postIntegrations = "c1";
        job.remoteState = PublishJob.REMOTE_ERROR;
        job.remoteChannel = "YT";
        job.remoteError = "too long";
        job.mediaId = "m1";
        job.mediaPath = "https://cdn.test/x.mp4";
        assertTrue(job.isRemoteFailed());

        job.resetForNewPublish("key-2");
        job.status = PublishJob.QUEUED;
        server.enqueue(new MockResponse().setBody("[{\"postId\":\"p9\",\"integration\":\"c1\"}]"));

        assertEquals(PublishPipeline.Outcome.DONE, run(0));
        assertEquals(1, server.getRequestCount()); // no second upload
        RecordedRequest post = server.takeRequest();
        assertEquals("key-2", post.getHeader("Idempotency-Key"));
        PublishJob saved = rows.get(7L);
        assertEquals("p9", saved.postIds);
        assertNull(saved.remoteState);
        assertNull(saved.remoteError);
        assertFalse(saved.isRemoteFailed());
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

    @Test
    public void throttlerRateLimitRetriesWithANewKeyBecauseTheServerStoredTheRefusal() throws Exception {
        PublishJob job = newJob();
        job.mediaId = "m1";
        job.mediaPath = "/m1.mp4";
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "600")
                .setBody("{\"statusCode\":429,\"message\":\"ThrottlerException: Too Many Requests\"}"));
        assertEquals(PublishPipeline.Outcome.RETRY, run(0));
        PublishJob queued = rows.get(7L);
        assertEquals(PublishJob.QUEUED, queued.status);
        assertEquals("RATE_LIMITED", queued.errorKind);
        assertNotEquals("key-1", queued.idempotencyKey);
        assertEquals("m1", queued.mediaId); // no second upload

        String rotated = queued.idempotencyKey;
        server.enqueue(new MockResponse().setBody(POST_OK));
        assertEquals(PublishPipeline.Outcome.DONE, run(1));
        assertEquals("key-1", server.takeRequest().getHeader("Idempotency-Key"));
        assertEquals(rotated, server.takeRequest().getHeader("Idempotency-Key"));
    }

    @Test
    public void authLayerRateLimitKeepsTheKeyBecauseNothingWasStoredForIt() throws Exception {
        PublishJob job = newJob();
        job.mediaId = "m1";
        job.mediaPath = "/m1.mp4";
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "60")
                .setBody("{\"statusCode\":429,\"code\":\"AUTH_RATE_LIMITED\"}"));
        assertEquals(PublishPipeline.Outcome.RETRY, run(0));
        assertEquals("key-1", rows.get(7L).idempotencyKey);
    }

    @Test
    public void throttledOnTheLastAttemptFailsWithANewKey() throws Exception {
        PublishJob job = newJob();
        job.mediaId = "m1";
        job.mediaPath = "/m1.mp4";
        server.enqueue(new MockResponse().setResponseCode(429)
                .setBody("{\"statusCode\":429,\"message\":\"ThrottlerException: Too Many Requests\"}"));
        assertEquals(PublishPipeline.Outcome.FAILED, run(2)); // maxAttempts is 3
        PublishJob failed = rows.get(7L);
        assertEquals("RATE_LIMITED", failed.errorKind);
        assertNotEquals("key-1", failed.idempotencyKey);
    }

    @Test
    public void unreadableUploadStreamIsRetriedAndUploadsAgain() throws Exception {
        newJob();
        server.enqueue(new MockResponse().setResponseCode(400).setBody(
                "{\"message\":\"Failed to read file\",\"error\":\"Bad Request\",\"statusCode\":400}"));
        assertEquals(PublishPipeline.Outcome.RETRY, run(0));
        assertNull(rows.get(7L).mediaId);
        server.enqueue(new MockResponse().setBody(UPLOAD_OK));
        server.enqueue(new MockResponse().setBody(POST_OK));
        assertEquals(PublishPipeline.Outcome.DONE, run(1));
    }

    @Test
    public void validationRefusalNamesTheChannelAndRotatesTheKey() throws Exception {
        PublishJob job = newJob();
        job.mediaId = "m1";
        job.mediaPath = "/m1.mp4";
        server.enqueue(new MockResponse().setResponseCode(400).setBody(
                "{\"statusCode\":400,\"provider\":\"youtube\",\"name\":\"My YouTube\","
                        + "\"message\":\"Title is required\"}"));
        assertEquals(PublishPipeline.Outcome.FAILED, run(0));
        PublishJob failed = rows.get(7L);
        assertEquals("REJECTED", failed.errorKind);
        assertEquals("My YouTube: Title is required", failed.errorDetail);
        assertNotEquals("key-1", failed.idempotencyKey);
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
        assertEquals("X: post is too long", rows.get(7L).errorDetail);
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

    @Test
    public void unauthorizedPostFailsButKeepsTheKeySoARetryCannotDoublePost() throws Exception {
        PublishJob job = newJob();
        job.mediaId = "m1"; // already uploaded: only the post call runs
        job.mediaPath = "/m1.mp4";
        server.enqueue(new MockResponse().setResponseCode(401).setBody("{\"statusCode\":401}"));

        assertEquals(PublishPipeline.Outcome.FAILED, run(0));
        PublishJob failed = rows.get(7L);
        assertEquals("SIGNED_OUT", failed.errorKind);
        // Nothing was stored for the key under a 401, so the same key is replayed after sign-in.
        assertEquals("key-1", failed.idempotencyKey);
    }

    // ---- auto-publish ----

    private static String preflight(boolean configured, String timing, int cost, int available) {
        return "{\"configured\":" + configured + ",\"setName\":\"Reels\",\"timing\":\"" + timing + "\","
                + "\"channels\":[{\"id\":\"c1\",\"name\":\"YT\",\"identifier\":\"youtube\","
                + "\"picture\":null,\"disabled\":false}],\"cost\":" + cost + ",\"available\":" + available
                + ",\"setupUrl\":\"https://app.test/w/o1/settings?tab=sets\"}";
    }

    private PublishJob newAutoJob() {
        PublishJob j = new PublishJob();
        j.id = 7;
        j.filePath = video.getAbsolutePath();
        j.fileName = "FaceCam_20261014_9x16.mp4";
        j.mode = PublishJob.MODE_AUTO;
        j.status = PublishJob.QUEUED;
        j.idempotencyKey = "key-auto";
        j.workspaceId = "w-A";
        rows.put(j.id, j);
        return j;
    }

    private PublishPipeline pipelineOnSameRows() {
        // A new object over the same stored rows: what a restarted process sees.
        return new PublishPipeline(new PublishApi(repo), new PublishPipeline.Store() {
            @Override
            public PublishJob load(long id) {
                return rows.get(id);
            }

            @Override
            public void save(PublishJob job) {
                if (rows.containsKey(job.id)) rows.put(job.id, job);
            }
        }, null, cancelled::get, 3, now::get);
    }

    @Test
    public void autoJobChecksThenUploadsThenPostsToTheAutoRoute() throws Exception {
        newAutoJob();
        server.enqueue(new MockResponse().setBody(preflight(true, "now", 2, 10)));
        server.enqueue(new MockResponse().setBody(UPLOAD_OK));
        server.enqueue(new MockResponse().setResponseCode(201).setBody(POST_OK));

        assertEquals(PublishPipeline.Outcome.DONE, run(0));

        RecordedRequest check = server.takeRequest();
        assertEquals("GET", check.getMethod());
        assertEquals("/public/v1/auto-publish", check.getPath());
        assertEquals("w-A", check.getHeader("x-workspace-id"));
        assertEquals("/public/v1/upload", server.takeRequest().getPath());
        RecordedRequest post = server.takeRequest();
        assertEquals("POST", post.getMethod());
        assertEquals("/public/v1/auto-publish", post.getPath());
        assertEquals("key-auto", post.getHeader("Idempotency-Key"));
        assertEquals("w-A", post.getHeader("x-workspace-id"));
        String body = post.getBody().readUtf8();
        assertTrue(body.contains("\"id\":\"m1\""));
        assertTrue(body.contains("\"title\":\"FaceCam_20261014\""));

        PublishJob saved = rows.get(7L);
        assertEquals(PublishJob.DONE, saved.status);
        assertEquals("p1", saved.postIds);
        assertEquals(PublishJob.MODE_AUTO, saved.mode);
    }

    @Test
    public void autoJobCancelledInsideTheUndoWindowUploadsAndPostsNothing() {
        PublishJob job = newAutoJob();
        // Released with a 30 s window: queued, deadline ahead. The user taps Cancel (row removed,
        // work cancelled); if the worker still got to run it finds nothing and sends nothing.
        job.scheduleAt = now.get() + 30_000L;
        assertEquals(30, AutoPublishUndo.secondsLeft(job, now.get()));
        rows.remove(7L);

        assertEquals(PublishPipeline.Outcome.GONE, run(0));
        assertEquals(0, server.getRequestCount());
    }

    @Test
    public void autoJobWithAnExpiredWindowRunsLikeAnyOther() throws Exception {
        PublishJob job = newAutoJob();
        job.scheduleAt = now.get() - 1_000L;
        server.enqueue(new MockResponse().setBody(preflight(true, "now", 2, 10)));
        server.enqueue(new MockResponse().setBody(UPLOAD_OK));
        server.enqueue(new MockResponse().setResponseCode(201).setBody(POST_OK));

        assertEquals(PublishPipeline.Outcome.DONE, run(0));
        assertEquals(3, server.getRequestCount());
    }

    @Test
    public void nextSlotPresetIsRememberedOnTheJob() throws Exception {
        newAutoJob();
        server.enqueue(new MockResponse().setBody(preflight(true, "next_slot", 0, 0)));
        server.enqueue(new MockResponse().setBody(UPLOAD_OK));
        server.enqueue(new MockResponse().setResponseCode(201).setBody(POST_OK));

        assertEquals(PublishPipeline.Outcome.DONE, run(0));
        assertEquals(PublishJob.MODE_AUTO_SLOT, rows.get(7L).mode);
        assertTrue(rows.get(7L).isAuto());
    }

    @Test
    public void preflightNotConfigured_failsWithoutUploading() throws Exception {
        newAutoJob();
        server.enqueue(new MockResponse().setBody(preflight(false, "now", 0, 5)));

        assertEquals(PublishPipeline.Outcome.FAILED, run(0));

        PublishJob failed = rows.get(7L);
        assertEquals("AUTO_NOT_CONFIGURED", failed.errorKind);
        // The server's setup link is not kept: the fix is the Auto upload screen.
        assertNull(failed.errorDetail);
        assertNull(failed.mediaId);
        assertEquals(1, server.getRequestCount()); // the GET only, no upload
    }

    @Test
    public void preflightShortOnCredits_failsWithoutUploadingAndKeepsTheNumbers() throws Exception {
        newAutoJob();
        server.enqueue(new MockResponse().setBody(preflight(true, "now", 5, 2)));

        assertEquals(PublishPipeline.Outcome.FAILED, run(0));

        PublishJob failed = rows.get(7L);
        assertEquals("CREDITS_SHORT", failed.errorKind);
        assertEquals("5|2", failed.errorDetail);
        assertEquals("key-auto", failed.idempotencyKey); // nothing was posted, nothing stored
        assertEquals(1, server.getRequestCount());

        // After a top-up the card's Retry runs the check again and then the whole flow once.
        failed.status = PublishJob.QUEUED;
        server.enqueue(new MockResponse().setBody(preflight(true, "now", 5, 20)));
        server.enqueue(new MockResponse().setBody(UPLOAD_OK));
        server.enqueue(new MockResponse().setResponseCode(201).setBody(POST_OK));
        assertEquals(PublishPipeline.Outcome.DONE, run(0));
        assertEquals(4, server.getRequestCount());
    }

    @Test
    public void preflightServerError_retriesWithoutUploading() throws Exception {
        newAutoJob();
        server.enqueue(new MockResponse().setResponseCode(503));

        assertEquals(PublishPipeline.Outcome.RETRY, run(0));
        assertEquals(PublishJob.QUEUED, rows.get(7L).status);
        assertEquals(1, server.getRequestCount());
    }

    @Test
    public void autoPostRefusedForCredits_rotatesTheKeyAndRetryPostsOnceWithoutUploadOrCheck() throws Exception {
        newAutoJob();
        server.enqueue(new MockResponse().setBody(preflight(true, "now", 2, 10)));
        server.enqueue(new MockResponse().setBody(UPLOAD_OK));
        server.enqueue(new MockResponse().setResponseCode(402).setBody(
                "{\"statusCode\":402,\"code\":\"INSUFFICIENT_CREDITS\","
                        + "\"message\":\"This needs 2 credits and you have 1.\"}"));

        assertEquals(PublishPipeline.Outcome.FAILED, run(0));
        PublishJob failed = rows.get(7L);
        assertEquals("INSUFFICIENT_CREDITS", failed.errorKind);
        assertNotEquals("key-auto", failed.idempotencyKey);
        assertEquals("m1", failed.mediaId);

        String rotated = failed.idempotencyKey;
        failed.status = PublishJob.QUEUED;
        server.enqueue(new MockResponse().setResponseCode(201).setBody(POST_OK));
        assertEquals(PublishPipeline.Outcome.DONE, run(0));

        server.takeRequest(); // check
        server.takeRequest(); // upload
        server.takeRequest(); // refused post
        RecordedRequest again = server.takeRequest();
        assertEquals("/public/v1/auto-publish", again.getPath());
        assertEquals("POST", again.getMethod());
        assertEquals(rotated, again.getHeader("Idempotency-Key"));
        assertEquals(4, server.getRequestCount());
    }

    @Test
    public void autoReplayAfterKill_reusesTheUploadAndTheKeyAndSkipsTheCheck() throws Exception {
        newAutoJob();
        server.enqueue(new MockResponse().setBody(preflight(true, "now", 2, 10)));
        server.enqueue(new MockResponse().setBody(UPLOAD_OK));
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
        assertEquals(PublishPipeline.Outcome.RETRY, run(0));
        PublishJob afterKill = rows.get(7L);
        assertEquals("m1", afterKill.mediaId);

        // The process died; a fresh pipeline picks the job up from the stored row.
        pipeline = pipelineOnSameRows();
        server.enqueue(new MockResponse().setResponseCode(201).setBody(POST_OK)); // replayed answer
        assertEquals(PublishPipeline.Outcome.DONE, run(1));

        assertEquals("GET", server.takeRequest().getMethod());
        assertEquals("/public/v1/upload", server.takeRequest().getPath());
        String first = server.takeRequest().getHeader("Idempotency-Key");
        RecordedRequest replay = server.takeRequest();
        assertEquals("/public/v1/auto-publish", replay.getPath());
        assertEquals("key-auto", first);
        assertEquals(first, replay.getHeader("Idempotency-Key"));
        assertEquals(4, server.getRequestCount()); // one check, one upload
    }

    @Test
    public void autoPostSaysNotConfigured_failsAndRotatesTheKey() throws Exception {
        newAutoJob();
        rows.get(7L).mediaId = "m1";
        rows.get(7L).mediaPath = "https://cdn.test/x.mp4";
        server.enqueue(new MockResponse().setResponseCode(409).setBody(
                "{\"statusCode\":409,\"code\":\"AUTO_PUBLISH_NOT_CONFIGURED\","
                        + "\"message\":\"Auto-publish is not set up for this workspace\"}"));

        assertEquals(PublishPipeline.Outcome.FAILED, run(0));
        PublishJob failed = rows.get(7L);
        assertEquals("AUTO_NOT_CONFIGURED", failed.errorKind);
        assertNotEquals("key-auto", failed.idempotencyKey);
        assertEquals(1, server.getRequestCount());
    }

    @Test
    public void autoPostSaysMediaNotFound_forgetsTheUploadSoRetryUploadsAgain() throws Exception {
        newAutoJob();
        rows.get(7L).mediaId = "gone";
        rows.get(7L).mediaPath = "https://cdn.test/gone.mp4";
        server.enqueue(new MockResponse().setResponseCode(404).setBody(
                "{\"statusCode\":404,\"code\":\"MEDIA_NOT_FOUND\",\"message\":\"Media not found\"}"));

        assertEquals(PublishPipeline.Outcome.FAILED, run(0));
        PublishJob failed = rows.get(7L);
        assertEquals("MEDIA_NOT_FOUND", failed.errorKind);
        assertNull(failed.mediaId);
        assertNull(failed.mediaPath);
    }

    @Test
    public void autoPostSaysMediaPurged_failsForGoodAndForgetsTheUpload() throws Exception {
        newAutoJob();
        rows.get(7L).mediaId = "gone";
        rows.get(7L).mediaPath = "https://cdn.test/gone.mp4";
        server.enqueue(new MockResponse().setResponseCode(410).setBody(
                "{\"statusCode\":410,\"code\":\"MEDIA_PURGED\",\"message\":\"removed\"}"));

        // Attempt 0 of 3: still FAILED, never RETRY.
        assertEquals(PublishPipeline.Outcome.FAILED, run(0));
        PublishJob failed = rows.get(7L);
        assertEquals(PublishJob.FAILED, failed.status);
        assertEquals("MEDIA_PURGED", failed.errorKind);
        assertNull(failed.mediaId);
        assertNull(failed.mediaPath);
        assertNotEquals("key-auto", failed.idempotencyKey);
        assertEquals(1, server.getRequestCount());
    }

    private static final String NOT_IN_PLAN = "{\"statusCode\":403,\"code\":\"FEATURE_NOT_IN_PLAN\","
            + "\"feature\":\"auto_publish\",\"requiredPlan\":\"starter\"}";

    @Test
    public void preflightNotInPlan_failsAtOnceWithoutRetryOrUpload() throws Exception {
        newAutoJob();
        server.enqueue(new MockResponse().setResponseCode(403).setBody(NOT_IN_PLAN));

        // Attempt 0 of 3: FAILED, never RETRY (waiting cannot change the plan).
        assertEquals(PublishPipeline.Outcome.FAILED, run(0));
        PublishJob failed = rows.get(7L);
        assertEquals(PublishJob.FAILED, failed.status);
        assertEquals("NEEDS_PLAN", failed.errorKind);
        assertEquals("auto_publish", failed.errorDetail);
        assertNull(failed.mediaId);
        assertTrue(PublishLabels.needsPlan(failed));
        assertEquals(1, server.getRequestCount()); // the check only, no upload
    }

    @Test
    public void autoPostNotInPlan_failsAtOnceAndRotatesTheKey() throws Exception {
        newAutoJob();
        rows.get(7L).mediaId = "m1";
        rows.get(7L).mediaPath = "https://cdn.test/x.mp4";
        server.enqueue(new MockResponse().setResponseCode(403).setBody(NOT_IN_PLAN));

        assertEquals(PublishPipeline.Outcome.FAILED, run(0));
        PublishJob failed = rows.get(7L);
        assertEquals("NEEDS_PLAN", failed.errorKind);
        assertNotEquals("key-auto", failed.idempotencyKey);
        // The upload is kept: after an upgrade the card's Retry posts it without sending it again.
        assertEquals("m1", failed.mediaId);
        assertEquals(1, server.getRequestCount());
    }

    @Test
    public void manualJobNeverTouchesTheAutoRoute() throws Exception {
        newJob();
        server.enqueue(new MockResponse().setBody(UPLOAD_OK));
        server.enqueue(new MockResponse().setBody(POST_OK));
        assertEquals(PublishPipeline.Outcome.DONE, run(0));
        assertEquals("/public/v1/upload", server.takeRequest().getPath());
        assertEquals("/public/v1/posts", server.takeRequest().getPath());
        assertEquals(2, server.getRequestCount());
    }
}
