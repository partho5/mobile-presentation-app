package com.jovoc.facecampresentationrecorder.publish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jovoc.facecampresentationrecorder.auth.ApiClient;
import com.jovoc.facecampresentationrecorder.db.PublishJob;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * AI captions inside the publish pipeline, against a fake API (no network, no ffmpeg): the publish
 * body is the same on every retry, a caption problem never fails the job, a skipped / late caption
 * publishes without waiting longer.
 */
public class PublishPipelineCaptionTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final Map<Long, PublishJob> rows = new HashMap<>();
    private final AtomicLong pipelineClock = new AtomicLong(1_000_000L);
    private final AtomicLong flowClock = new AtomicLong(0L);
    private final AtomicInteger discards = new AtomicInteger();
    private final AtomicInteger needsPlan = new AtomicInteger();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private File video;
    private FakeApi api;
    private boolean hasAudio = true;
    private PublishPipeline pipeline;

    /** Records what the publish calls would have sent. */
    private static class FakeApi extends PublishApi {
        final List<String> postBodies = new ArrayList<>();
        final List<String> captionBodies = new ArrayList<>();
        final List<String> captionKeys = new ArrayList<>();
        final AtomicInteger statusCalls = new AtomicInteger();
        final List<PublishException> postFailures = new ArrayList<>();
        PublishException createFailure;
        boolean skipped;
        String[] statuses = {AiCaptionStatus.READY};

        FakeApi() {
            super(null);
        }

        @Override
        public Media upload(File file, String workspaceId, ApiClient.ProgressListener progress,
                            BooleanSupplier cancelled) {
            return new Media("m1", "https://cdn.test/x.mp4");
        }

        @Override
        public Posted createPost(PublishJob job, long nowMs) throws PublishException {
            postBodies.add(PublishRequestBuilder.createPostBody(job, nowMs));
            if (!postFailures.isEmpty()) throw postFailures.remove(0);
            return new Posted(Collections.singletonList("p1"), Collections.singletonList("c1"));
        }

        @Override
        public Posted autoPublish(PublishJob job) throws PublishException {
            postBodies.add(PublishRequestBuilder.autoPublishBody(job));
            if (!postFailures.isEmpty()) throw postFailures.remove(0);
            return new Posted(Collections.singletonList("p1"), Collections.singletonList("c1"));
        }

        @Override
        public AiCaptionCreated createCaption(PublishJob job, String audioUploadId)
                throws PublishException {
            captionBodies.add(PublishRequestBuilder.aiCaptionBody(job, audioUploadId));
            captionKeys.add(job.aiCaptionKey);
            if (createFailure != null) throw createFailure;
            return skipped ? new AiCaptionCreated(null, true) : new AiCaptionCreated("cap1", false);
        }

        @Override
        public AiCaptionStatus captionStatus(String captionId, String workspaceId) {
            int i = Math.min(statusCalls.getAndIncrement(), statuses.length - 1);
            return new AiCaptionStatus(statuses[i]);
        }
    }

    @Before
    public void setUp() throws Exception {
        video = tmp.newFile("demo.mp4");
        try (FileOutputStream out = new FileOutputStream(video)) {
            out.write(new byte[1024]);
        }
        api = new FakeApi();
        flowClock.set(0L);
        CaptionFlow flow = new CaptionFlow(api,
                new CaptionFlow.Audio() {
                    @Override
                    public File extract(long jobId, File v) {
                        return hasAudio ? new File(v.getParentFile(), "x.wav") : null;
                    }

                    @Override
                    public void discard(long jobId) {
                        discards.incrementAndGet();
                    }
                },
                new CaptionFlow.AudioUpload() {
                    @Override
                    public String upload(long jobId, File wav, String ws, BooleanSupplier c) {
                        return "audio1";
                    }

                    @Override
                    public void finish(long jobId) {
                    }
                },
                () -> flowClock.addAndGet(1_000L), ms -> flowClock.addAndGet(ms), needsPlan::incrementAndGet);
        pipeline = new PublishPipeline(api, null, null, new PublishPipeline.Store() {
            @Override
            public PublishJob load(long id) {
                return rows.get(id);
            }

            @Override
            public void save(PublishJob job) {
                if (rows.containsKey(job.id)) rows.put(job.id, job);
            }
        }, job -> { }, cancelled::get, 3, () -> pipelineClock.addAndGet(5_000L), null, flow);
    }

    private PublishJob job(String mode, String aiState) {
        PublishJob j = new PublishJob();
        j.id = 7;
        j.filePath = video.getAbsolutePath();
        j.fileName = video.getName();
        j.caption = "link https://x.test";
        j.mode = mode;
        j.status = PublishJob.QUEUED;
        j.idempotencyKey = "key-1";
        j.aiCaptionState = aiState;
        j.channelsJson = Channel.toJson(Arrays.asList(new Channel("c1", "YT", "youtube", null, false)));
        rows.put(j.id, j);
        return j;
    }

    @Test
    public void readyCaptionIsSentOnTheManualPost() throws Exception {
        PublishJob manual = job(PublishJob.MODE_NOW, PublishJob.AI_EXTRACTING);
        assertEquals(PublishPipeline.Outcome.DONE, pipeline.run(7, 0));
        assertEquals("cap1", new JSONObject(api.postBodies.get(0)).getString("aiCaptionId"));
        assertEquals(PublishJob.AI_READY, manual.aiCaptionState);
        JSONObject create = new JSONObject(api.captionBodies.get(0));
        assertEquals("manual", create.getString("mode"));
        assertEquals("c1", create.getJSONArray("integrationIds").getString(0));
        assertEquals("link https://x.test", create.getString("userText"));
        assertEquals("audio1", create.getString("audioUploadId"));
    }

    @Test
    public void autoBodiesCarryTheCaptionIdAndTitle() throws Exception {
        PublishJob auto = new PublishJob();
        auto.mediaId = "m1";
        auto.mediaPath = "p";
        auto.fileName = "Talk_9x16.mp4";
        auto.mode = PublishJob.MODE_AUTO;
        auto.aiCaptionId = "cap1";
        JSONObject body = new JSONObject(PublishRequestBuilder.autoPublishBody(auto));
        assertEquals("cap1", body.getString("aiCaptionId"));
        JSONObject create = new JSONObject(PublishRequestBuilder.aiCaptionBody(auto, "a1"));
        assertEquals("auto", create.getString("mode"));
        assertEquals("Talk", create.getString("title"));
        assertFalse(create.has("integrationIds"));
        auto.aiCaptionId = PublishJob.AI_CAPTION_NONE;
        assertFalse(new JSONObject(PublishRequestBuilder.autoPublishBody(auto)).has("aiCaptionId"));
    }

    @Test
    public void retryUnderTheSameKeySendsTheSameBodyAndDate() throws Exception {
        job(PublishJob.MODE_NOW, PublishJob.AI_EXTRACTING);
        api.postFailures.add(new PublishException(PublishException.Kind.NETWORK));
        assertEquals(PublishPipeline.Outcome.RETRY, pipeline.run(7, 0));
        assertEquals(PublishPipeline.Outcome.DONE, pipeline.run(7, 1));
        assertEquals(2, api.postBodies.size());
        assertEquals(api.postBodies.get(0), api.postBodies.get(1));
        // the caption was created once, under one stable key
        assertEquals(1, api.captionBodies.size());
        assertNotNull(rows.get(7L).publishDate);
    }

    @Test
    public void captionKeyIsStableAndNotThePublishKey() {
        PublishJob j = job(PublishJob.MODE_NOW, PublishJob.AI_EXTRACTING);
        pipeline.run(7, 0);
        assertNotNull(j.aiCaptionKey);
        assertFalse(j.aiCaptionKey.equals(j.idempotencyKey));
        assertEquals(j.aiCaptionKey, api.captionKeys.get(0));
    }

    @Test
    public void skippedForCreditsPublishesWithoutACaptionId() throws Exception {
        api.skipped = true;
        PublishJob j = job(PublishJob.MODE_NOW, PublishJob.AI_EXTRACTING);
        assertEquals(PublishPipeline.Outcome.DONE, pipeline.run(7, 0));
        assertEquals(PublishJob.AI_SKIPPED_CREDITS, j.aiCaptionState);
        assertEquals(PublishJob.AI_CAPTION_NONE, j.aiCaptionId);
        assertFalse(new JSONObject(api.postBodies.get(0)).has("aiCaptionId"));
        assertEquals(0, api.statusCalls.get());
    }

    @Test
    public void captionFailureNeverFailsTheJob() throws Exception {
        api.createFailure = new PublishException(PublishException.Kind.SERVER);
        PublishJob j = job(PublishJob.MODE_NOW, PublishJob.AI_EXTRACTING);
        assertEquals(PublishPipeline.Outcome.DONE, pipeline.run(7, 0));
        assertEquals(PublishJob.AI_UNAVAILABLE, j.aiCaptionState);
        assertNull(j.errorKind);
        assertEquals(PublishJob.DONE, j.status);
    }

    @Test
    public void planRefusalOnlyRefreshesThePlan() {
        api.createFailure = new PublishException(PublishException.Kind.NEEDS_PLAN);
        PublishJob j = job(PublishJob.MODE_NOW, PublishJob.AI_EXTRACTING);
        assertEquals(PublishPipeline.Outcome.DONE, pipeline.run(7, 0));
        assertEquals(PublishJob.AI_UNAVAILABLE, j.aiCaptionState);
        assertEquals(1, needsPlan.get());
        assertNull(j.errorKind);
    }

    @Test
    public void noSpeechPublishesWithoutTheId() throws Exception {
        api.statuses = new String[]{AiCaptionStatus.PENDING, AiCaptionStatus.NO_SPEECH};
        PublishJob j = job(PublishJob.MODE_NOW, PublishJob.AI_EXTRACTING);
        assertEquals(PublishPipeline.Outcome.DONE, pipeline.run(7, 0));
        assertEquals(PublishJob.AI_NO_SPEECH, j.aiCaptionState);
        assertFalse(new JSONObject(api.postBodies.get(0)).has("aiCaptionId"));
        assertEquals(2, api.statusCalls.get());
    }

    @Test
    public void lateCaptionTimesOutAndPublishesWithoutWaitingLonger() {
        api.statuses = new String[]{AiCaptionStatus.GENERATING};
        PublishJob j = job(PublishJob.MODE_NOW, PublishJob.AI_EXTRACTING);
        assertEquals(PublishPipeline.Outcome.DONE, pipeline.run(7, 0));
        assertEquals(PublishJob.AI_TIMEOUT, j.aiCaptionState);
        assertEquals(1, api.postBodies.size());
        // polls stop at the limit, not an endless loop
        assertTrue(api.statusCalls.get() <= CaptionFlow.CAPTION_WAIT_MS / CaptionFlow.POLL_MS + 2);
    }

    @Test
    public void noAudioTrackSkipsTheCaption() {
        hasAudio = false;
        PublishJob j = job(PublishJob.MODE_NOW, PublishJob.AI_EXTRACTING);
        assertEquals(PublishPipeline.Outcome.DONE, pipeline.run(7, 0));
        assertEquals(PublishJob.AI_NO_AUDIO, j.aiCaptionState);
        assertTrue(api.captionBodies.isEmpty());
    }

    @Test
    public void captionsOffDoNoAudioWork() throws Exception {
        PublishJob j = job(PublishJob.MODE_NOW, PublishJob.AI_OFF);
        assertEquals(PublishPipeline.Outcome.DONE, pipeline.run(7, 0));
        assertTrue(api.captionBodies.isEmpty());
        assertEquals(PublishJob.AI_OFF, j.aiCaptionState);
        assertFalse(new JSONObject(api.postBodies.get(0)).has("aiCaptionId"));
    }

    @Test
    public void extractedAudioIsDeletedWhenThePostFails() {
        api.postFailures.add(new PublishException(PublishException.Kind.REJECTED, 400, "no", -1));
        job(PublishJob.MODE_NOW, PublishJob.AI_EXTRACTING);
        int before = discards.get();
        assertEquals(PublishPipeline.Outcome.FAILED, pipeline.run(7, 0));
        assertTrue(discards.get() > before);
    }

    @Test
    public void extractedAudioIsDeletedOnCancel() {
        job(PublishJob.MODE_NOW, PublishJob.AI_EXTRACTING);
        cancelled.set(true);
        int before = discards.get();
        assertEquals(PublishPipeline.Outcome.GONE, pipeline.run(7, 0));
        assertTrue(discards.get() > before);
    }

    @Test
    public void reusedCaptionIdDoesNotCreateOrChargeAgain() throws Exception {
        PublishJob j = job(PublishJob.MODE_NOW, PublishJob.AI_READY);
        j.aiCaptionId = "cap_precreated";
        assertEquals(PublishPipeline.Outcome.DONE, pipeline.run(7, 0));
        assertTrue("createCaption must not be called when aiCaptionId is already saved",
                api.captionBodies.isEmpty());
        assertEquals(0, api.statusCalls.get());
        assertEquals("cap_precreated", new JSONObject(api.postBodies.get(0)).getString("aiCaptionId"));
    }

    @Test
    public void reusedPendingCaptionIdPollsWithoutCallingCreateAgain() throws Exception {
        api.statuses = new String[]{AiCaptionStatus.READY};
        PublishJob j = job(PublishJob.MODE_NOW, PublishJob.AI_PENDING);
        j.aiCaptionId = "cap_inflight";
        assertEquals(PublishPipeline.Outcome.DONE, pipeline.run(7, 0));
        assertTrue("createCaption must not be called for already-created pending id",
                api.captionBodies.isEmpty());
        assertEquals(1, api.statusCalls.get());
        assertEquals(PublishJob.AI_READY, j.aiCaptionState);
        assertEquals("cap_inflight", new JSONObject(api.postBodies.get(0)).getString("aiCaptionId"));
    }
}
