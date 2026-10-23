package com.jovoc.facecampresentationrecorder.publish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jovoc.facecampresentationrecorder.db.PublishJob;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * {@link CaptionFlow} on its own, with a real thread A: a passing error is tried again, a final one
 * is not, and a thread that is late or given up writes nothing after the wait is over.
 */
public class CaptionFlowTest {

    private final AtomicLong clock = new AtomicLong(0L);
    /** How far the flow's clock moves on every read. */
    private final AtomicLong tick = new AtomicLong(1_000L);
    private final List<Long> pauses = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger uploads = new AtomicInteger();
    private final AtomicInteger creates = new AtomicInteger();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    /** Counted down on every discard: the finish() one and thread A's own at the end. */
    private final CountDownLatch discards = new CountDownLatch(2);

    private final List<PublishException> uploadFailures = new ArrayList<>();
    private final List<PublishException> createFailures = new ArrayList<>();
    private CountDownLatch uploadEntered;
    private CountDownLatch uploadRelease;
    private boolean swallowInterrupt;
    private CountDownLatch extractEntered;
    private CountDownLatch extractRelease;

    private final PublishApi api = new PublishApi(null) {
        @Override
        public AiCaptionCreated createCaption(PublishJob job, String audioUploadId)
                throws PublishException {
            creates.incrementAndGet();
            if (!createFailures.isEmpty()) throw createFailures.remove(0);
            return new AiCaptionCreated("cap1", false);
        }

        @Override
        public AiCaptionStatus captionStatus(String captionId, String workspaceId) {
            return new AiCaptionStatus(AiCaptionStatus.READY);
        }
    };

    private final CaptionFlow.Audio audio = new CaptionFlow.Audio() {
        @Override
        public File extract(long jobId, File video) {
            if (extractEntered != null) {
                extractEntered.countDown();
                awaitQuietly(extractRelease);
            }
            return new File("x.wav");
        }

        @Override
        public void discard(long jobId) {
            discards.countDown();
        }
    };

    private final CaptionFlow.AudioUpload upload = new CaptionFlow.AudioUpload() {
        @Override
        public String upload(long jobId, File wav, String ws, BooleanSupplier c) throws PublishException {
            uploads.incrementAndGet();
            if (uploadEntered != null) {
                uploadEntered.countDown();
                if (swallowInterrupt) {
                    awaitQuietly(uploadRelease);
                } else {
                    try {
                        uploadRelease.await();
                    } catch (InterruptedException e) {
                        throw new PublishException(PublishException.Kind.CANCELLED);
                    }
                }
            }
            if (!uploadFailures.isEmpty()) throw uploadFailures.remove(0);
            return "audio1";
        }

        @Override
        public void finish(long jobId) {
        }
    };

    private final CaptionFlow flow = new CaptionFlow(api, audio, upload,
            () -> clock.addAndGet(tick.get()), ms -> pauses.add(ms), () -> { });

    /** Waits for {@code latch} and ignores interrupts, like a call stuck in a slow socket. */
    private static void awaitQuietly(CountDownLatch latch) {
        while (true) {
            try {
                latch.await();
                return;
            } catch (InterruptedException ignored) {
                // keep waiting
            }
        }
    }

    private static PublishJob job() {
        PublishJob j = new PublishJob();
        j.id = 1;
        j.filePath = "video.mp4";
        j.workspaceId = "w";
        j.mode = PublishJob.MODE_AUTO;
        j.aiCaptionState = PublishJob.AI_EXTRACTING;
        return j;
    }

    private static PublishException failure(PublishException.Kind kind) {
        return new PublishException(kind);
    }

    @Test
    public void passingUploadErrorsAreTriedAgainWithGrowingPauses() throws Exception {
        uploadFailures.addAll(Arrays.asList(failure(PublishException.Kind.NETWORK),
                failure(PublishException.Kind.SERVER)));
        PublishJob j = job();
        CaptionFlow.Run run = flow.start(j, cancelled::get, x -> { });
        run.finish();
        assertEquals(3, uploads.get());
        assertEquals(Arrays.asList(2_000L, 4_000L), pauses);
        assertEquals("cap1", j.aiCaptionId);
        assertEquals(PublishJob.AI_READY, j.aiCaptionState);
    }

