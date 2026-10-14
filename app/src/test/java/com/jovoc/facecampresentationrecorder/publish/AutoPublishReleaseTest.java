package com.jovoc.facecampresentationrecorder.publish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.jovoc.facecampresentationrecorder.db.PublishJob;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Releasing a waiting auto-publish: the undo delay is applied once, on every road into the queue. */
public class AutoPublishReleaseTest {

    private static final long NOW = 9_000_000L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final Map<Long, PublishJob> rows = new HashMap<>();
    /** Every call to the scheduler as {jobId, delayMs}. */
    private final List<long[]> scheduled = new ArrayList<>();
    private File video;

    private final AutoPublishRelease.Jobs jobs = new AutoPublishRelease.Jobs() {
        @Override
        public void update(PublishJob job) {
            if (rows.containsKey(job.id)) rows.put(job.id, job);
        }

        @Override
        public void delete(long id) {
            rows.remove(id);
        }
    };
    private final AutoPublishRelease.Scheduler scheduler = (id, delay) -> scheduled.add(new long[]{id, delay});

    @Before
    public void setUp() throws Exception {
        video = tmp.newFile("rec.mp4");
        try (FileOutputStream out = new FileOutputStream(video)) {
            out.write(new byte[1024]);
        }
    }

    private PublishJob waiting() {
        PublishJob j = new PublishJob();
        j.id = 3;
        j.filePath = "/old/path.mp4";
        j.mode = PublishJob.MODE_AUTO;
        j.status = PublishJob.WAITING_PROCESSING;
        rows.put(j.id, j);
        return j;
    }

    /** What the startup sweep does: ask the store for waiting rows, release each. */
    private void sweep(int undoSeconds) {
        for (PublishJob j : new ArrayList<>(rows.values())) {
            if (PublishJob.WAITING_PROCESSING.equals(j.status)) {
                AutoPublishRelease.release(j, video, undoSeconds, NOW, jobs, scheduler);
            }
        }
    }

    @Test
    public void delayIsAppliedAndTheDeadlineIsStoredOnTheRow() {
        PublishJob j = waiting();
        AutoPublishRelease.Result r = AutoPublishRelease.release(j, video, 30, NOW, jobs, scheduler);

        assertEquals(AutoPublishRelease.Result.QUEUED_DELAYED, r);
        assertEquals(1, scheduled.size());
        assertEquals(3L, scheduled.get(0)[0]);
        assertEquals(30_000L, scheduled.get(0)[1]);
        PublishJob saved = rows.get(3L);
        assertEquals(PublishJob.QUEUED, saved.status);
        assertEquals(NOW + 30_000L, saved.scheduleAt);
        assertEquals(video.getAbsolutePath(), saved.filePath);
        assertEquals(30, AutoPublishUndo.secondsLeft(saved, NOW));
        assertEquals(10, AutoPublishUndo.secondsLeft(saved, NOW + 20_000L));
    }

    @Test
    public void sixtySecondsIsSixtyThousandMillis() {
        AutoPublishRelease.release(waiting(), video, 60, NOW, jobs, scheduler);
        assertEquals(60_000L, scheduled.get(0)[1]);
    }

    @Test
    public void offStartsAtOnceWithNoDelayAndNoWindow() {
        PublishJob j = waiting();
        AutoPublishRelease.Result r = AutoPublishRelease.release(j, video, 0, NOW, jobs, scheduler);

        assertEquals(AutoPublishRelease.Result.QUEUED_NOW, r);
        assertEquals(0L, scheduled.get(0)[1]);
        assertEquals(0L, rows.get(3L).scheduleAt);
        assertEquals(0, AutoPublishUndo.secondsLeft(rows.get(3L), NOW));
    }

    @Test
    public void aSweepAfterTheReleaseSchedulesNothingMore() {
        PublishJob j = waiting();
        AutoPublishRelease.release(j, video, 30, NOW, jobs, scheduler);
        assertEquals(1, scheduled.size());

        // Startup sweep / Saved Recordings opening: the row is QUEUED now, not WAITING.
        sweep(30);
        sweep(30);
        assertEquals(1, scheduled.size());
        assertEquals(NOW + 30_000L, rows.get(3L).scheduleAt);

        // Even a stale copy of the row that already moved on is refused.
        assertEquals(AutoPublishRelease.Result.NOT_WAITING,
                AutoPublishRelease.release(rows.get(3L), video, 30, NOW + 5_000L, jobs, scheduler));
        assertEquals(1, scheduled.size());
    }

    @Test
    public void aJobReleasedByTheSweepRespectsTheDelayToo() {
        waiting();
        sweep(60);
        assertEquals(1, scheduled.size());
        assertEquals(60_000L, scheduled.get(0)[1]);
        assertEquals(NOW + 60_000L, rows.get(3L).scheduleAt);
    }

    @Test
    public void aRecordingDeletedMeanwhileIsDroppedWithoutScheduling() {
        PublishJob j = waiting();
        File gone = new File(tmp.getRoot(), "deleted.mp4");
        AutoPublishRelease.Result r = AutoPublishRelease.release(j, gone, 30, NOW, jobs, scheduler);

        assertEquals(AutoPublishRelease.Result.DROPPED, r);
        assertTrue(scheduled.isEmpty());
        assertTrue(rows.isEmpty());
    }

    @Test
    public void aCancelledJobIsNotReleasedAgain() {
        PublishJob j = waiting();
        rows.remove(3L); // the user cancelled while it waited for processing
        // The sweep reads rows, not the stale object, so nothing is found to release.
        sweep(30);
        assertTrue(scheduled.isEmpty());
        assertEquals(PublishJob.WAITING_PROCESSING, j.status);
    }

    @Test
    public void aRowWrittenJustNowIsSettlingSoReconcileSkipsIt() {
        assertTrue(AutoPublishRelease.isSettling(NOW, NOW));
        assertTrue(AutoPublishRelease.isSettling(NOW - 9_999L, NOW));
        assertFalse(AutoPublishRelease.isSettling(NOW - 10_000L, NOW));
        assertFalse(AutoPublishRelease.isSettling(NOW - 60_000L, NOW));
    }
}
