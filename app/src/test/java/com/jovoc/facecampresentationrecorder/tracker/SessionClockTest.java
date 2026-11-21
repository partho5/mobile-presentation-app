package com.jovoc.facecampresentationrecorder.tracker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SessionClockTest {

    /** An in-memory store. */
    private static final class MemoryStore implements SessionClock.Store {
        String id;
        long at;

        @Override
        public String sessionId() {
            return id;
        }

        @Override
        public long lastActiveAt() {
            return at;
        }

        @Override
        public void save(String id, long at) {
            this.id = id;
            this.at = at;
        }
    }

    private static final long MIN = 60L * 1000;

    @Test
    public void sameSessionWithinThirtyMinutes() {
        SessionClock clock = new SessionClock(new MemoryStore(), () -> "fixed01");
        assertEquals("fixed01", clock.current(0));
        assertEquals("fixed01", clock.current(30 * MIN));
    }

    @Test
    public void newSessionAfterThirtyMinutesPlusOneMs() {
        MemoryStore store = new MemoryStore();
        SessionClock first = new SessionClock(store, () -> "first001");
        first.current(0);
        SessionClock second = new SessionClock(store, () -> "second02");
        assertEquals("second02", second.current(30 * MIN + 1));
    }

    @Test
    public void newSessionWhenTheClockWentBack() {
        MemoryStore store = new MemoryStore();
        new SessionClock(store, () -> "before01").current(10 * MIN);
        assertEquals("after002", new SessionClock(store, () -> "after002").current(5 * MIN));
    }

    @Test
    public void newIdIsEightHexCharacters() {
        String id = SessionClock.newId();
        assertEquals(8, id.length());
        assertTrue(id.matches("[0-9a-f]{8}"));
    }
}
