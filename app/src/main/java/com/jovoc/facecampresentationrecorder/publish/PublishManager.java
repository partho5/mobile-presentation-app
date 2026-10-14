package com.jovoc.facecampresentationrecorder.publish;

import android.content.Context;
import android.content.Intent;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.jovoc.facecampresentationrecorder.auth.SessionManager;
import com.jovoc.facecampresentationrecorder.db.AppDatabase;
import com.jovoc.facecampresentationrecorder.db.PublishJob;
import com.jovoc.facecampresentationrecorder.db.PublishJobDao;
import com.jovoc.facecampresentationrecorder.service.BgReplaceService;
import com.jovoc.facecampresentationrecorder.service.PublishWorker;
import com.jovoc.facecampresentationrecorder.util.RecordSettings;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Entry point for the UI: queue, retry and cancel publish jobs, and read their state. Database
 * work runs on its own thread; the UI hears about changes through {@link #ACTION_STATE_CHANGED}.
 */
public final class PublishManager {

    /** Local broadcast (this app only) sent whenever a job's state or progress changes. */
    public static final String ACTION_STATE_CHANGED =
            "com.jovoc.facecampresentationrecorder.PUBLISH_STATE_CHANGED";

    private static PublishManager instance;

    private final Context app;
    private final PublishJobDao dao;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    public static synchronized PublishManager get(Context context) {
        if (instance == null) instance = new PublishManager(context.getApplicationContext());
        return instance;
    }

    private PublishManager(Context app) {
        this.app = app;
        this.dao = AppDatabase.getInstance(app).publishJobDao();
    }

    /** Saves the job and starts it. The job's fields other than the input are set here. */
    public void enqueue(PublishJob job, Runnable onQueued) {
        io.execute(() -> {
            PublishJob existing = dao.latestFor(job.filePath);
            if (existing == null || !existing.isActive()) {
                long now = System.currentTimeMillis();
                // The job stays in the workspace picked now, even if the user switches later.
                if (job.workspaceId == null) {
                    job.workspaceId = SessionManager.get(app).repository().store().workspaceId();
                }
                job.status = PublishJob.QUEUED;
                job.progress = 0;
                job.idempotencyKey = PublishPipeline.newKey();
                job.createdAt = now;
                job.updatedAt = now;
                job.id = dao.insert(job);
                schedule(app, job.id);
                broadcast(app);
            }
            if (onQueued != null) onQueued.run();
        });
    }

    /** Runs a failed job again with its saved choices; the upload is skipped if it already finished. */
    public void retry(long id) {
        io.execute(() -> {
            PublishJob job = dao.get(id);
            if (job == null || job.isActive()) return;
            job.status = PublishJob.QUEUED;
            job.errorKind = null;
            job.errorDetail = null;
            job.updatedAt = System.currentTimeMillis();
            dao.update(job);
            schedule(app, id);
            broadcast(app);
        });
    }

    // ---- auto-publish ----

    /**
     * A recording just stopped (and its post-processing was queued). When the Settings checkbox is
     * on and the user is signed in, remembers it as an auto-publish job that waits for the
     * processing; {@link #releaseAuto} (or the sweep) starts it. The workspace is pinned now.
     * Call after {@link BgReplaceService#enqueue}, so "is it processing?" is already true.
     */
    public void onRecordingStopped(File video) {
        io.execute(() -> {
            boolean on = app.getSharedPreferences(RecordSettings.PREF_NAME, Context.MODE_PRIVATE)
                    .getBoolean(RecordSettings.KEY_AUTO_PUBLISH, false);
            if (!on || !SessionManager.get(app).hasSession()) return;
            String path = video.getAbsolutePath();
            PublishJob existing = dao.latestFor(path);
            if (existing != null && existing.isActive()) return;

            long now = System.currentTimeMillis();
            PublishJob job = new PublishJob();
            job.filePath = path;
            job.fileName = video.getName();
            job.mode = PublishJob.MODE_AUTO;
            job.status = PublishJob.WAITING_PROCESSING;
            job.idempotencyKey = PublishPipeline.newKey();
            job.workspaceId = SessionManager.get(app).repository().store().workspaceId();
            job.createdAt = now;
            job.updatedAt = now;
            job.id = dao.insert(job);
            broadcast(app);
            // Nothing to wait for (no background replace, no crops), or it already finished.
            if (!BgReplaceService.isProcessing(path)) release(job);
        });
    }

    /**
     * The recording's post-processing is over: start its waiting auto-publish job, if any. Safe
     * to call twice and for recordings without a job.
     */
    public void releaseAuto(String originalPath) {
        io.execute(() -> {
            PublishJob job = dao.latestFor(originalPath);
            if (job != null) release(job);
        });
    }

    /**
     * Starts every waiting auto-publish job whose processing is no longer running, which covers a
     * process killed mid-crop: the job is not lost, it publishes whatever file exists.
     */
    public void sweepAuto() {
        io.execute(() -> {
            for (PublishJob job : dao.waitingProcessing()) {
                if (!BgReplaceService.isProcessing(job.filePath)) release(job);
            }
        });
    }

    /** Waiting -> queued with the 9:16 crop (or the original) as the file. Runs on {@link #io}. */
    private void release(PublishJob job) {
        if (!PublishJob.WAITING_PROCESSING.equals(job.status)) return;
        File file = AutoPublishFiles.pick(new File(job.filePath));
        if (!file.isFile() || file.length() == 0) {
            // The recording was deleted meanwhile: nothing to publish, nothing to report.
            dao.deleteById(job.id);
            broadcast(app);
            return;
        }
        job.filePath = file.getAbsolutePath();
        job.fileName = file.getName();
        job.status = PublishJob.QUEUED;
        job.errorKind = null;
        job.errorDetail = null;
        job.updatedAt = System.currentTimeMillis();
        dao.update(job);
        schedule(app, job.id);
        broadcast(app);
    }

    /**
     * Called before the user picks a workspace while none was picked: jobs made under the server's
     * default are pinned to it ({@code defaultWorkspaceId}) so they do not follow the switch.
     */
    public void pinDefaultWorkspace(String defaultWorkspaceId) {
        io.execute(() -> dao.pinUnpinned(defaultWorkspaceId));
    }

    /** Stops the job and forgets it; the recording goes back to "not published". */
    public void cancel(long id) {
        io.execute(() -> {
            // Stop the work first so it cannot finish a post for a job that is being forgotten.
            WorkManager.getInstance(app).cancelUniqueWork(workName(id));
            dao.deleteById(id);
            broadcast(app);
        });
    }

    /** Keeps the status attached to a recording that was renamed. */
    public void onRenamed(File oldFile, File newFile) {
        io.execute(() -> {
            dao.rename(oldFile.getAbsolutePath(), newFile.getAbsolutePath(), newFile.getName());
            broadcast(app);
        });
    }

    /** The newest job of one recording, or null. Blocking: call off the main thread. */
    public PublishJob latestFor(String filePath) {
        return dao.latestFor(filePath);
    }

    /** The newest job of every recording, by file path. Blocking: call off the main thread. */
    public Map<String, PublishJob> latestByPath() {
        Map<String, PublishJob> map = new HashMap<>();
        for (PublishJob j : dao.allNewestFirst()) {
            if (j.filePath != null && !map.containsKey(j.filePath)) map.put(j.filePath, j);
        }
        return map;
    }

    /**
     * Fails jobs that claim to be running but whose work is gone (data cleared, work cancelled
     * from outside), so a card never shows "Uploading" forever. Blocking: call off the main thread.
     */
    public void reconcile() {
        List<PublishJob> active = dao.active();
        for (PublishJob job : active) {
            try {
                List<WorkInfo> infos = WorkManager.getInstance(app)
                        .getWorkInfosForUniqueWork(workName(job.id)).get(5, TimeUnit.SECONDS);
                boolean alive = false;
                for (WorkInfo w : infos) alive |= !w.getState().isFinished();
                if (!alive) {
                    job.status = PublishJob.FAILED;
                    job.errorKind = "INTERRUPTED";
                    job.updatedAt = System.currentTimeMillis();
                    dao.update(job);
                }
            } catch (Exception ignored) {
                // Could not ask WorkManager; leave the job as it is.
            }
        }
    }

    // ---- shared with the worker ----

    public static String workName(long jobId) {
        return "publish-" + jobId;
    }

    public static void schedule(Context context, long jobId) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(PublishWorker.class)
                .setInputData(new Data.Builder().putLong(PublishWorker.KEY_JOB_ID, jobId).build())
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build();
        WorkManager.getInstance(context)
                .enqueueUniqueWork(workName(jobId), ExistingWorkPolicy.REPLACE, request);
    }

    public static void broadcast(Context context) {
        context.sendBroadcast(new Intent(ACTION_STATE_CHANGED).setPackage(context.getPackageName()));
    }
}
