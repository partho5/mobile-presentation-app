package com.jovoc.facecampresentationrecorder.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.bgreplace.BubbleTrack;
import com.jovoc.facecampresentationrecorder.bgreplace.FaceBgReplaceTask;
import com.jovoc.facecampresentationrecorder.bgreplace.OfflineBgProcessor;
import com.jovoc.facecampresentationrecorder.bgreplace.WarmUpProgress;
import com.jovoc.facecampresentationrecorder.util.VideoCropHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Post-recording work as one foreground job: replace the face cam background, then auto-crop
 * the processed file (the order is fixed so every crop also has the new background). Runs in
 * a service with a progress notification and a partial wake lock, so leaving the app or
 * switching the screen off does not kill it.
 */
public class BgReplaceService extends Service {

    private static final String TAG = "BgReplaceService";
    private static final String CHANNEL_ID = "bg_replace_channel";
    private static final int NOTIFICATION_ID = 1002;
    private static final int DONE_NOTIFICATION_ID = 1003;
    private static final long WAKE_LOCK_TIMEOUT_MS = 2 * 60 * 60 * 1000L;
    private static final String REPLACE_STAGE = "Replacing facecam background using AI";
    private static final long ESTIMATE_TICK_MS = 250;

    /** Progress and completion, always delivered on the main thread. */
    public interface Listener {
        /** {@code percent} is 0..100, or -1 when the stage has no measurable progress. */
        void onProgress(String stage, int percent);

        /** Background replace is done (or was not needed): time to show the recording. */
        void onReady(String path);
    }

    /** Auto-crop completion for the video player dialog, always delivered on the main thread. */
    public interface CropListener {
        void onCropsDone(int cropSuccess, int cropFail);
    }

    private static final class Job {
        final File video;
        final List<BubbleTrack.Entry> track;
        final boolean replace;
        final List<int[]> ratios;

        Job(File video, List<BubbleTrack.Entry> track, boolean replace, List<int[]> ratios) {
            this.video = video;
            this.track = track;
            this.replace = replace;
            this.ratios = ratios;
        }
    }

    private static final Object LOCK = new Object();
    private static final List<Job> QUEUE = new ArrayList<>();
    private static boolean workerRunning;
    private static Listener listener;
    private static CropListener cropListener;
    private static final java.util.Set<String> CROP_PENDING = new java.util.HashSet<>();
    private static String currentStage = "";
    private static int currentPercent = -1;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /**
     * Queues a recording for processing. Returns true only when background replace will run, i.e.
     * when the UI should wait for {@link Listener#onReady}; auto-crops alone run silently in the
     * background and the recording can be shown right away.
     */
    public static boolean enqueue(Context context, File video, List<BubbleTrack.Entry> track) {
        boolean replace = context.getSharedPreferences("facecam_prefs", MODE_PRIVATE)
                .getBoolean("bg_replace_enabled", true) && BubbleTrack.hasVisible(track);
        List<int[]> ratios = readAutoCropRatios(context);
        if (!replace && ratios.isEmpty()) return false;
        synchronized (LOCK) {
            QUEUE.add(new Job(video, track, replace, ratios));
            if (!ratios.isEmpty()) CROP_PENDING.add(video.getAbsolutePath());
        }
        context.startForegroundService(new Intent(context, BgReplaceService.class));
        return replace;
    }

    /** Auto-crop ratios chosen in Settings. */
    public static List<int[]> readAutoCropRatios(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("app_prefs", MODE_PRIVATE);
        List<int[]> ratios = new ArrayList<>();
        if (prefs.getBoolean("key_autocrop_9_16", true)) ratios.add(new int[]{9, 16});
        if (prefs.getBoolean("key_autocrop_4_5", false)) ratios.add(new int[]{4, 5});
        if (prefs.getBoolean("key_autocrop_1_1", false)) ratios.add(new int[]{1, 1});
        return ratios;
    }

    /** Main thread only. Pass null to detach. */
    public static void setListener(@Nullable Listener l) {
        listener = l;
    }

    /** True from queuing until the auto-crops of this recording are done. */
    public static boolean isCropPending(String path) {
        synchronized (LOCK) {
            return CROP_PENDING.contains(path);
        }
    }

    /** Main thread only. Pass null to detach. */
    public static void setCropListener(@Nullable CropListener l) {
        cropListener = l;
    }

    public static boolean isBusy() {
        synchronized (LOCK) {
            return workerRunning || !QUEUE.isEmpty();
        }
    }

    public static String getCurrentStage() {
        return currentStage;
    }

    public static int getCurrentPercent() {
        return currentPercent;
    }

