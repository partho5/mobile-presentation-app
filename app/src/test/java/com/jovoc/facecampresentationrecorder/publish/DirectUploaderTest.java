package com.jovoc.facecampresentationrecorder.publish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jovoc.facecampresentationrecorder.db.UploadSession;
import com.jovoc.facecampresentationrecorder.db.UploadSessionDao;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** The upload loop against a scripted backend API and a MockWebServer standing in for R2. */
public class DirectUploaderTest {

    private static final long JOB = 7;
    private static final int PART_SIZE = 100;

    private MockWebServer r2;
    private File file;
    private FakeApi api;
    private MemorySessions sessions;
    private final List<Long> sleeps = new CopyOnWriteArrayList<>();
    private final AtomicLong clock = new AtomicLong(1_000_000);
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Map<Integer, AtomicInteger> puts = new ConcurrentHashMap<>();
    private final List<RecordedRequest> r2Requests = new CopyOnWriteArrayList<>();
    /** Answer for a part PUT; default 200 with an ETag derived from the part number. */
    private volatile PartAnswer answer = (n, attempt) -> ok(n);

    interface PartAnswer {
        MockResponse answer(int part, int attempt);
    }

    private static MockResponse ok(int n) {
        return new MockResponse().setResponseCode(200).setHeader("ETag", "\"etag-" + n + "\"");
    }

