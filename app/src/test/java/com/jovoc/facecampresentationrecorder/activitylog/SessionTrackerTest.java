package com.jovoc.facecampresentationrecorder.activitylog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class SessionTrackerTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final AtomicInteger ids = new AtomicInteger();
    private final SessionTracker tracker =
            new SessionTracker(now::get, () -> "s" + ids.incrementAndGet());

    @Test
    public void firstUseOpensASessionAndKeepsIt() {
        assertEquals("s1", tracker.current());
        assertEquals("s1", tracker.current());
    }

    @Test
    public void shortTripToTheBackgroundKeepsTheSession() {
        String before = tracker.current();
        tracker.onBackground();
        now.addAndGet(SessionTracker.IDLE_MS - 1);
        tracker.onForeground();
        assertEquals(before, tracker.current());
    }

    @Test
    public void longAbsenceStartsANewSession() {
        String before = tracker.current();
        tracker.onBackground();
        now.addAndGet(SessionTracker.IDLE_MS);
        tracker.onForeground();
        assertNotEquals(before, tracker.current());
    }

    @Test
    public void foregroundWithoutAPriorBackgroundKeepsTheSession() {
        String before = tracker.current();
        tracker.onForeground();
        assertEquals(before, tracker.current());
    }
}
