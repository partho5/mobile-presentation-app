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

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** DirectUploadApi and its error mapping against a local MockWebServer. */
public class DirectUploadApiTest {

    private static final String ID = "cm9k2a3bc0007";
    private static final String BASE = "/public/v1/upload/multipart";

    private MockWebServer server;
    private TokenStore store;
    private DirectUploadApi api;
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
        api = new DirectUploadApi(repo);
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
    }

    private static MockResponse json(int code, String body) {
        return new MockResponse().setResponseCode(code)
                .setHeader("Content-Type", "application/json").setBody(body);
    }

    private static String err(int status, String code) {
        return "{\"statusCode\":" + status + ",\"code\":\"" + code + "\",\"message\":\"m\"}";
    }

    private static PublishException failure(Runnable0 call) {
        try {
            call.run();
        } catch (PublishException e) {
            return e;
        }
        fail("expected PublishException");
        return null;
    }

    private interface Runnable0 {
        void run() throws PublishException;
    }

    private PublishException createFails(int status, String body) {
        server.enqueue(json(status, body));
        return failure(() -> api.create("a.mp4", 10, "w1"));
    }

    // ---- request shapes ----

    @Test
    public void create_postsFilenameAndSize_withBearerWorkspace_andNoIdempotencyKey() throws Exception {
        server.enqueue(json(201, "{\"id\":\"" + ID + "\",\"partSize\":16777216,\"partCount\":64}"));
        UploadSessionInfo s = api.create("Lecture1_9x16.mp4", 1073741824L, "w-job");
        assertEquals(ID, s.id);
        assertEquals(16777216L, s.partSize);
        assertEquals(64, s.partCount);

        RecordedRequest r = server.takeRequest();
        assertEquals("POST", r.getMethod());
        assertEquals(BASE, r.getPath());
        assertEquals("Bearer acc", r.getHeader("Authorization"));
        assertEquals("w-job", r.getHeader("x-workspace-id"));
        assertNull(r.getHeader("Idempotency-Key"));
        JSONObject body = new JSONObject(r.getBody().readUtf8());
        assertEquals("Lecture1_9x16.mp4", body.getString("filename"));
        assertEquals(1073741824L, body.getLong("size"));
        assertFalse(body.has("contentType"));
    }

    @Test
    public void create_withoutAWorkspace_usesTheDevicesPick() throws Exception {
        store.saveWorkspace("w-pick", "Acme");
        server.enqueue(json(201, "{\"id\":\"" + ID + "\",\"partSize\":5,\"partCount\":1}"));
        api.create("a.mp4", 3, null);
        assertEquals("w-pick", server.takeRequest().getHeader("x-workspace-id"));
    }

    @Test
    public void signParts_sendsTheNumbers_andReadsUrlsKeyedByPartNumber() throws Exception {
        server.enqueue(json(200, "{\"urls\":{\"1\":\"https://r2.example.com/p1?sig=a\","
                + "\"2\":\"https://r2.example.com/p2?sig=b\"},\"expiresIn\":900}"));
        PartUrls u = api.signParts(ID, Arrays.asList(1, 2), "w1");
        assertEquals("https://r2.example.com/p1?sig=a", u.urls.get(1));
        assertEquals("https://r2.example.com/p2?sig=b", u.urls.get(2));
        assertEquals(900, u.expiresInSeconds);

        RecordedRequest r = server.takeRequest();
        assertEquals("POST", r.getMethod());
        assertEquals(BASE + "/" + ID + "/parts", r.getPath());
        assertNull(r.getHeader("Idempotency-Key"));
        JSONArray n = new JSONObject(r.getBody().readUtf8()).getJSONArray("partNumbers");
        assertEquals(2, n.length());
        assertEquals(1, n.getInt(0));
        assertEquals(2, n.getInt(1));
    }

    @Test
    public void signParts_aSmallerAnswerThanAsked_isAServerFailure() {
        server.enqueue(json(200, "{\"urls\":{\"1\":\"https://r2.example.com/p1\"},\"expiresIn\":900}"));
        PublishException e = failure(() -> api.signParts(ID, Arrays.asList(1, 2), "w1"));
        assertEquals(PublishException.Kind.SERVER, e.kind);
    }

    @Test(expected = IllegalArgumentException.class)
    public void signParts_refusesZeroOrMoreThanTwentyNumbers() throws Exception {
        api.signParts(ID, Collections.<Integer>emptyList(), "w1");
    }

    @Test
    public void status_isAGet_andParsesThePartsWithEtagsAndSizes() throws Exception {
        server.enqueue(json(200, "{\"status\":\"pending\",\"size\":40000000,\"partSize\":16777216,"
                + "\"partCount\":3,\"parts\":[{\"n\":1,\"etag\":\"\\\"abc\\\"\",\"size\":16777216},"
                + "{\"n\":2,\"etag\":\"\\\"def\\\"\",\"size\":16777216}]}"));
        UploadStatus s = api.status(ID, "w1");
        assertTrue(s.isPending());
        assertEquals(40000000L, s.size);
        assertEquals(16777216L, s.partSize);
        assertEquals(3, s.partCount);
        assertEquals(2, s.parts.size());
        assertEquals(1, s.parts.get(0).n);
        assertEquals("\"abc\"", s.parts.get(0).etag);
        assertEquals(16777216L, s.parts.get(0).size);

        RecordedRequest r = server.takeRequest();
        assertEquals("GET", r.getMethod());
        assertEquals(BASE + "/" + ID, r.getPath());
        assertEquals("w1", r.getHeader("x-workspace-id"));
    }

    @Test
    public void status_nonPendingHasNoParts() throws Exception {
        server.enqueue(json(200, "{\"status\":\"completed\",\"size\":5,\"partSize\":5,"
                + "\"partCount\":1,\"parts\":[]}"));
        UploadStatus s = api.status(ID, "w1");
        assertFalse(s.isPending());
        assertEquals(UploadStatus.COMPLETED, s.status);
        assertTrue(s.parts.isEmpty());
    }

    @Test
    public void complete_sendsOnlyNAndEtag_andReadsTheMedia() throws Exception {
        server.enqueue(json(201, "{\"id\":\"m1\",\"path\":\"https://cdn.example.com/k.mp4\"}"));
        List<UploadedPart> parts = Arrays.asList(
                new UploadedPart(1, "\"abc\"", 16777216L), new UploadedPart(2, "\"def\"", 5));
        CompletedUpload c = api.complete(ID, parts, null, "w1");
        assertEquals("m1", c.id);
        assertEquals("https://cdn.example.com/k.mp4", c.path);
        assertNull(c.poster);
        assertEquals("m1", c.toMedia().id);
        assertEquals("https://cdn.example.com/k.mp4", c.toMedia().path);

        RecordedRequest r = server.takeRequest();
        assertEquals("POST", r.getMethod());
        assertEquals(BASE + "/" + ID + "/complete", r.getPath());
        assertNull(r.getHeader("Idempotency-Key"));
        JSONObject body = new JSONObject(r.getBody().readUtf8());
        assertFalse(body.has("poster"));
        JSONArray a = body.getJSONArray("parts");
        assertEquals(2, a.length());
        assertEquals(1, a.getJSONObject(0).getInt("n"));
        assertEquals("\"abc\"", a.getJSONObject(0).getString("etag"));
        assertFalse(a.getJSONObject(0).has("size"));
    }

    @Test
    public void complete_withAPoster_sendsItAndReadsItsUrl() throws Exception {
        server.enqueue(json(201, "{\"id\":\"m1\",\"path\":\"https://cdn.example.com/k.mp4\","
                + "\"poster\":\"https://cdn.example.com/k.jpg\"}"));
        CompletedUpload c = api.complete(ID,
                Collections.singletonList(new UploadedPart(1, "\"e\"", -1)), "QUJD", "w1");
        assertEquals("https://cdn.example.com/k.jpg", c.poster);
        JSONObject body = new JSONObject(server.takeRequest().getBody().readUtf8());
        assertEquals("QUJD", body.getString("poster"));
    }

    @Test
    public void complete_anEmptyPoster_isLeftOut() throws Exception {
        server.enqueue(json(201, "{\"id\":\"m1\",\"path\":\"p\"}"));
        api.complete(ID, Collections.singletonList(new UploadedPart(1, "\"e\"", -1)), "", "w1");
        assertFalse(new JSONObject(server.takeRequest().getBody().readUtf8()).has("poster"));
    }

    @Test
    public void abort_isADelete_andA204IsSuccess() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(204));
        api.abort(ID, "w1");
        RecordedRequest r = server.takeRequest();
        assertEquals("DELETE", r.getMethod());
        assertEquals(BASE + "/" + ID, r.getPath());
        assertEquals("w1", r.getHeader("x-workspace-id"));
        assertNull(r.getHeader("Idempotency-Key"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void anIdThatIsNotAPlainToken_isRefusedBeforeAnyRequest() throws Exception {
        api.status("../../x", "w1");
    }

    // ---- error mapping ----

    @Test
    public void unsupportedType_andTooLarge_areFinalAndRotateNothing() {
        PublishException t = createFails(400, err(400, "UNSUPPORTED_TYPE"));
        assertEquals(PublishException.Kind.UNSUPPORTED_TYPE, t.kind);
        assertFalse(t.isRetryable());
        assertFalse(t.spendsKey());
        assertEquals("m", t.detail);

        PublishException l = createFails(400, err(400, "FILE_TOO_LARGE"));
        assertEquals(PublishException.Kind.FILE_TOO_LARGE, l.kind);
        assertFalse(l.isRetryable());
        assertFalse(l.spendsKey());
    }

    @Test
    public void sizeAndContentTypeMismatch_areFinalAndRotateNothing() {
        server.enqueue(json(400, err(400, "SIZE_MISMATCH")));
        PublishException s = failure(() -> api.complete(ID,
                Collections.singletonList(new UploadedPart(1, "\"e\"", -1)), null, "w1"));
        assertEquals(PublishException.Kind.SIZE_MISMATCH, s.kind);
        assertFalse(s.isRetryable());
        assertFalse(s.spendsKey());

        server.enqueue(json(400, err(400, "CONTENT_TYPE_MISMATCH")));
        PublishException c = failure(() -> api.complete(ID,
                Collections.singletonList(new UploadedPart(1, "\"e\"", -1)), null, "w1"));
        assertEquals(PublishException.Kind.CONTENT_TYPE_MISMATCH, c.kind);
        assertFalse(c.isRetryable());
        assertFalse(c.spendsKey());
    }

    @Test
    public void tooManyPendingUploads_retries_andKeepsRetryAfter() {
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "30")
                .setHeader("Content-Type", "application/json")
                .setBody(err(429, "TOO_MANY_PENDING_UPLOADS")));
        PublishException e = failure(() -> api.create("a.mp4", 10, "w1"));
        assertEquals(PublishException.Kind.TOO_MANY_PENDING_UPLOADS, e.kind);
        assertEquals(30, e.retryAfterSeconds);
        assertTrue(e.isRetryable());
        assertFalse(e.spendsKey());
    }

    @Test
    public void storageError_isRetryable() {
        server.enqueue(json(502, err(502, "STORAGE_ERROR")));
        PublishException e = failure(() -> api.complete(ID,
                Collections.singletonList(new UploadedPart(1, "\"e\"", -1)), null, "w1"));
        assertEquals(PublishException.Kind.STORAGE_ERROR, e.kind);
        assertEquals(502, e.status);
        assertTrue(e.isRetryable());
        assertFalse(e.spendsKey());
    }

    @Test
    public void invalidPartsAndInvalidPoster_areNotRetriedAsIs() {
        PublishException p = createFails(400, err(400, "INVALID_PARTS"));
        assertEquals(PublishException.Kind.INVALID_PARTS, p.kind);
        assertFalse(p.isRetryable());
        assertFalse(p.spendsKey());

        PublishException q = createFails(400, err(400, "INVALID_POSTER"));
        assertEquals(PublishException.Kind.INVALID_POSTER, q.kind);
        assertFalse(q.isRetryable());
        assertFalse(q.spendsKey());
    }

    @Test
    public void notFound_andNotPending_areDistinct() {
        PublishException nf = createFails(404, err(404, "UPLOAD_NOT_FOUND"));
        assertEquals(PublishException.Kind.UPLOAD_NOT_FOUND, nf.kind);
        assertFalse(nf.isRetryable());
        assertFalse(nf.spendsKey());

        PublishException np = createFails(409, err(409, "UPLOAD_NOT_PENDING"));
        assertEquals(PublishException.Kind.UPLOAD_NOT_PENDING, np.kind);
        assertFalse(np.isRetryable());
        assertFalse(np.spendsKey());
    }

    @Test
    public void createThrottle429_hasNoCode_isPlainRetry_andRotatesNoKey() {
        PublishException e = createFails(429, "{\"statusCode\":429,"
                + "\"message\":\"ThrottlerException: Too Many Requests\"}");
        assertEquals(PublishException.Kind.RATE_LIMITED, e.kind);
        assertTrue(e.isRetryable());
        assertFalse(e.spendsKey());
    }

    @Test
    public void authRateLimit_isMappedAsEverywhereElse() {
        PublishException e = createFails(429, err(429, "AUTH_RATE_LIMITED"));
        assertEquals(PublishException.Kind.RATE_LIMITED, e.kind);
        assertFalse(e.spendsKey());
    }

    @Test
    public void serverErrors_unreadableAnswers_andForbiddenWorkspace_follow_theCommonMapping() {
        assertEquals(PublishException.Kind.SERVER, createFails(500, "{}").kind);
        assertTrue(createFails(500, "{}").isRetryable());
        assertEquals(PublishException.Kind.SERVER, createFails(503, "").kind);
        assertEquals(PublishException.Kind.WORKSPACE_FORBIDDEN,
                createFails(403, err(403, "WORKSPACE_FORBIDDEN")).kind);
        server.enqueue(json(201, "not json"));
        assertEquals(PublishException.Kind.SERVER,
                failure(() -> api.create("a.mp4", 10, "w1")).kind);
        server.enqueue(json(201, "{\"id\":\"x\",\"partSize\":0,\"partCount\":0}"));
        assertEquals(PublishException.Kind.SERVER,
                failure(() -> api.create("a.mp4", 10, "w1")).kind);
    }

    @Test
    public void aDroppedConnection_isANetworkFailure() throws Exception {
        server.shutdown();
        PublishException e = failure(() -> api.create("a.mp4", 10, "w1"));
        assertEquals(PublishException.Kind.NETWORK, e.kind);
        assertTrue(e.isRetryable());
    }
}
