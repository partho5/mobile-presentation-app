package com.jovoc.facecampresentationrecorder.publish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jovoc.facecampresentationrecorder.auth.ApiClient;
import com.jovoc.facecampresentationrecorder.auth.AuthRepository;
import com.jovoc.facecampresentationrecorder.auth.KeyValueStore;
import com.jovoc.facecampresentationrecorder.auth.TokenStore;
import com.jovoc.facecampresentationrecorder.db.PublishJob;
import com.jovoc.facecampresentationrecorder.db.UploadSession;
import com.jovoc.facecampresentationrecorder.db.UploadSessionDao;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * The publish flow with the direct upload: the video goes to "R2" (the same local server, under
 * /r2), the media id is stored once, the post is created once, and the idempotency key is never
 * spent by an upload failure.
 */
public class PublishPipelineDirectUploadTest {

    private static final int PART_SIZE = 131072; // the file below is 3 parts: 128K, 128K, 40K
    private static final String POST_OK = "[{\"postId\":\"p1\",\"integration\":\"c1\"}]";
    private static final String LEGACY_OK =
            "{\"id\":\"legacy1\",\"name\":\"x.mp4\",\"path\":\"https://cdn.test/legacy.mp4\"}";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private MockWebServer server;
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Map<Long, PublishJob> rows = new HashMap<>();
    private final MemorySessions sessions = new MemorySessions();
    private final List<RecordedRequest> seen = new CopyOnWriteArrayList<>();

    // The fake backend's state.
    private final AtomicInteger creates = new AtomicInteger();
    private final Map<Integer, String> stored = new TreeMap<>(); // parts "R2" holds, guarded by itself
    private final Map<Integer, AtomicInteger> puts = new HashMap<>();
    private volatile int failPart = -1;          // part whose PUT answers 500
    private volatile int createStatus = 201;     // 404 = a server without the routes
    private volatile String completeError;       // body of a 400 answer of complete, or null
    private volatile int legacyStatus = 200;
    private volatile String notFoundBody =
            "{\"statusCode\":404,\"message\":\"Cannot POST /public/v1/upload/multipart\"}";

