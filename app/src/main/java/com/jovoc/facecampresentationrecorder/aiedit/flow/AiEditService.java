package com.jovoc.facecampresentationrecorder.aiedit.flow;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.aiedit.capture.EventLogData;
import com.jovoc.facecampresentationrecorder.aiedit.capture.EventLogStore;
import com.jovoc.facecampresentationrecorder.auth.SessionManager;
import com.jovoc.facecampresentationrecorder.edit.EditGroupMath;
import com.jovoc.facecampresentationrecorder.subtitle.SubtitleCache;
import com.jovoc.facecampresentationrecorder.subtitle.SubtitleEmbedStore;
import com.jovoc.facecampresentationrecorder.util.RecordSettings;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The AI edit as a foreground job: uploads, waits for the plan, renders the enabled ratios (9:16 first), all in the
 * background with one notification ("Uploading…", "Planning…", "Rendering 9:16…", "Done"), so leaving the app or
 * switching the screen off does not stop it. The jobs live in {@link AiEditStore}: a restarted service (the process
 * was killed) continues every unfinished one, and {@link #start} for a recording and level that already has a job
 * continues or reuses it, so a repeated tap never makes a second server job or a second charge.
 */
public class AiEditService extends Service {

    private static final String TAG = "AiEditService";
    private static final String CHANNEL_ID = "ai_edit_channel";
    private static final int NOTIFICATION_ID = 1004;
    private static final int DONE_NOTIFICATION_ID = 1005;
    private static final long WAKE_LOCK_TIMEOUT_MS = 2 * 60 * 60 * 1000L;
    /** A crop's name ({@code <master>_9x16}); the AI edit is made from the video the crop came from. */
    private static final Pattern CROP_SUFFIX = Pattern.compile("^(.+)_(9x16|4x5|1x1)\\.mp4$");

    /** What {@link #start} did. */
    public enum StartResult {
        /** A new job was queued. */
        STARTED,
        /** The recording's job stopped earlier; it continues where it stopped. */
        RESUMED,
        /** The recording's job at this level is already queued or running. */
        ALREADY_RUNNING,
        /** The recording's job at this level is finished and its files are there. */
        ALREADY_DONE,
        /** The server heard no speech in this recording; asking again gets the same answer. */
        NO_SPEECH,
        NOT_SIGNED_IN,
        /** No brand can be had now (offline, first use): nothing is saved or charged; try again later. */
        NO_BRAND,
        /** The file is missing, or the level is unknown. */
        INVALID
    }

    private static final Object LOCK = new Object();
    private static final List<String> QUEUE = new ArrayList<>();
    /** The job the worker is on, as {@link #key}; guarded by LOCK. */
    private static String running;
    private static boolean workerRunning;
    private static volatile AiEditListener listener;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /**
     * Starts the AI edit of {@code video} at {@code level} ({@link AiEditSettings#LEVEL_BASIC} ...), or finds the
     * job it already has. A crop of the recording stands for the recording itself. Safe to call twice.
     */
    public static StartResult start(Context context, File video, String level) {
        Context app = context.getApplicationContext();
        File source = sourceOf(video);
        if (source == null || !source.isFile() || source.length() == 0 || !AiEditSettings.isLevel(level)) {
            return StartResult.INVALID;
        }
        if (!SessionManager.get(app).hasSession()) return StartResult.NOT_SIGNED_IN;
        String path = source.getAbsolutePath();
        AiEditStore store = AiEditStore.get(app);
        AiEditJob job = store.find(path, level);
        StartResult result = StartResult.STARTED;
        synchronized (LOCK) {
            if (isQueuedOrRunning(path, level)) return StartResult.ALREADY_RUNNING;
        }
        if (job == null) {
            // The job is made in the brand picked now. Called off the main thread (the panel's executor), so a
            // brand not stored yet may be resolved here; without one, nothing is saved or charged.
            String brand = SessionManager.get(app).brandForNewJob();
            if (brand == null) return StartResult.NO_BRAND;
            job = newJob(app, store, source, level, brand);
        } else if (AiEditException.NO_SPEECH.equals(job.error)) {
            return StartResult.NO_SPEECH;
        } else if (AiEditJob.STAGE_DONE.equals(job.stage)) {
            if (outputsExist(job)) return StartResult.ALREADY_DONE;
            // The user removed the files: draw them again from the saved plan, or ask for the plan again.
            job.doneRatios.clear();
            File plans = AiEditNames.plansDir(app.getFilesDir());
            if (job.outputBase != null && AiEditNames.planFile(plans, job.outputBase).isFile()) {
                job.stage = AiEditJob.STAGE_RENDER;
            } else {
                job.resetForNewServerJob();
            }
            result = StartResult.RESUMED;
        } else {
            result = StartResult.RESUMED;
        }
        job.clearError();
        store.save(job, System.currentTimeMillis());
        enqueue(app, path, level);
        return result;
    }

    /** Continues every unfinished job (after the app was reopened); a no-op when there is none. */
    public static void resumePending(Context context) {
        Context app = context.getApplicationContext();
        List<AiEditJob> jobs = AiEditStore.get(app).unfinished();
        if (jobs.isEmpty()) return;
        for (AiEditJob job : jobs) enqueueKey(key(job.sourcePath, job.level));
        launchWorker(app);
    }

    /** True while the job of this recording and level is queued or running. */
    public static boolean isActive(String sourcePath, String level) {
        synchronized (LOCK) {
            return isQueuedOrRunning(sourcePath, level);
        }
    }

    /** Main thread only. Pass null to detach. */
    public static void setListener(@Nullable AiEditListener l) {
        listener = l;
    }

    /** The video an edit is made from: the recording itself for one of its crops, else {@code video}. */
    static File sourceOf(File video) {
        if (video == null) return null;
        Matcher m = CROP_SUFFIX.matcher(video.getName());
        if (!m.matches()) return video;
        File master = new File(video.getParentFile(), m.group(1) + ".mp4");
        return master.isFile() ? master : video;
    }

    private static AiEditJob newJob(Context app, AiEditStore store, File source, String level, String brand) {
        String path = source.getAbsolutePath();
        List<String> ratios = new ArrayList<>();
        for (int[] r : RecordSettings.autoCropRatios(app, null)) ratios.add(r[0] + ":" + r[1]);
        if (ratios.isEmpty()) ratios.add("9:16"); // 9:16 is the one result when no crop is enabled
        // An edited video's log times moved with the manual cuts, so its session is its own.
        String fromLog = null;
        if (!EditGroupMath.isEditedName(source.getName())) {
            EventLogData log = EventLogStore.load(EventLogStore.dir(app), source);
            if (log != null) fromLog = log.sessionId;
        }
        AiEditJob job = new AiEditJob(path, level, store.sessionFor(path, fromLog),
                brand, ratios,
                AiEditSettings.textColors(app), AiEditSettings.soundEffects(app));
        // The caption is used only once its words are on the phone (it is READY); else the audio is sent.
        if (SubtitleCache.loadForVideo(app.getCacheDir(), source) != null) {
            job.aiCaptionId = SubtitleEmbedStore.getCaptionId(app, source);
        }
        return job;
    }

    private static boolean outputsExist(AiEditJob job) {
        if (job.outputBase == null || job.doneRatios.isEmpty()) return false;
        for (String ratio : job.doneRatios) {
            if (!AiEditNames.outputFile(job.outputBase, ratio).isFile()) return false;
        }
        return true;
    }

    private static String key(String sourcePath, String level) {
        return sourcePath + "|" + level;
    }

    private static boolean isQueuedOrRunning(String sourcePath, String level) {
        String k = key(sourcePath, level);
        return k.equals(running) || QUEUE.contains(k);
    }

    private static void enqueue(Context app, String sourcePath, String level) {
        enqueueKey(key(sourcePath, level));
        launchWorker(app);
    }

    private static void enqueueKey(String k) {
        synchronized (LOCK) {
            if (!k.equals(running) && !QUEUE.contains(k)) QUEUE.add(k);
        }
    }

    private static void launchWorker(Context app) {
        app.startForegroundService(new Intent(app, AiEditService.class));
    }

    // ---- the service ----

    private final AtomicBoolean cancel = new AtomicBoolean();
    private volatile int lastStartId;
    private NotificationManager notificationManager;
    private String lastNotified = "";

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
                    CHANNEL_ID, getString(R.string.ai_edit_channel_name), NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        lastStartId = startId;
        // Required within seconds of startForegroundService()
        Notification n = progressNotification(getString(R.string.ai_edit_notif_uploading), -1);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
        if (intent == null) {
            // Restarted after the process died: continue what the store says is unfinished.
            for (AiEditJob job : AiEditStore.get(this).unfinished()) enqueueKey(key(job.sourcePath, job.level));
        }
        synchronized (LOCK) {
            if (workerRunning) return START_STICKY;
            workerRunning = true;
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = null;
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FaceCam:aiedit");
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
        }
        final PowerManager.WakeLock lock = wakeLock;
        new Thread(() -> workLoop(lock), "AiEditService").start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        // A render in progress stops and deletes its partial file; the job stays saved and continues later.
        cancel.set(true);
        super.onDestroy();
    }

    private void workLoop(PowerManager.WakeLock wakeLock) {
        AiEditStore store = AiEditStore.get(this);
        AiEditRunner runner = new AiEditRunner(this, store, new Hub(), cancel);
        try {
            while (!cancel.get()) {
                String k;
                synchronized (LOCK) {
                    if (QUEUE.isEmpty()) {
                        workerRunning = false;
                        break;
                    }
                    k = QUEUE.remove(0);
                    running = k;
                }
                try {
                    int bar = k.lastIndexOf('|');
                    AiEditJob job = store.find(k.substring(0, bar), k.substring(bar + 1));
                    if (job != null && job.isUnfinished()) runner.run(job);
                } finally {
                    synchronized (LOCK) {
                        running = null;
                    }
                }
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

    /** Turns the runner's calls into the notification and the main-thread listener. */
    private final class Hub implements AiEditListener {

        @Override
        public void onProgress(final String sourcePath, final String level, final Stage stage,
                               final String ratio, final int percent) {
            String text;
            switch (stage) {
                case QUEUED:
                    text = getString(R.string.ai_edit_notif_queued);
                    break;
                case TRANSCRIBING:
                    text = getString(R.string.ai_edit_notif_transcribing);
                    break;
                case PLANNING:
                    text = getString(R.string.ai_edit_notif_planning);
                    break;
                case RENDERING:
                    text = getString(R.string.ai_edit_notif_rendering, ratio);
                    break;
                default:
                    text = getString(R.string.ai_edit_notif_uploading);
            }
            String shown = text + "|" + percent;
            if (!shown.equals(lastNotified) && notificationManager != null) {
                lastNotified = shown;
                notificationManager.notify(NOTIFICATION_ID, progressNotification(text, percent));
            }
            MAIN.post(() -> {
                if (listener != null) listener.onProgress(sourcePath, level, stage, ratio, percent);
            });
        }

        @Override
        public void onOutput(final String sourcePath, final String level, final File output) {
            MAIN.post(() -> {
                if (listener != null) listener.onOutput(sourcePath, level, output);
            });
        }

        @Override
        public void onDone(final String sourcePath, final String level) {
            if (notificationManager != null) {
                notificationManager.notify(NOTIFICATION_ID,
                        progressNotification(getString(R.string.ai_edit_notif_done), 100));
            }
            lastNotified = "";
            MAIN.post(() -> {
                if (listener != null) {
                    listener.onDone(sourcePath, level);
                } else {
                    // Nobody is watching (app closed or in the background): say it is ready
                    notifyEnded(getString(R.string.ai_edit_notif_done_text));
                }
            });
        }

        @Override
        public void onFailed(final String sourcePath, final String level, final String code,
                             final boolean retryable) {
            lastNotified = "";
            MAIN.post(() -> {
                if (listener != null) {
                    listener.onFailed(sourcePath, level, code, retryable);
                } else if (!AiEditException.CANCELLED.equals(code)) {
                    notifyEnded(getString(R.string.ai_edit_notif_failed_text));
                }
            });
        }
    }

    private void notifyEnded(String text) {
        if (notificationManager == null) return;
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_gallery)
                .setContentIntent(launchIntent())
                .setAutoCancel(true)
                .build();
        notificationManager.notify(DONE_NOTIFICATION_ID, n);
    }

    private Notification progressNotification(String text, int percent) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(percent >= 0 ? text + " " + percent + "%" : text)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentIntent(launchIntent())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setProgress(100, Math.max(0, percent), percent < 0)
                .build();
    }

    @Nullable
    private PendingIntent launchIntent() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch == null) return null;
        launch.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return PendingIntent.getActivity(this, 3, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