    @Before
    public void setUp() throws IOException {
        r2 = new MockWebServer();
        r2.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                r2Requests.add(request);
                int n = Integer.parseInt(request.getPath().split("[/?]")[2]);
                int attempt = puts.computeIfAbsent(n, k -> new AtomicInteger()).incrementAndGet();
                return answer.answer(n, attempt);
            }
        });
        r2.start();
        file = File.createTempFile("rec", ".mp4");
        Files.write(file.toPath(), new byte[250]); // 3 parts: 100, 100, 50
        api = new FakeApi();
        sessions = new MemorySessions();
    }

    @After
    public void tearDown() throws IOException {
        r2.shutdown();
        file.delete();
    }

    private DirectUploader uploader() {
        return new DirectUploader(api, DirectUploader.newR2Client(), sleeps::add, clock::get);
    }

    private CompletedUpload run(List<long[]> progress) throws PublishException {
        return uploader().upload(JOB, file, "ws1", sessions,
                progress == null ? null : (sent, total) -> progress.add(new long[]{sent, total}),
                cancelled::get);
    }

    private static PublishException failure(Call c) {
        try {
            c.run();
        } catch (PublishException e) {
            return e;
        }
        fail("expected PublishException");
        return null;
    }

    interface Call {
        void run() throws PublishException;
    }

    // ---- fakes ----

    /** The backend routes, scripted; part URLs point at the MockWebServer. */
    private class FakeApi extends DirectUploadApi {
        final AtomicInteger creates = new AtomicInteger();
        final List<List<Integer>> signed = new CopyOnWriteArrayList<>();
        final List<String> aborted = new CopyOnWriteArrayList<>();
        final List<List<UploadedPart>> completes = new CopyOnWriteArrayList<>();
        final List<String> posters = new CopyOnWriteArrayList<>();
        final List<String> workspaces = new CopyOnWriteArrayList<>();
        volatile UploadStatus status;
        volatile int expiresIn = 900;
        volatile PublishException completeError;
        int completeErrors;

        FakeApi() {
            super(null);
        }

        @Override
        public UploadSessionInfo create(String filename, long size, String workspaceId) {
            creates.incrementAndGet();
            workspaces.add(workspaceId);
            assertEquals(file.getName(), filename);
            assertEquals(250, size);
            return new UploadSessionInfo("up" + creates.get(), PART_SIZE, 3);
        }

        @Override
        public PartUrls signParts(String uploadId, List<Integer> partNumbers, String workspaceId) {
            signed.add(new ArrayList<>(partNumbers));
            Map<Integer, String> urls = new LinkedHashMap<>();
            for (int n : partNumbers) urls.put(n, r2.url("/part/" + n + "?sig=x").toString());
            return new PartUrls(urls, expiresIn);
        }

        @Override
        public UploadStatus status(String uploadId, String workspaceId) throws PublishException {
            if (status == null) throw new PublishException(PublishException.Kind.UPLOAD_NOT_FOUND);
            return status;
        }

        @Override
        public CompletedUpload complete(String uploadId, List<UploadedPart> parts, String poster,
                                        String workspaceId) throws PublishException {
            completes.add(new ArrayList<>(parts));
            posters.add(poster);
            if (completeError != null && completeErrors-- > 0) throw completeError;
            return new CompletedUpload(uploadId, "https://cdn.example.com/" + uploadId + ".mp4", null);
        }

        @Override
        public void abort(String uploadId, String workspaceId) throws PublishException {
            aborted.add(uploadId);
        }
    }

    private static final class MemorySessions implements UploadSessionDao {
        final Map<Long, UploadSession> rows = new HashMap<>();
        final List<String> partWrites = new CopyOnWriteArrayList<>();

        @Override
        public synchronized UploadSession get(long jobId) {
            UploadSession s = rows.get(jobId);
            return s == null ? null : copy(s);
        }

        @Override
        public synchronized void put(UploadSession session) {
            rows.put(session.jobId, copy(session));
        }

        @Override
        public synchronized void setParts(long jobId, String partsJson) {
            UploadSession s = rows.get(jobId);
            if (s != null) s.partsJson = partsJson;
            partWrites.add(partsJson);
        }

        @Override
        public synchronized void setStatus(long jobId, String status) {
            UploadSession s = rows.get(jobId);
            if (s != null) s.status = status;
        }

        @Override
        public synchronized void delete(long jobId) {
            rows.remove(jobId);
        }

        static UploadSession copy(UploadSession s) {
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

    private UploadSession storedRow(String uploadId, long size, Map<Integer, String> parts) {
        UploadSession s = new UploadSession();
        s.jobId = JOB;
        s.uploadId = uploadId;
        s.size = size;
        s.partSize = PART_SIZE;
        s.partCount = 3;
        s.partsJson = UploadPlan.partsToJson(parts);
        s.status = UploadSession.PENDING;
        return s;
    }

    private static UploadStatus pending(UploadedPart... parts) {
        List<UploadedPart> l = new ArrayList<>();
        Collections.addAll(l, parts);
        return statusOf(UploadStatus.PENDING, l);
    }

    private static UploadStatus statusOf(String status, List<UploadedPart> parts) {
        return new UploadStatus(status, 250, PART_SIZE, 3, parts);
    }

    // ---- tests ----

    @Test
    public void freshUpload_sendsEveryPartWithExactLengthAndNoAuthAndCompletes() throws Exception {
        List<long[]> progress = new ArrayList<>();
        CompletedUpload done = run(progress);

        assertEquals("up1", done.id);
        assertEquals(1, api.creates.get());
        assertEquals("ws1", api.workspaces.get(0));
        assertEquals(3, r2Requests.size());
        Map<Integer, Long> lengths = new TreeMap<>();
        for (RecordedRequest r : r2Requests) {
            int n = Integer.parseInt(r.getPath().split("[/?]")[2]);
            assertEquals("PUT", r.getMethod());
            assertNull("no auth to R2", r.getHeader("Authorization"));
            assertNull(r.getHeader("Transfer-Encoding"));
            assertEquals(String.valueOf(r.getBodySize()), r.getHeader("Content-Length"));
            lengths.put(n, r.getBodySize());
        }
        assertEquals(100L, (long) lengths.get(1));
        assertEquals(100L, (long) lengths.get(2));
        assertEquals(50L, (long) lengths.get(3));

        assertEquals(1, api.completes.size());
        Map<Integer, String> sent = new TreeMap<>();
        for (UploadedPart p : api.completes.get(0)) sent.put(p.n, p.etag);
        assertEquals("\"etag-1\"", sent.get(1));
        assertEquals("\"etag-3\"", sent.get(3));
        assertEquals(3, sent.size());

        UploadSession row = sessions.get(JOB);
        assertNotNull("kept until finish()", row);
        assertEquals(UploadSession.COMPLETED, row.status);
        assertEquals(3, UploadPlan.partsFromJson(row.partsJson).size());

        long[] last = progress.get(progress.size() - 1);
        assertEquals(250, last[0]);
        assertEquals(250, last[1]);
        long prev = -1;
        for (long[] p : progress) {
            assertTrue(p[0] >= prev);
            prev = p[0];
        }
    }

    @Test
    public void everyFinishedPartIsPersistedBeforeCompleteIsCalled() throws Exception {
        run(null);
        // 3 parts, 3 writes: each part stored on its own, growing.
        assertEquals(3, sessions.partWrites.size());
        assertEquals(1, UploadPlan.partsFromJson(sessions.partWrites.get(0)).size());
        assertEquals(3, UploadPlan.partsFromJson(sessions.partWrites.get(2)).size());
    }

    @Test
    public void sessionRowIsWrittenWithTheServersNumbersRightAfterCreate() throws Exception {
        answer = (n, a) -> new MockResponse().setResponseCode(500);
        failure(() -> run(null));
        UploadSession row = sessions.get(JOB);
        assertEquals("up1", row.uploadId);
        assertEquals(250, row.size);
        assertEquals(PART_SIZE, row.partSize);
        assertEquals(3, row.partCount);
    }

    @Test
    public void urlsAreSignedInSmallBatchesAsPartsAreNeeded() throws Exception {
        run(null);
        // 3 missing parts fit one batch of at most URL_BATCH, never one call per part more than needed.
        int total = 0;
        for (List<Integer> b : api.signed) {
            assertTrue(b.size() <= UploadPlan.URL_BATCH);
            total += b.size();
        }
        assertTrue(total >= 3);
        assertTrue(api.signed.size() <= 3);
    }

    @Test
    public void resume_sendsOnlyTheMissingPartsAndDoesNotCreateAgain() throws Exception {
        Map<Integer, String> local = new TreeMap<>();
        local.put(1, "\"etag-1\"");
        local.put(2, "\"etag-2\"");
        sessions.put(storedRow("upX", 250, local));
        api.status = pending(new UploadedPart(1, "\"etag-1\"", 100), new UploadedPart(2, "\"etag-2\"", 100));

        run(null);

        assertEquals(0, api.creates.get());
        assertEquals(1, r2Requests.size());
        assertTrue(r2Requests.get(0).getPath().startsWith("/part/3"));
        assertEquals(3, api.completes.get(0).size());
    }

    @Test
    public void resume_resendsAPartRtwoDoesNotListEvenIfStoredLocally() throws Exception {
        Map<Integer, String> local = new TreeMap<>();
        local.put(1, "\"etag-1\"");
        local.put(2, "\"stale\"");
        sessions.put(storedRow("upX", 250, local));
        api.status = pending(new UploadedPart(1, "\"etag-1\"", 100));

        run(null);

        List<Integer> sent = new ArrayList<>(puts.keySet());
        Collections.sort(sent);
        assertEquals(java.util.Arrays.asList(2, 3), sent);
        for (UploadedPart p : api.completes.get(0)) assertFalse(p.etag.equals("\"stale\""));
    }

    @Test
    public void resume_everythingStoredAndRtwoListEmptyGoesStraightToComplete() throws Exception {
        Map<Integer, String> local = new TreeMap<>();
        for (int n = 1; n <= 3; n++) local.put(n, "\"etag-" + n + "\"");
        sessions.put(storedRow("upX", 250, local));
        api.status = pending();

        run(null);

        assertEquals(0, r2Requests.size());
        assertEquals(1, api.completes.size());
    }

    @Test
    public void resume_completedOnTheServerReplaysCompleteWithStoredEtags() throws Exception {
        Map<Integer, String> local = new TreeMap<>();
        for (int n = 1; n <= 3; n++) local.put(n, "\"etag-" + n + "\"");
        sessions.put(storedRow("upX", 250, local));
        api.status = statusOf(UploadStatus.COMPLETED, Collections.<UploadedPart>emptyList());

        CompletedUpload done = run(null);

        assertEquals("upX", done.id);
        assertEquals(0, r2Requests.size());
        assertEquals(0, api.creates.get());
    }

    @Test
    public void resume_expiredOrAbortedUploadStartsOver() throws Exception {
        sessions.put(storedRow("upOld", 250, new TreeMap<>()));
        api.status = statusOf(UploadStatus.EXPIRED, Collections.<UploadedPart>emptyList());

        CompletedUpload done = run(null);

        assertEquals(1, api.creates.get());
        assertEquals("up1", done.id);
        assertEquals(3, r2Requests.size());
    }

    @Test
    public void resume_unknownToTheServerStartsOver() throws Exception {
        sessions.put(storedRow("upOld", 250, new TreeMap<>()));
        api.status = null; // fake answers UPLOAD_NOT_FOUND

        run(null);

        assertEquals(1, api.creates.get());
        assertTrue(api.aborted.isEmpty());
    }

    @Test
    public void fileSizeChanged_abortsTheOldUploadAndStartsOver() throws Exception {
        Map<Integer, String> local = new TreeMap<>();
        local.put(1, "\"etag-1\"");
        sessions.put(storedRow("upOld", 999, local)); // created for a 999-byte file

        run(null);

        assertEquals(java.util.Collections.singletonList("upOld"), api.aborted);
        assertEquals(1, api.creates.get());
        assertEquals(3, r2Requests.size()); // nothing reused
        assertEquals("up1", sessions.get(JOB).uploadId);
    }

    @Test
    public void expiredUrl403_asksForAFreshUrlAndSucceeds() throws Exception {
        answer = (n, attempt) -> n == 2 && attempt == 1
                ? new MockResponse().setResponseCode(403) : ok(n);

        run(null);

        assertEquals(2, puts.get(2).get());
        int signedFor2 = 0;
        for (List<Integer> b : api.signed) if (b.contains(2)) signedFor2++;
        assertEquals(2, signedFor2);
        assertTrue("a 403 re-sign does not wait", sleeps.isEmpty());
    }

    @Test
    public void missingEtag_isAPartFailureAndIsRetriedWithBackoff() throws Exception {
        answer = (n, attempt) -> n == 1 && attempt == 1
                ? new MockResponse().setResponseCode(200) : ok(n);

        run(null);

        assertEquals(2, puts.get(1).get());
        long total = 0;
        for (long s : sleeps) total += s;
        assertEquals(1000, total);
        assertEquals("\"etag-1\"", api.completes.get(0).get(0).etag);
    }

    @Test
    public void aPartFailingFiveTimesFailsTheUploadAndKeepsTheSession() throws Exception {
        answer = (n, attempt) -> n == 2
                ? new MockResponse().setResponseCode(503) : ok(n);

        PublishException e = failure(() -> run(null));

        assertEquals(PublishException.Kind.SERVER, e.kind);
        assertEquals(UploadPlan.MAX_TRIES, puts.get(2).get());
        // 1 + 2 + 4 + 8 s between 5 tries.
        long total = 0;
        for (long s : sleeps) total += s;
        assertEquals(15_000, total);
        assertTrue(api.completes.isEmpty());
        assertNotNull(sessions.get(JOB));
        assertEquals(UploadSession.PENDING, sessions.get(JOB).status);
    }

    @Test
    public void connectionDroppedFiveTimes_isANetworkFailure() throws Exception {
        answer = (n, attempt) -> n == 2
                ? new MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START)
                : ok(n);

        PublishException e = failure(() -> run(null));

        assertEquals(PublishException.Kind.NETWORK, e.kind);
        assertTrue(e.isRetryable());
    }

    @Test
    public void cancelDuringUpload_stopsWithCancelled() throws Exception {
        answer = (n, attempt) -> {
            cancelled.set(true);
            return ok(n);
        };

        PublishException e = failure(() -> run(null));

        assertEquals(PublishException.Kind.CANCELLED, e.kind);
        assertTrue(api.completes.isEmpty());
    }

    @Test
    public void invalidPartsAtComplete_refreshesFromStatusAndSendsTheMissingPart() throws Exception {
        api.completeError = new PublishException(PublishException.Kind.INVALID_PARTS, 400, null, -1);
        api.completeErrors = 1;
        // R2 says part 2 is not really there.
        api.status = pending(new UploadedPart(1, "\"etag-1\"", 100), new UploadedPart(3, "\"etag-3\"", 50));

        run(null);

        assertEquals(2, api.completes.size());
        assertEquals(2, puts.get(2).get()); // sent in round 1 and again after the status
        assertEquals(1, puts.get(1).get());
    }

    @Test
    public void refusedPoster_isDroppedAndCompleteRepeated() throws Exception {
        api.completeError = new PublishException(PublishException.Kind.INVALID_POSTER, 400, null, -1);
        api.completeErrors = 1;

        uploader().upload(JOB, file, "ws1", sessions, null, cancelled::get, "UE9TVEVS");

        assertEquals(2, api.completes.size());
        assertEquals("UE9TVEVS", api.posters.get(0));
        assertNull(api.posters.get(1));
        assertEquals(3, r2Requests.size()); // no part was sent twice
    }

    @Test
    public void completeStorageError_keepsTheSessionForARetry() throws Exception {
        api.completeError = new PublishException(PublishException.Kind.STORAGE_ERROR, 502, null, -1);
        api.completeErrors = 1;

        PublishException e = failure(() -> run(null));
        assertEquals(PublishException.Kind.STORAGE_ERROR, e.kind);
        assertTrue(e.isRetryable());

        // The retry: R2 lists nothing, but every etag is stored, so only complete is repeated.
        api.status = pending();
        run(null);
        assertEquals(3, r2Requests.size());
        assertEquals(2, api.completes.size());
        assertEquals(1, api.creates.get());
    }

    @Test
    public void finish_deletesTheRowWithoutTalkingToTheServer() throws Exception {
        run(null);
        uploader().finish(JOB, sessions);
        assertNull(sessions.get(JOB));
        assertTrue(api.aborted.isEmpty());
    }

    @Test
    public void cancel_abortsBestEffortAndDeletesTheRow() throws Exception {
        sessions.put(storedRow("upX", 250, new TreeMap<>()));
        uploader().cancel(JOB, "ws1", sessions);
        assertEquals(Collections.singletonList("upX"), api.aborted);
        assertNull(sessions.get(JOB));
    }

    @Test
    public void cancel_stillDeletesTheRowWhenTheAbortFails() throws Exception {
        sessions.put(storedRow("upX", 250, new TreeMap<>()));
        FakeApi failing = new FakeApi() {
            @Override
            public void abort(String uploadId, String workspaceId) throws PublishException {
                throw new PublishException(PublishException.Kind.NETWORK);
            }
        };
        new DirectUploader(failing, new OkHttpClient(), sleeps::add, clock::get)
                .cancel(JOB, "ws1", sessions);
        assertNull(sessions.get(JOB));
    }

    @Test
    public void cancel_withoutASessionIsANoOp() {
        uploader().cancel(JOB, "ws1", sessions);
        assertTrue(api.aborted.isEmpty());
    }

    @Test
    public void missingFile_isAFileFailure() {
        File gone = new File(file.getParentFile(), "nope.mp4");
        PublishException e = failure(() -> uploader().upload(JOB, gone, null, sessions, null,
                cancelled::get));
        assertEquals(PublishException.Kind.FILE, e.kind);
    }

    @Test
    public void r2Client_hasNoInterceptorsAndNoAuthenticator() {
        OkHttpClient c = DirectUploader.newR2Client();
        assertTrue(c.interceptors().isEmpty());
        assertTrue(c.networkInterceptors().isEmpty());
        assertEquals(okhttp3.Authenticator.NONE, c.authenticator());
        assertEquals(0, c.callTimeoutMillis());
    }
}
