package com.jovoc.facecampresentationrecorder.activitylog;

import android.content.Context;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/**
 * Decides when {@link ActivityLogWorker} runs. Always on an unmetered network (Wi-Fi), never on
 * mobile data or roaming, and also when the app is closed:
 * <ul>
 *   <li>a periodic job every 12 hours carries the 3-day rule for light users;</li>
 *   <li>after each logged event, once 10 are waiting, a one-time job (delayed until the cooldown since
 *       the last send is over) carries the volume rule.</li>
 * </ul>
 * The worker re-checks {@link UploadPolicy} itself, so an extra or early run costs nothing.
 */
public final class ActivityLogScheduler implements ActivityLog.Listener {

    static final String UNIQUE_PERIODIC = "activity-log-periodic";
    static final String UNIQUE_COUNT = "activity-log-count";
    static final String UNIQUE_DRAIN = "activity-log-drain";

    private static final long PERIOD_HOURS = 12;
    private static final long BACKOFF_MINUTES = 10;
    /** The volume rule is looked at once a minute at most, however fast events come. */
    private static final long CHECK_EVERY_MS = 60_000L;

    private final Context context;
    private volatile long lastCheckAt;

    public ActivityLogScheduler(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Keeps one periodic job; calling again leaves the existing one alone. */
    public static void schedulePeriodic(Context context) {
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                ActivityLogWorker.class, PERIOD_HOURS, TimeUnit.HOURS)
                .setConstraints(unmetered())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
                .build();
        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request);
    }

    /** One run after {@code delayMs}; a run already waiting is kept. {@code force} skips the policy check. */
    static void enqueueOneTime(Context context, long delayMs, boolean force) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(ActivityLogWorker.class)
                .setConstraints(unmetered())
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
                .setInputData(new Data.Builder().putBoolean(ActivityLogWorker.KEY_FORCE, force).build())
                .build();
        WorkManager.getInstance(context.getApplicationContext()).enqueueUniqueWork(
                force ? UNIQUE_DRAIN : UNIQUE_COUNT, ExistingWorkPolicy.KEEP, request);
    }

    private static Constraints unmetered() {
        return new Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build();
    }

    /** Runs on the logger's writer thread after each stored event. */
    @Override
    public void onLogged(int pending) {
        try {
            if (pending < UploadPolicy.COUNT_TRIGGER) return;
            long now = System.currentTimeMillis();
            if (now - lastCheckAt < CHECK_EVERY_MS) return;
            lastCheckAt = now;
            long delay = UploadPolicy.delayUntilCountTrigger(new ActivityLogPrefs(context).lastSendAt(), now);
            enqueueOneTime(context, delay, false);
        } catch (Exception e) {
            // Scheduling is best effort; the periodic job still picks the events up.
        }
    }
}
