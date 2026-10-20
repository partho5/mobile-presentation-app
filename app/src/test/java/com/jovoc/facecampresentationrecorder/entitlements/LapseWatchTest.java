package com.jovoc.facecampresentationrecorder.entitlements;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class LapseWatchTest {

    private static final String SCOPE = "s1|w1";

    private final LapseWatch watch = new LapseWatch(new TokenFixture.MemoryKv());

    @Test
    public void firstLookOnlyRecordsThePlan() {
        assertNull(watch.check("pro", true, SCOPE));
        // and the baseline is Pro: a verified drop is now a lapse
        assertNotNull(watch.check("free", true, SCOPE));
    }

    @Test
    public void aVerifiedDropToFreeIsReportedOnce() {
        watch.check("pro", true, SCOPE);

        LapseWatch.Lapse lapse = watch.check("free", true, SCOPE);

        assertEquals("pro", lapse.endedPlan);
        assertEquals("free", lapse.nowPlan);
        assertNull(watch.check("free", true, SCOPE));
    }

    @Test
    public void aDropToAnotherPaidPlanNamesBothPlans() {
        watch.check("agency", true, SCOPE);

        LapseWatch.Lapse lapse = watch.check("starter", true, SCOPE);

        assertEquals("agency", lapse.endedPlan);
        assertEquals("starter", lapse.nowPlan);
    }

    @Test
    public void aTokenThatMerelyRanOutSaysNothingYet() {
        watch.check("pro", true, SCOPE);

        // Offline for too long: no token counts, so the plan reads Free but nothing is known.
        assertNull(watch.check("free", false, SCOPE));
        assertNull(watch.check("free", false, SCOPE));

        // The server then confirms Free: now it is a lapse.
        assertNotNull(watch.check("free", true, SCOPE));
    }

    @Test
    public void theServerConfirmingTheSamePlanAfterAnOutageIsNotALapse() {
        watch.check("pro", true, SCOPE);
        watch.check("free", false, SCOPE);

        assertNull(watch.check("pro", true, SCOPE));
        assertNotNull(watch.check("free", true, SCOPE));
    }

    @Test
    public void goingUpIsNeverALapse() {
        watch.check("free", true, SCOPE);

        assertNull(watch.check("starter", true, SCOPE));
        assertNull(watch.check("pro", true, SCOPE));
        assertNotNull(watch.check("starter", true, SCOPE));
    }

    @Test
    public void signingOutOrSwitchingWorkspaceStartsAFreshBaseline() {
        watch.check("pro", true, SCOPE);

        // Signed out: another scope, Free is not a lapse.
        assertNull(watch.check("free", true, "|"));
        // Back in the same account and workspace later: the plan was Free in between.
        assertNull(watch.check("pro", true, SCOPE));
        // A workspace on a lower plan is not a lapse either.
        assertNull(watch.check("free", true, "s1|w2"));
    }

    @Test
    public void aNewScopeWithoutAVerifiedTokenStartsFromFree() {
        watch.check("pro", true, SCOPE);

        assertNull(watch.check("free", false, "s2|"));
        // The new account's real plan arrives: a rise, not a lapse.
        assertNull(watch.check("pro", true, "s2|"));
    }

    @Test
    public void plansTheAppDoesNotKnowAreNeverReported() {
        watch.check("enterprise", true, SCOPE);
        assertNull(watch.check("free", true, SCOPE));

        watch.check("pro", true, SCOPE);
        assertNull(watch.check("enterprise", true, SCOPE));
    }
}
