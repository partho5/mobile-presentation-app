package com.jovoc.facecampresentationrecorder.publish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.jovoc.facecampresentationrecorder.db.PublishJob;
import com.jovoc.facecampresentationrecorder.ui.WebLinks;

import org.junit.Test;

/** The "Set up on the web" action of a card whose auto-publish failed for lack of a preset. */
public class PublishLabelsTest {

    private static PublishJob failed(String kind, String detail) {
        PublishJob j = new PublishJob();
        j.status = PublishJob.FAILED;
        j.errorKind = kind;
        j.errorDetail = detail;
        return j;
    }

    @Test
    public void notConfiguredOpensTheServersSetupLink() {
        PublishJob j = failed("AUTO_NOT_CONFIGURED", "https://app.example.com/w/1/settings?tab=sets");
        assertEquals("https://app.example.com/w/1/settings?tab=sets", PublishLabels.setupUrl(j));
    }

    @Test
    public void notConfiguredWithoutALinkFallsBackToTheSetsPage() {
        assertEquals(WebLinks.setsUrl(), PublishLabels.setupUrl(failed("AUTO_NOT_CONFIGURED", null)));
        // A detail that is not a web link is never handed to the browser.
        assertEquals(WebLinks.setsUrl(),
                PublishLabels.setupUrl(failed("AUTO_NOT_CONFIGURED", "javascript:alert(1)")));
    }

    @Test
    public void otherJobsHaveNoSetupAction() {
        assertNull(PublishLabels.setupUrl(null));
        assertNull(PublishLabels.setupUrl(failed("NETWORK", null)));
        PublishJob done = failed("AUTO_NOT_CONFIGURED", null);
        done.status = PublishJob.DONE;
        assertNull(PublishLabels.setupUrl(done));
    }
}
