package com.jovoc.facecampresentationrecorder.tracker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UploadPolicyTest {

    private static final long NOW = 1_763_712_000_000L;
    private static final long MIN = 60_000L;
    private static final long HOUR = 60 * MIN;

    @Test
    public void noPendingRecordsIsNeverDue() {
        assertFalse(UploadPolicy.isDue(0, NOW - 10 * UploadPolicy.DAY_MS, NOW));
        assertFalse(UploadPolicy.isDue(0, NOW + HOUR, NOW));
    }

    @Test
    public void twentyPendingAnHourAfterSendIsNotDue() {
        assertFalse(UploadPolicy.isDue(20, NOW - HOUR, NOW));
    }

    @Test
    public void twentyOnePendingAnHourAfterSendIsDue() {
        assertTrue(UploadPolicy.isDue(21, NOW - HOUR, NOW));
    }

    @Test
    public void twentyOnePendingFiveMinutesAfterSendIsNotDueBecauseOfTheGap() {
        assertFalse(UploadPolicy.isDue(21, NOW - 5 * MIN, NOW));
    }

    @Test
    public void timeRuleFiresExactlyAtFiveDays() {
        long fiveDays = TrackerConfig.SEND_EVERY_DAYS * UploadPolicy.DAY_MS;
        assertFalse(UploadPolicy.isDue(1, NOW - fiveDays + 1, NOW));
        assertTrue(UploadPolicy.isDue(1, NOW - fiveDays, NOW));
    }

    @Test
    public void clockSetBackIsDue() {
        assertTrue(UploadPolicy.isDue(1, NOW + HOUR, NOW));
    }

    @Test
    public void volumeDelayIsTheRestOfTheGap() {
        assertEquals(10 * MIN, UploadPolicy.delayForVolume(NOW - 5 * MIN, NOW));
    }

    @Test
    public void volumeDelayIsZeroAfterTheGap() {
        assertEquals(0, UploadPolicy.delayForVolume(NOW - 20 * MIN, NOW));
    }
}
