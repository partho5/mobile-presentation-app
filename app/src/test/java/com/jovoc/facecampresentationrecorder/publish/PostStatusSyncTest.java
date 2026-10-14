package com.jovoc.facecampresentationrecorder.publish;

import static org.junit.Assert.assertEquals;
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
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** Which jobs are asked about, chunking, and how a job's posts combine into one card state. */
public class PostStatusSyncTest {

    private static final long NOW = 100L * 24 * 60 * 60 * 1000;
    private static final long DAY = 24L * 60 * 60 * 1000;

    private MockWebServer server;
    private PublishApi api;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        TokenStore store = new TokenStore(new KeyValueStore() {
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
        store.saveTokens("acc", "ref", "sid", 900, NOW);
        api = new PublishApi(new AuthRepository(
                new ApiClient(server.url("/").toString(), true), store, () -> NOW));
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
    }

    private static PublishJob job(long id, String postIds, long updatedAt) {
        PublishJob j = new PublishJob();
        j.id = id;
        j.status = PublishJob.DONE;
        j.mode = PublishJob.MODE_NOW;
        j.postIds = postIds;
        j.updatedAt = updatedAt;
        return j;
    }

    private static Map<String, PostStatus> answers(PostStatus... all) {
        Map<String, PostStatus> m = new LinkedHashMap<>();
        for (PostStatus s : all) m.put(s.id, s);
        return m;
    }

    private static PostStatus st(String id, PostStatus.State s) {
        return new PostStatus(id, s, null, null);
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    // ---- which jobs are asked about ----

    @Test
    public void due_keepsAcceptedJobsOfTheLastSevenDays_withNoFinalState() {
        PublishJob fresh = job(1, "p1", NOW - DAY);
        PublishJob edge = job(2, "p2", NOW - 7 * DAY);
        PublishJob old = job(3, "p3", NOW - 7 * DAY - 1);
        List<PublishJob> due = PostStatusSync.due(Arrays.asList(fresh, edge, old), NOW);
        assertEquals(Arrays.asList(fresh, edge), due);
    }

    @Test
    public void due_skipsFinalJobsAndJobsThatWereNeverAccepted() {
        PublishJob live = job(1, "p1", NOW);
        live.remoteState = PublishJob.REMOTE_PUBLISHED;
        PublishJob failedRemote = job(2, "p2", NOW);
        failedRemote.remoteState = PublishJob.REMOTE_ERROR;
        PublishJob noPosts = job(3, null, NOW);
        PublishJob blankPosts = job(4, "", NOW);
        PublishJob queued = job(5, "p5", NOW);
        queued.status = PublishJob.QUEUED;
        PublishJob failedLocal = job(6, "p6", NOW);
        failedLocal.status = PublishJob.FAILED;
        assertTrue(PostStatusSync.due(
                Arrays.asList(live, failedRemote, noPosts, blankPosts, queued, failedLocal), NOW).isEmpty());
    }

    @Test
    public void due_aScheduledJobIsCheckedForAWeekAfterItsScheduleTime() {
        PublishJob j = job(1, "p1", NOW - 10 * DAY);
        j.mode = PublishJob.MODE_SCHEDULE;
        j.scheduleAt = NOW - 2 * DAY;
        assertEquals(1, PostStatusSync.due(Arrays.asList(j), NOW).size());
        j.scheduleAt = NOW - 9 * DAY;
        assertTrue(PostStatusSync.due(Arrays.asList(j), NOW).isEmpty());
    }

    // ---- chunking ----

    @Test
    public void chunk_splitsIntoPiecesOfAtMostTheSize() {
        List<Integer> items = new ArrayList<>();
        for (int i = 0; i < 120; i++) items.add(i);
        List<List<Integer>> chunks = PostStatusSync.chunk(items, 50);
        assertEquals(3, chunks.size());
        assertEquals(50, chunks.get(0).size());
        assertEquals(50, chunks.get(1).size());
        assertEquals(20, chunks.get(2).size());
        assertEquals(Integer.valueOf(50), chunks.get(1).get(0));
        assertTrue(PostStatusSync.chunk(new ArrayList<Integer>(), 50).isEmpty());
        assertEquals(1, PostStatusSync.chunk(Arrays.asList(1, 2, 3), 50).size());
    }

    // ---- one job's posts -> one card state ----

    @Test
    public void resolve_allPublished_isLive_withTheFirstLink() {
        PublishJob j = job(1, "a,b", NOW);
        PostStatus a = new PostStatus("a", PostStatus.State.PUBLISHED, "https://y.test/a", null);
        PostStatus b = new PostStatus("b", PostStatus.State.PUBLISHED, "https://t.test/b", null);
        PostStatusSync.Result r = PostStatusSync.resolve(j, answers(a, b));
        assertEquals(PublishJob.REMOTE_PUBLISHED, r.state);
        assertEquals("https://y.test/a", r.url);
    }

    @Test
    public void resolve_publishedWithoutALink_isLiveWithNoUrl() {
        PostStatusSync.Result r = PostStatusSync.resolve(job(1, "a", NOW),
                answers(st("a", PostStatus.State.PUBLISHED)));
        assertEquals(PublishJob.REMOTE_PUBLISHED, r.state);
        assertNull(r.url);
    }

    @Test
    public void resolve_aFailedPost_namesItsChannelAndError() {
        PublishJob j = job(1, "a,b", NOW);
        j.postIntegrations = "c-yt,c-tt";
        j.channelsJson = Channel.toJson(Arrays.asList(
                new Channel("c-yt", "My YouTube", "youtube", null, false),
                new Channel("c-tt", "My TikTok", "tiktok", null, false)));
        PostStatus a = new PostStatus("a", PostStatus.State.PUBLISHED, "https://y.test/a", null);
        PostStatus b = new PostStatus("b", PostStatus.State.ERROR, null, "Video too long");
        PostStatusSync.Result r = PostStatusSync.resolve(j, answers(a, b));
        assertEquals(PublishJob.REMOTE_ERROR, r.state);
        assertEquals("My TikTok", r.channel);
        assertEquals("Video too long", r.error);
        assertNull(r.url);
    }

    @Test
    public void resolve_aFailureWithoutKnownChannel_hasNoChannelName() {
        PublishJob j = job(1, "a", NOW);
        PostStatusSync.Result r = PostStatusSync.resolve(j,
                answers(new PostStatus("a", PostStatus.State.ERROR, null, "boom")));
        assertEquals(PublishJob.REMOTE_ERROR, r.state);
        assertNull(r.channel);
        assertEquals("boom", r.error);
    }

    @Test
    public void resolve_waitsUntilEveryPostIsFinal() {
        PublishJob j = job(1, "a,b", NOW);
        assertNull(PostStatusSync.resolve(j, answers(
                st("a", PostStatus.State.PUBLISHED), st("b", PostStatus.State.QUEUE))));
        assertNull(PostStatusSync.resolve(j, answers(
                st("a", PostStatus.State.ERROR), st("b", PostStatus.State.DRAFT))));
        assertNull("a post the server did not return", PostStatusSync.resolve(j, answers(
                st("a", PostStatus.State.PUBLISHED))));
    }

    @Test
    public void resolve_deletedOnlyWhenEveryPostWasDeleted() {
        PublishJob j = job(1, "a,b", NOW);
        assertEquals(PublishJob.REMOTE_DELETED, PostStatusSync.resolve(j, answers(
                st("a", PostStatus.State.DELETED), st("b", PostStatus.State.DELETED))).state);
        assertEquals(PublishJob.REMOTE_PUBLISHED, PostStatusSync.resolve(j, answers(
                st("a", PostStatus.State.DELETED), st("b", PostStatus.State.PUBLISHED))).state);
    }

    // ---- the network loop ----

    @Test
    public void refresh_asksOnceForAllDueJobs_andReportsOnlyFinalOnes() throws Exception {
        PublishJob live = job(1, "a", NOW);
        PublishJob pending = job(2, "b", NOW);
        PublishJob failed = job(3, "c", NOW);
        server.enqueue(json("[{\"id\":\"a\",\"state\":\"PUBLISHED\",\"releaseURL\":\"https://y.test/a\"},"
                + "{\"id\":\"b\",\"state\":\"QUEUE\"},{\"id\":\"c\",\"state\":\"ERROR\",\"error\":\"nope\"}]"));

        Map<Long, PostStatusSync.Result> got = new HashMap<>();
        int n = PostStatusSync.refresh(api, Arrays.asList(live, pending, failed), NOW,
                (job, r) -> got.put(job.id, r));

        assertEquals(2, n);
        assertEquals(1, server.getRequestCount());
        RecordedRequest req = server.takeRequest();
        assertEquals("GET", req.getMethod());
        assertEquals("/public/v1/posts/status?ids=a,b,c", req.getPath());
        assertEquals("Bearer acc", req.getHeader("Authorization"));
        assertEquals(PublishJob.REMOTE_PUBLISHED, got.get(1L).state);
        assertEquals(PublishJob.REMOTE_ERROR, got.get(3L).state);
        assertNull(got.get(2L));
    }

    @Test
    public void refresh_sendsAtMostFiftyIdsPerCall() throws Exception {
        List<PublishJob> jobs = new ArrayList<>();
        for (int i = 0; i < 120; i++) jobs.add(job(i + 1, "p" + i, NOW));
        for (int i = 0; i < 3; i++) server.enqueue(json("[]"));

        PostStatusSync.refresh(api, jobs, NOW, (job, r) -> { });

        assertEquals(3, server.getRequestCount());
        int total = 0;
        for (int i = 0; i < 3; i++) {
            String path = server.takeRequest().getPath();
            int ids = path.substring(path.indexOf("ids=") + 4).split(",").length;
            assertTrue(ids <= 50);
            total += ids;
        }
        assertEquals(120, total);
    }

    @Test
    public void refresh_keepsEachJobsWorkspace_oneCallPerWorkspace() throws Exception {
        PublishJob a = job(1, "a", NOW);
        a.workspaceId = "w1";
        PublishJob b = job(2, "b", NOW);
        b.workspaceId = "w2";
        PublishJob c = job(3, "c", NOW);
        c.workspaceId = "w1";
        server.enqueue(json("[]"));
        server.enqueue(json("[]"));

        PostStatusSync.refresh(api, Arrays.asList(a, b, c), NOW, (job, r) -> { });

        RecordedRequest r1 = server.takeRequest();
        RecordedRequest r2 = server.takeRequest();
        assertEquals("w1", r1.getHeader("x-workspace-id"));
        assertEquals("/public/v1/posts/status?ids=a,c", r1.getPath());
        assertEquals("w2", r2.getHeader("x-workspace-id"));
        assertEquals("/public/v1/posts/status?ids=b", r2.getPath());
    }

    @Test
    public void refresh_aFailedCall_changesNothing_andDoesNotThrow() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("{}"));
        int n = PostStatusSync.refresh(api, Arrays.asList(job(1, "a", NOW)), NOW,
                (job, r) -> { throw new AssertionError("no result expected"); });
        assertEquals(0, n);
    }

    @Test
    public void refresh_withNothingDue_makesNoCall() {
        PublishJob old = job(1, "a", NOW - 30 * DAY);
        assertEquals(0, PostStatusSync.refresh(api, Arrays.asList(old), NOW, (job, r) -> { }));
        assertEquals(0, server.getRequestCount());
        assertNotNull(api);
    }
}