    private volatile int lastStartId;
    private NotificationManager notificationManager;
    private int lastNotifiedPercent = -2;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager != null) {
            notificationManager.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "Video processing", NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        lastStartId = startId;
        // Required within seconds of startForegroundService()
        Notification n = buildProgressNotification("Preparing…", -1);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
        synchronized (LOCK) {
            if (workerRunning) return START_NOT_STICKY;
            workerRunning = true;
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = null;
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FaceCam:bgreplace");
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
        }
        PowerManager.WakeLock lock = wakeLock;
        new Thread(() -> workLoop(lock), "BgReplaceService").start();
        return START_NOT_STICKY;
    }

    private void workLoop(PowerManager.WakeLock wakeLock) {
        try {
            while (true) {
                Job job;
                synchronized (LOCK) {
                    if (QUEUE.isEmpty()) {
                        workerRunning = false;
                        break;
                    }
                    job = QUEUE.remove(0);
                }
                runJob(job);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Worker crashed", t);
            synchronized (LOCK) {
                workerRunning = false;
            }
        } finally {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            stopForeground(STOP_FOREGROUND_REMOVE);
            // Only stops if no newer job started the service again in the meantime
            stopSelf(lastStartId);
        }
    }

    private void runJob(Job job) {
        File video = job.video;
        if (job.replace) {
            if (!FaceBgReplaceTask.hasEnoughSpace(video)) {
                toast("Not enough free space to replace the background — original kept");
            } else {
                // Without the kernel cache the first ~25 s report nothing: show an estimate so the
                // bar never looks stuck. With the cache the real progress is used from 0.
                WarmUpProgress warmUp = new WarmUpProgress(OfflineBgProcessor.isWarmUpNeeded(this));
                long startMs = SystemClock.elapsedRealtime();
                setStage(REPLACE_STAGE, 0);
                Runnable estimate = new Runnable() {
                    @Override
                    public void run() {
                        if (!warmUp.isEstimating()) return;
                        setStage(REPLACE_STAGE, warmUp.estimate(SystemClock.elapsedRealtime() - startMs));
                        MAIN.postDelayed(this, ESTIMATE_TICK_MS);
                    }
                };
                if (warmUp.isEstimating()) MAIN.postDelayed(estimate, ESTIMATE_TICK_MS);
                boolean ok;
                try {
                    ok = FaceBgReplaceTask.replace(this, video, job.track,
                            percent -> setStage(REPLACE_STAGE, warmUp.real(percent)));
                } finally {
                    MAIN.removeCallbacks(estimate);
                }
                if (!ok) toast("Background replace failed — original recording kept");
            }
            currentStage = "";
            currentPercent = -1;
            lastNotifiedPercent = -2;
            MAIN.post(() -> {
                if (listener != null) {
                    listener.onReady(video.getAbsolutePath());
                } else {
                    // Nobody is watching (app closed or in the background): say it is ready
                    notifyReady(video);
                }
            });
        }

        int cropOk = 0;
        int cropFail = 0;
        if (!job.ratios.isEmpty() && video.exists() && video.length() > 0) {
            showCropNotification();
            int[] counts = {0, 0};
            CountDownLatch done = new CountDownLatch(1);
            VideoCropHelper.cropAsync(getApplicationContext(), video.getAbsolutePath(), job.ratios,
                    new VideoCropHelper.CropCallback() {
                        @Override
                        public void onProgress(String ratioLabel, boolean success) {
                        }

                        @Override
                        public void onAllComplete(int successCount, int failCount) {
                            counts[0] = successCount;
                            counts[1] = failCount;
                            done.countDown();
                        }
                    });
            try {
                if (!done.await(60, TimeUnit.MINUTES)) Log.w(TAG, "Crop timed out");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            cropOk = counts[0];
            cropFail = counts[1];
        }

        if (!job.ratios.isEmpty()) {
            synchronized (LOCK) {
                CROP_PENDING.remove(video.getAbsolutePath());
            }
            int ok = cropOk;
            int fail = cropFail;
            MAIN.post(() -> {
                if (cropListener != null) cropListener.onCropsDone(ok, fail);
            });
        }
    }

    /** Auto-crop is background work: notification only, no full-screen processing UI. */
    private void showCropNotification() {
        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, buildProgressNotification("Cropping videos…", -1));
        }
    }

    private synchronized void setStage(String stage, int percent) {
        // The estimate ticker (main thread) and the worker report concurrently: never step back
        if (percent >= 0 && stage.equals(currentStage) && percent < currentPercent) return;
        currentStage = stage;
        currentPercent = percent;
        if (percent != lastNotifiedPercent || percent < 0) {
            lastNotifiedPercent = percent;
            if (notificationManager != null) {
                notificationManager.notify(NOTIFICATION_ID, buildProgressNotification(stage, percent));
            }
        }
        MAIN.post(() -> {
            if (listener != null) listener.onProgress(stage, percent);
        });
    }

    private void notifyReady(File video) {
        if (notificationManager == null) return;
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("Your recording is ready")
                .setSmallIcon(android.R.drawable.ic_menu_gallery)
                .setContentIntent(launchIntent())
                .setAutoCancel(true)
                .build();
        notificationManager.notify(DONE_NOTIFICATION_ID, n);
    }

    private Notification buildProgressNotification(String stage, int percent) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(percent >= 0 ? stage + " " + percent + "%" : stage)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentIntent(launchIntent())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setProgress(100, Math.max(0, percent), percent < 0);
        return b.build();
    }

    @Nullable
    private PendingIntent launchIntent() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch == null) return null;
        launch.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return PendingIntent.getActivity(this, 2, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void toast(String message) {
        MAIN.post(() -> Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show());
    }
}