    @Test
    public void createIsTriedAgainThenGivenUpAfterTheLastTry() throws Exception {
        for (int i = 0; i < 10; i++) createFailures.add(failure(PublishException.Kind.SERVER));
        PublishJob j = job();
        CaptionFlow.Run run = flow.start(j, cancelled::get, x -> { });
        run.finish();
        assertEquals(CaptionFlow.CALL_ATTEMPTS, creates.get());
        assertEquals(Arrays.asList(2_000L, 4_000L, 8_000L), pauses);
        assertEquals(PublishJob.AI_UNAVAILABLE, j.aiCaptionState);
        assertNull(j.aiCaptionId);
    }

    @Test
    public void aFinalErrorIsNotTriedAgain() throws Exception {
        uploadFailures.add(failure(PublishException.Kind.REJECTED));
        PublishJob j = job();
        flow.start(j, cancelled::get, x -> { }).finish();
        assertEquals(1, uploads.get());
        assertTrue(pauses.isEmpty());
        assertEquals(PublishJob.AI_UNAVAILABLE, j.aiCaptionState);
    }

    @Test
    public void noCreditsIsNotTriedAgain() throws Exception {
        createFailures.add(failure(PublishException.Kind.INSUFFICIENT_CREDITS));
        PublishJob j = job();
        flow.start(j, cancelled::get, x -> { }).finish();
        assertEquals(1, creates.get());
        assertEquals(PublishJob.AI_SKIPPED_CREDITS, j.aiCaptionState);
    }

    @Test
    public void aCancelDuringAPauseEndsThreadAQuietly() throws Exception {
        uploadFailures.add(failure(PublishException.Kind.NETWORK));
        CaptionFlow slept = new CaptionFlow(api, audio, upload, () -> clock.addAndGet(tick.get()),
                ms -> cancelled.set(true), () -> { });
        PublishJob j = job();
        CaptionFlow.Run run = slept.start(j, cancelled::get, x -> { });
        assertTrue(awaitDiscard(1));
        assertEquals(1, uploads.get());
        assertEquals(0, creates.get());
        // A cancel is not a failure of the captions: the state is left as it was.
        assertEquals(PublishJob.AI_UPLOADING, j.aiCaptionState);
        run.abandon();
    }

    @Test
    public void aThreadStillBusyAtTheDeadlineIsGivenUpAndWritesNothingLater() throws Exception {
        uploadEntered = new CountDownLatch(1);
        uploadRelease = new CountDownLatch(1);
        swallowInterrupt = true; // a call that does not notice the interrupt
        PublishJob j = job();
        CaptionFlow.Run run = flow.start(j, cancelled::get, x -> { });
        assertTrue(uploadEntered.await(5, TimeUnit.SECONDS));

        tick.set(60_000L); // the wait is over after two reads
        run.finish();
        assertEquals(PublishJob.AI_TIMEOUT, j.aiCaptionState);
        assertNull(j.aiCaptionId);

        uploadRelease.countDown(); // the upload finishes late
        assertTrue(discards.await(5, TimeUnit.SECONDS));
        assertEquals(0, creates.get());
        assertEquals(PublishJob.AI_TIMEOUT, j.aiCaptionState);
        assertNull(j.aiCaptionId);
    }

    @Test
    public void aThreadThatNoticesTheInterruptEndsWithoutWritingAState() throws Exception {
        uploadEntered = new CountDownLatch(1);
        uploadRelease = new CountDownLatch(1);
        PublishJob j = job();
        CaptionFlow.Run run = flow.start(j, cancelled::get, x -> { });
        assertTrue(uploadEntered.await(5, TimeUnit.SECONDS));

        tick.set(60_000L);
        run.finish();
        assertTrue(discards.await(5, TimeUnit.SECONDS));
        assertEquals(PublishJob.AI_TIMEOUT, j.aiCaptionState);
        assertEquals(0, creates.get());
    }

    @Test
    public void abandonedBeforeTheAudioIsReadyUploadsNothing() throws Exception {
        extractEntered = new CountDownLatch(1);
        extractRelease = new CountDownLatch(1);
        PublishJob j = job();
        CaptionFlow.Run run = flow.start(j, cancelled::get, x -> { });
        assertTrue(extractEntered.await(5, TimeUnit.SECONDS));

        run.abandon(); // the job ended (cancel, failure) while the audio was being made
        extractRelease.countDown();
        assertTrue(awaitDiscard(1));
        assertEquals(0, uploads.get());
        assertEquals(0, creates.get());
        assertEquals(PublishJob.AI_EXTRACTING, j.aiCaptionState);
    }

    /** True once {@code count} discards have happened (thread A's own comes last). */
    private boolean awaitDiscard(int count) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (2 - discards.getCount() < count) {
            if (System.nanoTime() > end) return false;
            Thread.sleep(5);
        }
        return true;
    }
}
