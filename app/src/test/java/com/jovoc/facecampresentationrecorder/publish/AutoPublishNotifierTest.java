package com.jovoc.facecampresentationrecorder.publish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.jovoc.facecampresentationrecorder.db.PublishJob;

import org.junit.Test;

public class AutoPublishNotifierTest {

    @Test
    public void countsThePostIdsOfTheFinishedJob() {
        assertEquals(0, AutoPublishNotifier.countIds(null));
        assertEquals(0, AutoPublishNotifier.countIds(""));
        assertEquals(1, AutoPublishNotifier.countIds("p1"));
        assertEquals(3, AutoPublishNotifier.countIds("p1,p2, p3"));
    }

    private static PublishJob job(String status, String aiState) {
        PublishJob j = new PublishJob();
        j.status = status;
        j.aiCaptionState = aiState;
        return j;
    }

    @Test
    public void topUpIsOfferedOnlyForAPostedJobWithoutCreditsForCaptions() {
        assertTrue(AutoPublishNotifier.creditsSkipped(job(PublishJob.DONE, PublishJob.AI_SKIPPED_CREDITS)));
        assertFalse(AutoPublishNotifier.creditsSkipped(job(PublishJob.DONE, PublishJob.AI_READY)));
        assertFalse(AutoPublishNotifier.creditsSkipped(job(PublishJob.DONE, PublishJob.AI_UNAVAILABLE)));
        assertFalse(AutoPublishNotifier.creditsSkipped(job(PublishJob.FAILED, PublishJob.AI_SKIPPED_CREDITS)));
        assertFalse(AutoPublishNotifier.creditsSkipped(null));
    }
}
