package com.jovoc.facecampresentationrecorder.publish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.jovoc.facecampresentationrecorder.db.PublishJob;

import org.junit.Test;

/** The undo window numbers: choices, remaining seconds and when the card has to tick. */
public class AutoPublishUndoTest {

    private static final long NOW = 5_000_000L;

    private static PublishJob queuedAuto(long deadline) {
        PublishJob j = new PublishJob();
        j.mode = PublishJob.MODE_AUTO;
        j.status = PublishJob.QUEUED;
        j.scheduleAt = deadline;
        return j;
    }

    @Test
    public void onlyOffThirtyAndSixtyAreOffered_everythingElseIsTheDefault() {
        assertEquals(0, AutoPublishUndo.normalize(0));
        assertEquals(30, AutoPublishUndo.normalize(30));
        assertEquals(60, AutoPublishUndo.normalize(60));
        assertEquals(30, AutoPublishUndo.normalize(45));
        assertEquals(30, AutoPublishUndo.normalize(-1));
        assertEquals(30, AutoPublishUndo.DEFAULT_SECONDS);
    }

    @Test
    public void delayIsMillisecondsAndZeroWhenOff() {
        assertEquals(30_000L, AutoPublishUndo.delayMillis(30));
        assertEquals(60_000L, AutoPublishUndo.delayMillis(60));
        assertEquals(0L, AutoPublishUndo.delayMillis(0));
    }

    @Test
    public void secondsLeftRoundsUpAndEndsAtTheDeadline() {
        assertEquals(30, AutoPublishUndo.secondsLeft(queuedAuto(NOW + 30_000), NOW));
        assertEquals(30, AutoPublishUndo.secondsLeft(queuedAuto(NOW + 29_001), NOW));
        assertEquals(1, AutoPublishUndo.secondsLeft(queuedAuto(NOW + 1), NOW));
        assertEquals(0, AutoPublishUndo.secondsLeft(queuedAuto(NOW), NOW));
        assertEquals(0, AutoPublishUndo.secondsLeft(queuedAuto(NOW - 5), NOW));
        // Off: no deadline stored.
        assertEquals(0, AutoPublishUndo.secondsLeft(queuedAuto(0), NOW));
    }

    @Test
    public void aClockMovedBackNeverShowsMoreThanTheLongestSetting() {
        assertEquals(60, AutoPublishUndo.secondsLeft(queuedAuto(NOW + 3_600_000L), NOW));
    }

    @Test
    public void onlyAQueuedErrorFreeAutoJobCounts() {
        PublishJob manual = queuedAuto(NOW + 10_000);
        manual.mode = PublishJob.MODE_NOW;
        assertEquals(0, AutoPublishUndo.secondsLeft(manual, NOW));

        PublishJob uploading = queuedAuto(NOW + 10_000);
        uploading.status = PublishJob.UPLOADING;
        assertEquals(0, AutoPublishUndo.secondsLeft(uploading, NOW));

        PublishJob waitingNet = queuedAuto(NOW + 10_000);
        waitingNet.errorKind = "NETWORK";
        assertEquals(0, AutoPublishUndo.secondsLeft(waitingNet, NOW));

        assertEquals(0, AutoPublishUndo.secondsLeft(null, NOW));
    }

    @Test
    public void tickContinuesOneBeatPastTheDeadlineSoTheCardMovesOn() {
        PublishJob j = queuedAuto(NOW + 2_000);
        assertTrue(AutoPublishUndo.needsTick(j, NOW));
        assertTrue(AutoPublishUndo.needsTick(j, NOW + 2_000));
        assertTrue(AutoPublishUndo.needsTick(j, NOW + 3_000));
        assertFalse(AutoPublishUndo.needsTick(j, NOW + 4_000));
        assertFalse(AutoPublishUndo.needsTick(queuedAuto(0), NOW));
    }
}
