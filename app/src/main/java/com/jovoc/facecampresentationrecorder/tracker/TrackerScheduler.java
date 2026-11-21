package com.jovoc.facecampresentationrecorder.tracker;

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
 * Decides when {@link TrackerUploadWorker} runs. Only with a network (the worker checks that the
 * internet is validated), also when the app is closed:
 * <ul>
 *   <li>a periodic job every 24 hours carries the 5-day rule for light users;</li>
 *   <li>after a stored record, once more than {@link TrackerConfig#SEND_WHEN_MORE_THAN} are waiting,
 *       a one-time job (delayed until the cooldown since the last send is over) carries the volume rule.</li>
 * </ul>
 * Scheduling is best effort: nothing here ever throws.
 */
public final class TrackerScheduler implements TrackerStore.Listener {

    static final String UNIQUE_PERIODIC = "tracker-upload-periodic";
    static final String UNIQUE_NOW = "tracker-upload-now";
    static final String UNIQUE_DRAIN = "tracker-upload-drain";

    private static final long PERIOD_HOURS = 24;
    private static final long BACKOFF_MINUTES = 10;
    /** The volume rule is looked at once a minute at most, however fast records come. */
    private static final long CHECK_EVERY_MS = 60_000L;

    private final Context context;
    private volatile long lastCheckAt;

    public TrackerScheduler(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Keeps one periodic job; calling again leaves the existing one alone. */
    public static void schedulePeriodic(Context context) {
        try {
            PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                    TrackerUploadWorker.class, PERIOD_HOURS, TimeUnit.HOURS)
                    .setConstraints(connected())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
                    .build();
            WorkManager.getInstance(context.getApplicationContext())
                    .enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request);
        } catch (Throwable ignored) {
            // best effort
        }
    }

    /** A run that uploads whatever is waiting, regardless of the policy (the follow-up for a big buffer). */
    public static void drainNow(Context context) {
        try {
            OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(TrackerUploadWorker.class)
                    .setConstraints(connected())
                    .setInputData(new Data.Builder().putBoolean(TrackerUploadWorker.KEY_FORCE, true).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
                    .build();
            WorkManager.getInstance(context.getApplicationContext())
                    // APPEND_OR_REPLACE, not KEEP: the worker calls this while it is still running, and KEEP
                    // would drop the follow-up run. APPEND chains it after the running one.
                    .enqueueUniqueWork(UNIQUE_DRAIN, ExistingWorkPolicy.APPEND_OR_REPLACE, request);
        } catch (Throwable ignored) {
            // best effort
        }
    }

    /** Runs on the tracker thread after each stored record. */
    @Override
    public void onStored(int pending) {
        try {
            if (pending <= TrackerConfig.SEND_WHEN_MORE_THAN) return;
            long now = System.currentTimeMillis();
            if (now - lastCheckAt < CHECK_EVERY_MS) return;
            lastCheckAt = now;
            long delay = UploadPolicy.delayForVolume(new TrackerPrefs(context).lastSentAt(), now);
            OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(TrackerUploadWorker.class)
                    .setConstraints(connected())
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
                    .build();
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NOW, ExistingWorkPolicy.KEEP, request);
        } catch (Throwable ignored) {
            // best effort; the periodic job still picks the records up
        }
    }

    private static Constraints connected() {
        return new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
    }
}
