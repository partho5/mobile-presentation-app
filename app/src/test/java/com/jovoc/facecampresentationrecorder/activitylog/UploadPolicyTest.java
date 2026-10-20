package com.jovoc.facecampresentationrecorder.activitylog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UploadPolicyTest {

    private static final long NOW = 10L * 24 * 60 * 60 * 1000;
    private static final long MIN = 60_000L;
    private static final long HOUR = 60 * MIN;
    private static final long DAY = 24 * HOUR;

    @Test
    public void nothingWaitingIsNeverDue() {
        assertFalse(UploadPolicy.isDue(0, NOW - 5 * DAY, NOW));
    }

    @Test
    public void nineEventsAnHourAfterTheLastSendWait() {
        assertFalse(UploadPolicy.isDue(9, NOW - HOUR, NOW));
    }

    @Test
    public void tenEventsInsideTheCooldownWait() {
        assertFalse(UploadPolicy.isDue(10, NOW - 14 * MIN, NOW));
    }

    @Test
    public void tenEventsAfterTheCooldownAreSent() {
        assertTrue(UploadPolicy.isDue(10, NOW - 15 * MIN, NOW));
        assertTrue(UploadPolicy.isDue(250, NOW - HOUR, NOW));
    }

    @Test
    public void aSingleEventWaitsUntilThreeDaysHavePassed() {
        assertFalse(UploadPolicy.isDue(1, NOW - (3 * DAY - MIN), NOW));
        assertTrue(UploadPolicy.isDue(1, NOW - 3 * DAY, NOW));
        assertTrue(UploadPolicy.isDue(1, NOW - 9 * DAY, NOW));
    }

    @Test
    public void aClockThatWentBackFlushesOnce() {
        assertTrue(UploadPolicy.isDue(1, NOW + DAY, NOW));
    }

    @Test
    public void delayIsTheRestOfTheCooldown() {
        assertEquals(5 * MIN, UploadPolicy.delayUntilCountTrigger(NOW - 10 * MIN, NOW));
        assertEquals(0, UploadPolicy.delayUntilCountTrigger(NOW - HOUR, NOW));
        assertEquals(0, UploadPolicy.delayUntilCountTrigger(NOW + DAY, NOW));
    }
}
