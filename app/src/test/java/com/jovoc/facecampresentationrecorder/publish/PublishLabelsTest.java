package com.jovoc.facecampresentationrecorder.publish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.jovoc.facecampresentationrecorder.db.PublishJob;

import org.junit.Test;

/** The "Set up auto upload" action of a card whose auto-publish failed for lack of a setup. */
public class PublishLabelsTest {

    private static PublishJob failed(String kind, String detail) {
        PublishJob j = new PublishJob();
        j.status = PublishJob.FAILED;
        j.errorKind = kind;
        j.errorDetail = detail;
        return j;
    }

    @Test
    public void notConfiguredOffersSetup() {
        assertTrue(PublishLabels.needsSetup(failed("AUTO_NOT_CONFIGURED", null)));
        // An older row may still hold the server's setup link; it is ignored, not a reason to hide.
        assertTrue(PublishLabels.needsSetup(
                failed("AUTO_NOT_CONFIGURED", "https://app.example.com/w/1/settings?tab=sets")));
    }

    @Test
    public void planRefusalOffersTheUpgradeSheetNotSetup() {
        PublishJob job = failed("NEEDS_PLAN", "auto_publish");
        assertTrue(PublishLabels.needsPlan(job));
        assertFalse(PublishLabels.needsSetup(job));
        assertFalse(PublishLabels.needsPlan(null));
        assertFalse(PublishLabels.needsPlan(failed("AUTO_NOT_CONFIGURED", null)));
        job.status = PublishJob.DONE;
        assertFalse(PublishLabels.needsPlan(job));
    }

    @Test
    public void otherJobsHaveNoSetupAction() {
        assertFalse(PublishLabels.needsSetup(null));
        assertFalse(PublishLabels.needsSetup(failed("NETWORK", null)));
        PublishJob done = failed("AUTO_NOT_CONFIGURED", null);
        done.status = PublishJob.DONE;
        assertFalse(PublishLabels.needsSetup(done));
    }

    private static PublishJob captioned(String mode, String remote, String captionId) {
        PublishJob j = new PublishJob();
        j.status = PublishJob.DONE;
        j.mode = mode;
        j.remoteState = remote;
        j.aiCaptionId = captionId;
        return j;
    }

    @Test
    public void captionActionEditWhenScheduledSeeWhenPosted() {
        assertEquals(PublishLabels.CaptionAction.EDIT,
                PublishLabels.captionAction(captioned(PublishJob.MODE_SCHEDULE, null, "c1")));
        assertEquals(PublishLabels.CaptionAction.EDIT,
                PublishLabels.captionAction(captioned(PublishJob.MODE_AUTO_SLOT, null, "c1")));
        assertEquals(PublishLabels.CaptionAction.SEE,
                PublishLabels.captionAction(captioned(PublishJob.MODE_SCHEDULE, PublishJob.REMOTE_PUBLISHED, "c1")));
        assertEquals(PublishLabels.CaptionAction.SEE,
                PublishLabels.captionAction(captioned(PublishJob.MODE_NOW, PublishJob.REMOTE_PUBLISHED, "c1")));
    }

    @Test
    public void captionActionHiddenWithoutCaption() {
        assertEquals(PublishLabels.CaptionAction.NONE, PublishLabels.captionAction(null));
        assertEquals(PublishLabels.CaptionAction.NONE,
                PublishLabels.captionAction(captioned(PublishJob.MODE_SCHEDULE, null, null)));
        assertEquals(PublishLabels.CaptionAction.NONE,
                PublishLabels.captionAction(captioned(PublishJob.MODE_SCHEDULE, null, PublishJob.AI_CAPTION_NONE)));
        PublishJob failed = captioned(PublishJob.MODE_SCHEDULE, null, "c1");
        failed.status = PublishJob.FAILED;
        assertEquals(PublishLabels.CaptionAction.NONE, PublishLabels.captionAction(failed));
    }
}
