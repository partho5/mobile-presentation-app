package com.jovoc.facecampresentationrecorder.contentplan;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PlanRefreshPolicyTest {

    @Test
    public void outsideTheFreshWindow_alwaysFetches() {
        assertTrue(PlanRefreshPolicy.shouldFetchWeek(false, false, "s1", "s1"));
        assertTrue(PlanRefreshPolicy.shouldFetchWeek(false, false, "s1", null));
        assertTrue(PlanRefreshPolicy.shouldFetchWeek(false, false, null, null));
    }

    @Test
    public void force_alwaysFetches() {
        assertTrue(PlanRefreshPolicy.shouldFetchWeek(true, true, "s1", "s1"));
        assertTrue(PlanRefreshPolicy.shouldFetchWeek(true, true, "s1", null));
    }

    @Test
    public void fresh_sameStamp_doesNotFetch() {
        assertFalse(PlanRefreshPolicy.shouldFetchWeek(true, false, "s1", "s1"));
    }

    @Test
    public void fresh_changedStamp_fetches() {
        assertTrue(PlanRefreshPolicy.shouldFetchWeek(true, false, "s1", "s2"));
    }

    @Test
    public void fresh_noStoredStamp_fetches() {
        assertTrue(PlanRefreshPolicy.shouldFetchWeek(true, false, null, "s2"));
    }

    @Test
    public void fresh_stampFailed_keepsTheCache() {
        assertFalse(PlanRefreshPolicy.shouldFetchWeek(true, false, "s1", null));
        assertFalse(PlanRefreshPolicy.shouldFetchWeek(true, false, null, null));
    }
}
