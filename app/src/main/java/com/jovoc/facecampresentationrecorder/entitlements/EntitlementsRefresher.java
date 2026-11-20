package com.jovoc.facecampresentationrecorder.entitlements;

import android.content.Context;

import com.jovoc.facecampresentationrecorder.activitylog.ActivityLog;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Keeps the stored plan token fresh. Everything runs on one background thread and never blocks the
 * caller; every method is safe to call at any time and never throws. A fetch happens when:
 * <ul>
 * <li>the app comes to the foreground and the last one is over 6 hours old or the token is past
 * half its life ({@link #refreshIfDue});</li>
 * <li>the user signed in or switched workspace, or a server call answered 403 FEATURE_NOT_IN_PLAN
 * ({@link #refreshNow});</li>
 * <li>the user comes back from the billing page ({@link #refreshBurst}: now, +3 s, +10 s, until the
 * plan changes, because the payment may reach the server a moment after the browser closes).</li>
 * </ul>
 * Only a response whose token verifies replaces the stored one ({@link Entitlements#accept}); any
 * error keeps the plan as it is. Triggers that pile up behind a running fetch become one more fetch.
 */
public final class EntitlementsRefresher {

    /** What the refresher needs from the stored plan: the {@link Entitlements} facade in the app. */
    interface PlanStore {
        boolean accept(String token, String catalogueJson, String workspaceId);

        String plan();

        long lastFetchAtMs();

        long tokenIssuedAtSec();

        long tokenExpiresAtSec();
    }

    static final long STALE_AFTER_MS = 6 * 60 * 60 * 1000L;
    /**
     * A foreground check does not start another fetch within this time of the last attempt, so a
     * fetch that keeps failing (offline, wrong clock, no public key) does not hit the server on
     * every app switch. Other triggers ignore it.
     */
    static final long RETRY_GAP_MS = 5 * 60 * 1000L;
    static final long[] BURST_DELAYS_MS = {0L, 3_000L, 10_000L};

    private static final PlanStore FACADE = new PlanStore() {
        @Override
        public boolean accept(String token, String catalogueJson, String workspaceId) {
            return Entitlements.accept(token, catalogueJson, workspaceId);
        }

        @Override
        public String plan() {
            return Entitlements.plan();
        }

        @Override
        public long lastFetchAtMs() {
            return Entitlements.lastFetchAtMs();
        }

        @Override
        public long tokenIssuedAtSec() {
            return Entitlements.tokenIssuedAtSec();
        }

        @Override
        public long tokenExpiresAtSec() {
            return Entitlements.tokenExpiresAtSec();
        }
    };

    private static volatile EntitlementsRefresher instance;

    private final Supplier<EntitlementsApi> apiSource;
    private final PlanStore store;
    private final LongSupplier clockMs;
    private final ScheduledExecutorService worker;
    private final long[] burstDelaysMs;
    private final BiConsumer<String, String> planChanged;
    /** True while a fetch is waiting for the worker; a trigger then has nothing to add. */
    private final AtomicBoolean queued = new AtomicBoolean();
    private volatile long lastAttemptAtMs;
    private ScheduledFuture<?>[] burst = new ScheduledFuture<?>[0];
    /** Created on the worker thread the first time it is needed (opens the session store). */
    private EntitlementsApi api;

    /**
     * @param apiSource builds the API client; called on the worker thread
     * @param worker runs the fetches one at a time
     * @param planChanged told the plan key before and after when a fetch changed it
     */
    EntitlementsRefresher(Supplier<EntitlementsApi> apiSource, PlanStore store, LongSupplier clockMs,
                          ScheduledExecutorService worker, long[] burstDelaysMs,
                          BiConsumer<String, String> planChanged) {
        this.apiSource = apiSource;
        this.store = store;
        this.clockMs = clockMs;
        this.worker = worker;
        this.burstDelaysMs = burstDelaysMs;
        this.planChanged = planChanged;
    }

    /** Call once at startup, after {@link Entitlements#init}. Cheap: nothing is read or sent yet. */
    public static synchronized void init(Context context) {
        if (instance != null) return;
        Context app = context.getApplicationContext();
        instance = new EntitlementsRefresher(() -> EntitlementsApi.create(app), FACADE,
                System::currentTimeMillis, newWorker(), BURST_DELAYS_MS,
                (from, to) -> ActivityLog.log("entitlement_change", "from", from, "to", to));
    }

    /**
     * Fetch the token now (in the background). For: after sign-in, after a workspace switch, after a
     * 403 FEATURE_NOT_IN_PLAN, and when a screen needs prices the phone does not have yet.
     */
    public static void refreshNow() {
        EntitlementsRefresher r = instance;
        if (r == null) return;
        try {
            r.requestRefresh();
        } catch (RuntimeException e) {
            // Plans must never break the screen that asked.
        }
    }

    /** Fetch only if the last one is over 6 hours old or the token is past half its life (app foreground). */
    public static void refreshIfDue() {
        EntitlementsRefresher r = instance;
        if (r == null) return;
        try {
            r.requestIfDue();
        } catch (RuntimeException e) {
            // Plans must never break the screen that asked.
        }
    }

    /**
     * Fetch the token now and wait for the answer, at most {@code timeoutMs}. For a brand change, so
     * the next screen opens with the new brand's plan. Blocks the calling thread; never throws.
     * @return true when the fetch finished in time (whether or not its token was kept)
     */
    public static boolean refreshAndWait(long timeoutMs) {
        EntitlementsRefresher r = instance;
        if (r == null) return false;
        try {
            return r.awaitFetch(timeoutMs);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * For the return from the billing page: fetch now, again after 3 s and after 10 s, stopping as
     * soon as the plan differs from what it was when this was called.
     */
    public static void refreshBurst() {
        EntitlementsRefresher r = instance;
        if (r == null) return;
        try {
            r.requestBurst();
        } catch (RuntimeException e) {
            // Plans must never break the screen that asked.
        }
    }

    private static ScheduledExecutorService newWorker() {
        ScheduledThreadPoolExecutor ex = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "entitlements-refresh");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        ex.setRemoveOnCancelPolicy(true);
        return ex;
    }

    /** @return false when a fetch is already waiting to start (this trigger joins it) */
    boolean requestRefresh() {
        if (!queued.compareAndSet(false, true)) return false;
        try {
            worker.execute(() -> {
                queued.set(false);
                fetchOnce();
            });
            return true;
        } catch (RejectedExecutionException e) {
            queued.set(false);
            return false;
        }
    }

    /** Queues a fetch behind any running one and waits for it; false when it did not finish in time. */
    boolean awaitFetch(long timeoutMs) {
        try {
            worker.submit(this::fetchOnce).get(timeoutMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException | RejectedExecutionException e) {
            return false;
        }
    }

    /** @return true when a fetch was started */
    boolean requestIfDue() {
        return isDue() && requestRefresh();
    }

    synchronized void requestBurst() {
        for (ScheduledFuture<?> f : burst) f.cancel(false);
        final String planAtStart = store.plan();
        ScheduledFuture<?>[] next = new ScheduledFuture<?>[burstDelaysMs.length];
        for (int i = 0; i < next.length; i++) {
            next[i] = worker.schedule(() -> {
                if (!planAtStart.equals(store.plan())) return; // it moved: the purchase arrived
                fetchOnce();
            }, burstDelaysMs[i], TimeUnit.MILLISECONDS);
        }
        burst = next;
    }

    /** True when a foreground check should fetch: stale, or half the token's life is gone, and not just tried. */
    boolean isDue() {
        long now = clockMs.getAsLong();
        if (!stale(now)) return false;
        long last = lastAttemptAtMs;
        return last == 0 || now < last || now - last >= RETRY_GAP_MS;
    }

    private boolean stale(long nowMs) {
        long last = store.lastFetchAtMs();
        if (last <= 0 || nowMs < last || nowMs - last > STALE_AFTER_MS) return true;
        long iat = store.tokenIssuedAtSec();
        long exp = store.tokenExpiresAtSec();
        if (exp <= 0) return true; // fetched, yet no token counts (clock, key): ask again
        return nowMs / 1000 >= iat + (exp - iat) / 2;
    }

    /** One fetch on the calling thread: keep the answer if its token verifies, log a plan change. */
    void fetchOnce() {
        lastAttemptAtMs = clockMs.getAsLong();
        try {
            if (api == null) api = apiSource.get();
            EntitlementsApi.Fetched fetched = api.fetch();
            if (fetched == null) return;
            String from = store.plan();
            if (!store.accept(fetched.token, fetched.catalogueJson, fetched.workspaceId)) return;
            String to = store.plan();
            if (!from.equals(to)) planChanged.accept(from, to);
        } catch (RuntimeException e) {
            // A refresh must never take the app down; the stored plan stays as it is.
        }
    }
}
