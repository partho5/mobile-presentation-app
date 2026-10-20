package com.jovoc.facecampresentationrecorder.ui;

import java.util.function.LongSupplier;

/**
 * The decisions behind a trip to the billing page, with no Android in it: the trip stays pending
 * (surviving app restarts, through the {@link Store}) until the plan ranks above what it was when
 * the user left, or 24 hours pass. Every time the app comes to the front while pending, a refresh
 * burst may start, at most one per {@link #BURST_GAP_MS}. Main thread only.
 */
final class BillingTrip {

    /** Where the trip is kept between launches. */
    interface Store {
        /** Wall-clock millis the user left for the billing page; 0 when there is no trip. */
        long openedAtMs();

        /** Plan rank when they left; {@link #RANK_UNKNOWN} when an older build stored none. */
        int rankAtOpen();

        void save(long openedAtMs, int rank);

        void clear();
    }

    enum Step {
        /** No trip pending. */
        NONE,
        /** Pending: start a refresh burst now. */
        REFRESH,
        /** Pending, nothing to start (a burst started a moment ago, or this is not a foreground check). */
        WAIT,
        /** The plan rose: the trip has just ended, say so once. */
        RISEN,
        /** 24 hours passed (or the clock went back): the trip has just ended, quietly. */
        EXPIRED
    }

    static final int RANK_UNKNOWN = Integer.MIN_VALUE;
    static final long MAX_AGE_MS = 24L * 60 * 60 * 1000;
    static final long BURST_GAP_MS = 20_000L;
    /** The same plan is announced once within this time, whoever (app or sheet) gets there first. */
    static final long ANNOUNCE_GAP_MS = 30_000L;

    private final Store store;
    private final LongSupplier clockMs;
    private long lastBurstAtMs;
    private String announcedPlan;
    private long announcedAtMs;

    BillingTrip(Store store, LongSupplier clockMs) {
        this.store = store;
        this.clockMs = clockMs;
    }

    /** The user is leaving for the billing page on a plan of rank {@code rankNow}. */
    void begin(int rankNow) {
        store.save(clockMs.getAsLong(), rankNow);
        lastBurstAtMs = 0L; // the return from this trip bursts at once
    }

    /** The app came to the front. {@link Step#REFRESH} also counts as the burst for the throttle. */
    Step onForeground(int rankNow) {
        return check(rankNow, true);
    }

    /** The plan changed (or may have): settle the trip, never start a burst. */
    Step onPlan(int rankNow) {
        return check(rankNow, false);
    }

    private Step check(int rankNow, boolean foreground) {
        long openedAt = store.openedAtMs();
        if (openedAt == 0L) return Step.NONE;
        long now = clockMs.getAsLong();
        long age = now - openedAt;
        if (age < 0 || age > MAX_AGE_MS) {
            store.clear();
            return Step.EXPIRED;
        }
        int rankAtOpen = store.rankAtOpen();
        if (rankAtOpen == RANK_UNKNOWN) { // trip begun by an older build: count from now
            store.save(openedAt, rankNow);
            rankAtOpen = rankNow;
        }
        if (rankNow > rankAtOpen) {
            store.clear();
            return Step.RISEN;
        }
        if (!foreground) return Step.WAIT;
        if (lastBurstAtMs != 0L && now >= lastBurstAtMs && now - lastBurstAtMs < BURST_GAP_MS) return Step.WAIT;
        lastBurstAtMs = now;
        return Step.REFRESH;
    }

    /** True the first time {@code planKey} is announced within {@link #ANNOUNCE_GAP_MS}; records it. */
    boolean shouldAnnounce(String planKey) {
        long now = clockMs.getAsLong();
        boolean repeat = planKey != null && planKey.equals(announcedPlan)
                && now >= announcedAtMs && now - announcedAtMs < ANNOUNCE_GAP_MS;
        if (repeat) return false;
        announcedPlan = planKey;
        announcedAtMs = now;
        return true;
    }
}