    private PublishPipeline pipeline;
    private File video;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest r) {
                seen.add(r);
                return answer(r);
            }
        });
        server.start();

        TokenStore tokens = new TokenStore(new KeyValueStore() {
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
        OkHttpClient http = new OkHttpClient.Builder().retryOnConnectionFailure(false)
                .readTimeout(5, TimeUnit.SECONDS).build();
        AuthRepository repo = new AuthRepository(
                new ApiClient(server.url("/").toString(), http, true), tokens, now::get);

        video = tmp.newFile("demo.mp4");
        try (FileOutputStream out = new FileOutputStream(video)) {
            out.write(new byte[300 * 1024]);
        }

        // The R2 client is a plain one (as in the app: no interceptor); no waiting between tries.
        DirectUploader direct = new DirectUploader(new DirectUploadApi(repo),
                new OkHttpClient.Builder().build(), millis -> { }, now::get);
        pipeline = new PublishPipeline(new PublishApi(repo), direct, sessions,
                new PublishPipeline.Store() {
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

    @After
    public void tearDown() throws Exception {
        server.shutdown();
    }

    private MockResponse answer(RecordedRequest r) {
        String path = r.getPath();
        String method = r.getMethod();
        if (path.startsWith("/r2/")) {
            int n = Integer.parseInt(path.substring(4));
            synchronized (puts) {
                puts.computeIfAbsent(n, k -> new AtomicInteger()).incrementAndGet();
            }
            if (n == failPart) return new MockResponse().setResponseCode(500);
            synchronized (stored) {
                stored.put(n, "\"e" + n + "\"");
            }
            return new MockResponse().setHeader("ETag", "\"e" + n + "\"");
        }
        if (path.equals("/public/v1/upload/multipart") && method.equals("POST")) {
            creates.incrementAndGet();
            if (createStatus == 404) {
                return new MockResponse().setResponseCode(404).setBody(notFoundBody);
            }
            return new MockResponse().setResponseCode(201).setBody(
                    "{\"id\":\"u" + creates.get() + "\",\"partSize\":" + PART_SIZE + ",\"partCount\":3}");
        }
        if (path.startsWith("/public/v1/upload/multipart/")) {
            if (path.endsWith("/parts")) {
                try {
                    JSONArray nums = new JSONObject(r.getBody().readUtf8()).getJSONArray("partNumbers");
                    JSONObject urls = new JSONObject();
                    for (int i = 0; i < nums.length(); i++) {
                        urls.put(String.valueOf(nums.getInt(i)),
                                server.url("/r2/" + nums.getInt(i)).toString());
                    }
                    return new MockResponse().setBody(
                            new JSONObject().put("urls", urls).put("expiresIn", 900).toString());
                } catch (Exception e) {
                    return new MockResponse().setResponseCode(500);
                }
            }
            if (path.endsWith("/complete")) {
                if (completeError != null) {
                    return new MockResponse().setResponseCode(400).setBody(completeError);
                }
                return new MockResponse().setResponseCode(201)
                        .setBody("{\"id\":\"m1\",\"path\":\"https://cdn.test/x.mp4\"}");
            }
            if (method.equals("GET")) {
                StringBuilder parts = new StringBuilder();
                synchronized (stored) {
                    for (Map.Entry<Integer, String> e : stored.entrySet()) {
                        if (parts.length() > 0) parts.append(',');
                        long len = e.getKey() < 3 ? PART_SIZE : 300 * 1024 - 2L * PART_SIZE;
                        parts.append("{\"n\":").append(e.getKey()).append(",\"etag\":")
                                .append(JSONObject.quote(e.getValue())).append(",\"size\":")
                                .append(len).append('}');
                    }
                }
                return new MockResponse().setBody("{\"status\":\"pending\",\"size\":" + 300 * 1024
                        + ",\"partSize\":" + PART_SIZE + ",\"partCount\":3,\"parts\":[" + parts + "]}");
            }
            return new MockResponse().setResponseCode(204);
        }
        if (path.equals("/public/v1/upload")) {
            if (legacyStatus != 200) {
                return new MockResponse().setResponseCode(legacyStatus)
                        .setBody("{\"statusCode\":" + legacyStatus + ",\"message\":\"nope\"}");
            }
            return new MockResponse().setBody(LEGACY_OK);
        }
        return new MockResponse().setBody(POST_OK);
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

    private int count(String method, String path) {
        int n = 0;
        for (RecordedRequest r : seen) {
            if (r.getMethod().equals(method) && r.getPath().equals(path)) n++;
        }
        return n;
    }

    private int putsOf(int part) {
        synchronized (puts) {
            AtomicInteger c = puts.get(part);
            return c == null ? 0 : c.get();
        }
    }

    // ---- happy path ----

    @Test
    public void aVideoGoesDirectToR2ThenPostsOnceWithTheKey() throws Exception {
        newJob();

        assertEquals(PublishPipeline.Outcome.DONE, pipeline.run(7, 0));

        PublishJob j = rows.get(7L);
        assertEquals(PublishJob.DONE, j.status);
        assertEquals("m1", j.mediaId);
        assertEquals("https://cdn.test/x.mp4", j.mediaPath);
        assertEquals("p1", j.postIds);
        assertEquals(1, creates.get());
        assertEquals(0, count("POST", "/public/v1/upload")); // nothing through the legacy route
        for (int n = 1; n <= 3; n++) assertEquals(1, putsOf(n));
        assertNull("the row is forgotten once the media id is saved", sessions.get(7));

        for (RecordedRequest r : seen) {
            String p = r.getPath();
            if (p.startsWith("/public/v1/upload")) {
                assertNull("no key on upload routes", r.getHeader("Idempotency-Key"));
            }
            if (p.startsWith("/public/v1/posts")) {
                assertEquals("key-1", r.getHeader("Idempotency-Key"));
                assertTrue(r.getBody().readUtf8().contains("m1"));
            }
        }
    }

    @Test
    public void anImageOrOtherFileKeepsTheLegacyUpload() throws Exception {
        PublishJob j = newJob();
        File other = tmp.newFile("cover.png");
        try (FileOutputStream out = new FileOutputStream(other)) {
            out.write(new byte[1024]);
        }
        j.filePath = other.getAbsolutePath();

        assertEquals(PublishPipeline.Outcome.DONE, pipeline.run(7, 0));

        assertEquals(0, creates.get());
        assertEquals(1, count("POST", "/public/v1/upload"));
        assertEquals("legacy1", rows.get(7L).mediaId);
    }

    // ---- resume ----

    @Test
    public void aRunInterruptedMidUploadResumesWithoutANewCreateOrResentParts() throws Exception {
        newJob();
        failPart = 2; // R2 keeps answering 500 for part 2

        assertEquals(PublishPipeline.Outcome.RETRY, pipeline.run(7, 0));
        PublishJob queued = rows.get(7L);
        assertEquals(PublishJob.QUEUED, queued.status);
        assertNull(queued.mediaId);
        assertEquals("key-1", queued.idempotencyKey);
        UploadSession row = sessions.get(7);
        assertNotNull("the session survives so the next run resumes", row);
        assertEquals("u1", row.uploadId);
        int firstRunPart1 = putsOf(1);

        failPart = -1;
        assertEquals(PublishPipeline.Outcome.DONE, pipeline.run(7, 1));

        assertEquals("no second create", 1, creates.get());
        assertEquals("a finished part is not sent again", firstRunPart1, putsOf(1));
        assertEquals("m1", rows.get(7L).mediaId);
        assertEquals(1, count("POST", "/public/v1/posts"));
        assertNull(sessions.get(7));
    }

    @Test
    public void aKillAfterCompleteBeforeTheMediaIdWasSavedReplaysCompleteWithoutUploading() throws Exception {
        newJob();
        // What the first process left: everything uploaded, complete answered, id not saved.
        pipeline.run(7, 0);
        PublishJob j = rows.get(7L);
        j.status = PublishJob.QUEUED;
        j.mediaId = null;
        j.mediaPath = null;
        UploadSession row = new UploadSession();
        row.jobId = 7;
        row.uploadId = "u1";
        row.size = 300 * 1024;
        row.partSize = PART_SIZE;
        row.partCount = 3;
        row.partsJson = "[{\"n\":1,\"etag\":\"\\\"e1\\\"\"},{\"n\":2,\"etag\":\"\\\"e2\\\"\"},"
                + "{\"n\":3,\"etag\":\"\\\"e3\\\"\"}]";
        row.status = UploadSession.COMPLETED;
        sessions.put(row);
        seen.clear();

        assertEquals(PublishPipeline.Outcome.DONE, pipeline.run(7, 1));

        assertEquals(1, creates.get());
        assertEquals(1, count("POST", "/public/v1/upload/multipart/u1/complete"));
        assertEquals(0, count("PUT", "/r2/1"));
        assertEquals("m1", rows.get(7L).mediaId);
    }

    // ---- fallback ----

    @Test
    public void aServerWithoutTheRoutesFallsBackToTheLegacyUploadOnceAndKeepsTheKey() throws Exception {
        newJob();
        createStatus = 404;
        legacyStatus = 500; // the legacy upload hiccups on the first run

        assertEquals(PublishPipeline.Outcome.RETRY, pipeline.run(7, 0));
        assertEquals("key-1", rows.get(7L).idempotencyKey);
        assertEquals(1, count("POST", "/public/v1/upload"));
        assertEquals(UploadSession.LEGACY, sessions.get(7).status);

        legacyStatus = 200;
        assertEquals(PublishPipeline.Outcome.DONE, pipeline.run(7, 1));

        assertEquals("the choice is remembered: no second create", 1, creates.get());
        PublishJob j = rows.get(7L);
        assertEquals("legacy1", j.mediaId);
        assertEquals("key-1", j.idempotencyKey);
        assertNull(sessions.get(7));
        assertEquals(1, count("POST", "/public/v1/posts"));
    }

    @Test
    public void aPlain4xxOfTheUploadStepNeverRotatesTheKey() throws Exception {
        newJob();
        createStatus = 404;
        legacyStatus = 400; // a plain 4xx: spendsKey() would be true for a post

        assertEquals(PublishPipeline.Outcome.FAILED, pipeline.run(7, 0));

        PublishJob j = rows.get(7L);
        assertEquals("REJECTED", j.errorKind);
        assertEquals("key-1", j.idempotencyKey);
        assertEquals(0, count("POST", "/public/v1/posts"));
    }

    @Test
    public void a404WithACodeIsARealAnswerAndNeverFallsBackToTheLegacyUpload() throws Exception {
        newJob();
        createStatus = 404;
        notFoundBody = "{\"statusCode\":404,\"code\":\"UPLOAD_NOT_FOUND\",\"message\":\"x\"}";

        PublishPipeline.Outcome out = pipeline.run(7, 0);

        assertEquals(0, count("POST", "/public/v1/upload"));
        assertFalse(sessions.get(7) != null && UploadSession.LEGACY.equals(sessions.get(7).status));
        assertEquals("key-1", rows.get(7L).idempotencyKey);
        assertEquals(0, count("POST", "/public/v1/posts"));
        assertFalse(PublishPipeline.Outcome.DONE == out);
    }

    // ---- final errors ----

    @Test
    public void sizeMismatchIsFinalKeepsTheKeyAndARetryStartsANewSession() throws Exception {
        newJob();
        completeError = "{\"statusCode\":400,\"code\":\"SIZE_MISMATCH\",\"message\":\"size\"}";

        assertEquals(PublishPipeline.Outcome.FAILED, pipeline.run(7, 0));

        PublishJob failed = rows.get(7L);
        assertEquals(PublishJob.FAILED, failed.status);
        assertEquals("SIZE_MISMATCH", failed.errorKind);
        assertEquals("key-1", failed.idempotencyKey);
        assertNull(failed.mediaId);
        assertNull("the aborted session is dropped", sessions.get(7));
        assertEquals(0, count("POST", "/public/v1/posts"));
        assertEquals(1, creates.get());

        // The user taps Retry: the same job queued again, the server is fine this time.
        completeError = null;
        synchronized (stored) {
            stored.clear();
        }
        failed.status = PublishJob.QUEUED;
        assertEquals(PublishPipeline.Outcome.DONE, pipeline.run(7, 0));
        assertEquals("a new session", 2, creates.get());
        assertEquals("m1", rows.get(7L).mediaId);
        assertFalse(PublishJob.FAILED.equals(rows.get(7L).status));
    }

    @Test
    public void contentTypeMismatchIsFinalAndNothingIsPosted() throws Exception {
        newJob();
        completeError = "{\"statusCode\":400,\"code\":\"CONTENT_TYPE_MISMATCH\",\"message\":\"type\"}";

        assertEquals(PublishPipeline.Outcome.FAILED, pipeline.run(7, 0));

        assertEquals("CONTENT_TYPE_MISMATCH", rows.get(7L).errorKind);
        assertEquals("key-1", rows.get(7L).idempotencyKey);
        assertEquals(0, count("POST", "/public/v1/posts"));
    }

    @Test
    public void aRowForAJobThatWasDeletedIsForgottenWhenTheWorkerFindsItGone() {
        UploadSession row = new UploadSession();
        row.jobId = 9;
        row.uploadId = "u9";
        row.status = UploadSession.PENDING;
        sessions.put(row);

        assertEquals(PublishPipeline.Outcome.GONE, pipeline.run(9, 0));

        assertNull(sessions.get(9));
    }

    // ---- in-memory Room ----

    private static final class MemorySessions implements UploadSessionDao {
        private final Map<Long, UploadSession> map = new HashMap<>();

        @Override
        public synchronized UploadSession get(long jobId) {
            UploadSession s = map.get(jobId);
            return s == null ? null : copy(s);
        }

        @Override
        public synchronized void put(UploadSession s) {
            map.put(s.jobId, copy(s));
        }

        @Override
        public synchronized void setParts(long jobId, String partsJson) {
            UploadSession s = map.get(jobId);
            if (s != null) s.partsJson = partsJson;
        }

        @Override
        public synchronized void setStatus(long jobId, String status) {
            UploadSession s = map.get(jobId);
            if (s != null) s.status = status;
        }

        @Override
        public synchronized void delete(long jobId) {
            map.remove(jobId);
        }

        private static UploadSession copy(UploadSession s) {
            UploadSession c = new UploadSession();
            c.jobId = s.jobId;
            c.uploadId = s.uploadId;
            c.size = s.size;
            c.partSize = s.partSize;
            c.partCount = s.partCount;
            c.partsJson = s.partsJson;
            c.status = s.status;
            return c;
        }
    }
}
