package com.jovoc.facecampresentationrecorder.publish;

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
    public void otherJobsHaveNoSetupAction() {
        assertFalse(PublishLabels.needsSetup(null));
        assertFalse(PublishLabels.needsSetup(failed("NETWORK", null)));
        PublishJob done = failed("AUTO_NOT_CONFIGURED", null);
        done.status = PublishJob.DONE;
        assertFalse(PublishLabels.needsSetup(done));
    }
}
