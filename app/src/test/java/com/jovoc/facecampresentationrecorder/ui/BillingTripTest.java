package com.jovoc.facecampresentationrecorder.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/** The billing-trip decisions: stays pending until the plan rises or 24 h pass, one burst per 20 s. */
public class BillingTripTest {

    private static final int FREE = 0;
    private static final int STARTER = 1;

    private long now;
    private FakeStore store;
    private BillingTrip trip;

    @Before
    public void setUp() {
        now = 1_000_000L;
        store = new FakeStore();
        trip = new BillingTrip(store, () -> now);
    }

    @Test
    public void noTripMeansNothingToDo() {
        assertEquals(BillingTrip.Step.NONE, trip.onForeground(FREE));
        assertEquals(BillingTrip.Step.NONE, trip.onPlan(STARTER));
    }

    @Test
    public void theFirstReturnRefreshesAndKeepsTheTripPending() {
        trip.begin(FREE);
        now += 5_000L;
        assertEquals(BillingTrip.Step.REFRESH, trip.onForeground(FREE));
        assertTrue(store.pending());
    }

    @Test
    public void aSamePlanKeepsItPendingOnEveryLaterReturn() {
        trip.begin(FREE);
        now += 5_000L;
        trip.onForeground(FREE);
        now += BillingTrip.BURST_GAP_MS;
        assertEquals(BillingTrip.Step.REFRESH, trip.onForeground(FREE));
        now += 60 * 60 * 1000L;
        assertEquals(BillingTrip.Step.REFRESH, trip.onForeground(FREE));
        assertTrue(store.pending());
    }

    @Test
    public void aRiseEndsTheTripOnce() {
        trip.begin(FREE);
        now += 5_000L;
        assertEquals(BillingTrip.Step.RISEN, trip.onPlan(STARTER));
        assertFalse(store.pending());
        assertEquals(BillingTrip.Step.NONE, trip.onPlan(STARTER));
        assertEquals(BillingTrip.Step.NONE, trip.onForeground(STARTER));
    }

    @Test
    public void aRiseNoticedOnReturnEndsTheTripToo() {
        trip.begin(FREE);
        now += 5_000L;
        assertEquals(BillingTrip.Step.RISEN, trip.onForeground(STARTER));
        assertEquals(BillingTrip.Step.NONE, trip.onForeground(STARTER));
    }

    @Test
    public void aPlanThatDoesNotRiseDoesNotEndIt() {
        trip.begin(STARTER);
        now += 5_000L;
        assertEquals(BillingTrip.Step.WAIT, trip.onPlan(STARTER));
        assertEquals(BillingTrip.Step.WAIT, trip.onPlan(FREE)); // dropped (sign-out): still not a rise
        assertTrue(store.pending());
    }

    @Test
    public void aTripEndsAfter24HoursWithoutAToastStep() {
        trip.begin(FREE);
        now += BillingTrip.MAX_AGE_MS; // exactly 24 h still counts
        assertEquals(BillingTrip.Step.REFRESH, trip.onForeground(FREE));
        now += 1L;
        assertEquals(BillingTrip.Step.EXPIRED, trip.onForeground(FREE));
        assertFalse(store.pending());
        assertEquals(BillingTrip.Step.NONE, trip.onForeground(STARTER));
    }

    @Test
    public void aRiseAfter24HoursIsNotAnnounced() {
        trip.begin(FREE);
        now += BillingTrip.MAX_AGE_MS + 1L;
        assertEquals(BillingTrip.Step.EXPIRED, trip.onPlan(STARTER));
    }

    @Test
    public void aClockSetBackEndsTheTrip() {
        trip.begin(FREE);
        now -= 1L;
        assertEquals(BillingTrip.Step.EXPIRED, trip.onForeground(FREE));
        assertFalse(store.pending());
    }

    @Test
    public void burstsAreOneEvery20Seconds() {
        trip.begin(FREE);
        now += 1_000L;
        assertEquals(BillingTrip.Step.REFRESH, trip.onForeground(FREE));
        now += BillingTrip.BURST_GAP_MS - 1L;
        assertEquals(BillingTrip.Step.WAIT, trip.onForeground(FREE)); // another caller of the same return
        now += 1L;
        assertEquals(BillingTrip.Step.REFRESH, trip.onForeground(FREE));
    }

    @Test
    public void planChecksNeverStartABurst() {
        trip.begin(FREE);
        now += 1_000L;
        assertEquals(BillingTrip.Step.WAIT, trip.onPlan(FREE));
        assertEquals(BillingTrip.Step.REFRESH, trip.onForeground(FREE)); // and do not use up the throttle
    }

    @Test
    public void aNewTripBurstsAtOnceEvenRightAfterABurst() {
        trip.begin(FREE);
        now += 1_000L;
        trip.onForeground(FREE);
        now += 1_000L;
        trip.begin(FREE);
        now += 1_000L;
        assertEquals(BillingTrip.Step.REFRESH, trip.onForeground(FREE));
    }

    @Test
    public void aTripFromAnOlderBuildCountsFromTheCurrentPlan() {
        store.openedAt = now - 1_000L; // opened_at only, no rank
        assertEquals(BillingTrip.Step.REFRESH, trip.onForeground(STARTER));
        assertEquals(BillingTrip.Step.WAIT, trip.onPlan(STARTER));
        assertEquals(BillingTrip.Step.RISEN, trip.onPlan(STARTER + 1));
    }

    @Test
    public void theSamePlanIsAnnouncedOnceWithin30Seconds() {
        assertTrue(trip.shouldAnnounce("pro"));
        now += BillingTrip.ANNOUNCE_GAP_MS - 1L;
        assertFalse(trip.shouldAnnounce("pro"));
        assertTrue(trip.shouldAnnounce("agency")); // a different plan is news
        assertTrue(trip.shouldAnnounce("pro"));
        now += BillingTrip.ANNOUNCE_GAP_MS;
        assertTrue(trip.shouldAnnounce("pro")); // a later unlock of the same plan counts again
    }

    private static final class FakeStore implements BillingTrip.Store {
        long openedAt;
        int rank = BillingTrip.RANK_UNKNOWN;

        boolean pending() {
            return openedAt != 0L;
        }

        @Override
        public long openedAtMs() {
            return openedAt;
        }

        @Override
        public int rankAtOpen() {
            return rank;
        }

        @Override
        public void save(long openedAtMs, int rank) {
            this.openedAt = openedAtMs;
            this.rank = rank;
        }

        @Override
        public void clear() {
            openedAt = 0L;
            rank = BillingTrip.RANK_UNKNOWN;
        }
    }
}
