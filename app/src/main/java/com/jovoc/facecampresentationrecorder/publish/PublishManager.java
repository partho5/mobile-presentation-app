package com.jovoc.facecampresentationrecorder.publish;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.jovoc.facecampresentationrecorder.auth.SessionManager;
import com.jovoc.facecampresentationrecorder.db.AppDatabase;
import com.jovoc.facecampresentationrecorder.db.PublishJob;
import com.jovoc.facecampresentationrecorder.db.PublishJobDao;
import com.jovoc.facecampresentationrecorder.service.BgReplaceService;
import com.jovoc.facecampresentationrecorder.service.GateWorker;
import com.jovoc.facecampresentationrecorder.service.PublishWorker;
import com.jovoc.facecampresentationrecorder.util.RecordSettings;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
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

    /** Marks the upload request of a job's unique chain (the undo gate does not carry it). */
    static final String TAG_UPLOAD = "publish-upload";
    private static final String LOG_TAG = "PublishManager";

    private static PublishManager instance;

    private final Context app;
    private final PublishJobDao dao;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    /** Status look-ups wait on the network; they must not hold up publish actions on {@link #io}. */
    private final ExecutorService statusIo = Executors.newSingleThreadExecutor();

    private static final long MIN_STATUS_INTERVAL_MS = 20_000L;
    private boolean statusRunning;
    private long lastStatusAt;

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
            if (job.isRemoteFailed()) {
                // The backend accepted it but the channel refused: a new publish. The old key
                // would replay the first answer, so it is replaced; the upload is kept.
                job.resetForNewPublish(PublishPipeline.newKey());
            }
            job.status = PublishJob.QUEUED;
            job.errorKind = null;
            job.errorDetail = null;
            job.updatedAt = System.currentTimeMillis();
            dao.update(job);
            schedule(app, id);
            broadcast(app);
        });
    }

    // ---- live post status ----

    /**
     * Asks the server how recently accepted posts ended up and stores the final answers on their
     * jobs (the card then reads Live / Failed on ... / Deleted on web). One call per 50 posts and
     * workspace; nothing runs in the background, this is called when the screen opens or resumes.
     * Skipped when signed out, when one is already running or when one ran moments ago.
     */
    public void refreshStatuses() {
        if (!SessionManager.get(app).hasSession()) return;
        long now = System.currentTimeMillis();
        synchronized (this) {
            if (statusRunning || now - lastStatusAt < MIN_STATUS_INTERVAL_MS) return;
            statusRunning = true;
            lastStatusAt = now;
        }
        statusIo.execute(() -> {
            try {
                PublishApi api = new PublishApi(SessionManager.get(app).repository());
                int changed = PostStatusSync.refresh(api, dao.doneAwaitingRemote(), now, (job, r) ->
                        dao.setRemote(job.id, job.postIds, r.state, r.channel, r.error, r.url));
                if (changed > 0) broadcast(app);
            } catch (RuntimeException ignored) {
                // A failed look-up changes nothing; the next resume asks again.
            } finally {
                synchronized (this) {
                    statusRunning = false;
                }
            }
        });
    }

    // ---- auto-publish ----

    /** Recordings whose auto-publish row exists but whose processing is not queued yet; io thread only. */
    private final Set<String> starting = new HashSet<>();
    /** Renamed-while-waiting recordings; io thread only. */
    private final RenameAliases aliases = new RenameAliases();

    /**
     * A recording just stopped: when the Settings checkbox is on and the user is signed in, saves
     * an auto-publish job that waits for the post-processing. Call this BEFORE
     * {@link BgReplaceService#enqueue}, and {@link #onRecordingStopped} after it: the row is
     * durable first, so a process death right after the recording stops cannot lose the job (the
     * startup sweep publishes it). Waits briefly for the insert; the workspace is pinned now.
     */
    public void beginAuto(File video) {
        Future<?> saved = io.submit(() -> {
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
            // Marked first: a sweep must not release the row before the processing is queued.
            starting.add(path);
            job.id = dao.insert(job);
            broadcast(app);
        });
        try {
            saved.get(3, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Slow disk: the insert still completes on its thread; do not hold the caller longer.
        }
    }

    /**
     * The recording's post-processing is queued (or there is none): starts the job saved by
     * {@link #beginAuto} when there is nothing to wait for. Otherwise {@link #releaseAuto}
     * (processing finished) or the sweep (process killed) starts it.
     */
    public void onRecordingStopped(File video) {
        io.execute(() -> {
            String path = video.getAbsolutePath();
            starting.remove(path);
            PublishJob job = dao.latestFor(path);
            if (job != null && !BgReplaceService.isProcessing(path)) release(job);
        });
    }

    /**
     * The recording's post-processing is over: start its waiting auto-publish job, if any. Safe
     * to call twice and for recordings without a job. {@code originalPath} is the path the
     * processing was queued with, which may be an older name of a recording renamed meanwhile.
     */
    public void releaseAuto(String originalPath) {
        io.execute(() -> {
            starting.remove(originalPath);
            PublishJob job = dao.latestFor(originalPath);
            if (job == null) {
                String renamed = aliases.currentFor(originalPath);
                if (renamed != null) job = dao.latestFor(renamed);
            }
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
                String root = aliases.rootOf(job.filePath);
                boolean busy = starting.contains(job.filePath)
                        || BgReplaceService.isProcessing(job.filePath)
                        || (root != null && (starting.contains(root) || BgReplaceService.isProcessing(root)));
                if (!busy) release(job);
            }
        });
    }

    /**
     * Waiting -> queued with the 9:16 crop (or the original) as the file, behind the undo window
     * from Settings. Every road in (processing finished, nothing to process, startup sweep) comes
     * here, and only a row still WAITING_PROCESSING moves, so the delay is applied once and a job
     * already waiting it out is never enqueued again. Runs on {@link #io}.
     */
    private void release(PublishJob job) {
        if (!PublishJob.WAITING_PROCESSING.equals(job.status)) return;
        String root = aliases.rootOf(job.filePath);
        aliases.forget(job.filePath);
        File file = AutoPublishFiles.pick(new File(job.filePath), root == null ? null : new File(root));
        int undo = AutoPublishUndo.normalize(
                app.getSharedPreferences(RecordSettings.PREF_NAME, Context.MODE_PRIVATE)
                        .getInt(RecordSettings.KEY_AUTO_PUBLISH_UNDO_SECONDS, AutoPublishUndo.DEFAULT_SECONDS));
        long now = System.currentTimeMillis();
        AutoPublishRelease.Result result = AutoPublishRelease.release(job, file, undo, now,
                new AutoPublishRelease.Jobs() {
                    @Override
                    public void update(PublishJob j) {
                        dao.update(j);
                    }

                    @Override
                    public void delete(long id) {
                        // The recording was deleted meanwhile: nothing to publish, nothing to report.
                        dao.deleteById(id);
                    }
                },
                (jobId, delayMs) -> schedule(app, jobId, delayMs));
        if (result == AutoPublishRelease.Result.QUEUED_DELAYED) {
            UndoNotifier.show(app, job, AutoPublishUndo.delayMillis(undo), now);
        }
        if (result != AutoPublishRelease.Result.NOT_WAITING) broadcast(app);
    }

    /**
     * Called before the user picks a workspace while none was picked: jobs made under the server's
     * default are pinned to it ({@code defaultWorkspaceId}) so they do not follow the switch.
     */
    public void pinDefaultWorkspace(String defaultWorkspaceId) {
        io.execute(() -> dao.pinUnpinned(defaultWorkspaceId));
    }

    /**
     * Stops the job and forgets it; the recording goes back to "not published". Inside the undo
     * window nothing has been uploaded yet, so nothing is sent and no credit is touched.
     */
    public void cancel(long id) {
        cancel(id, null);
    }

    /** Same, and {@code done} runs once the job is gone (the notification action waits on it). */
    public void cancel(long id, Runnable done) {
        io.execute(() -> {
            try {
                // Stop the work first so it cannot finish a post for a job that is being forgotten.
                WorkManager.getInstance(app).cancelUniqueWork(workName(id));
                dao.deleteById(id);
                UndoNotifier.cancel(app, id);
                broadcast(app);
            } finally {
                if (done != null) done.run();
            }
        });
    }

    /**
     * The "Upload on Wi-Fi only" setting changed: queued uploads get the matching network
     * constraint through {@link WorkManager#updateWork}, which swaps the constraint of the very
     * same work in place. Nothing is cancelled or replaced, so an upload that has just started is
     * never stopped (a stop would make the worker drop the job), and the run attempts, the unique
     * name, a waiting undo gate, the idempotency key and the undo deadline all stay as they are.
     * Only QUEUED jobs with live upload work are touched; an uploading / publishing job finishes
     * under the constraint it started with. If WorkManager cannot apply the change the job simply
     * keeps its old constraint.
     */
    public void onWifiOnlyChanged() {
        io.execute(() -> {
            boolean changed = false;
            WorkManager wm = WorkManager.getInstance(app);
            for (PublishJob job : dao.active()) {
                if (!PublishJob.QUEUED.equals(job.status)) continue;
                try {
                    List<WorkInfo> infos = wm.getWorkInfosForUniqueWork(workName(job.id))
                            .get(5, TimeUnit.SECONDS);
                    for (WorkInfo w : infos) {
                        // The undo gate has its own (delay-only) constraints: leave it alone.
                        if (w.getState().isFinished()) continue;
                        if (!PublishNetwork.isUploadWork(w.getTags(), TAG_UPLOAD,
                                PublishWorker.class.getName())) {
                            continue;
                        }
                        WorkManager.UpdateResult result = wm.updateWork(
                                uploadBuilder(app, job.id).setId(w.getId()).build())
                                .get(5, TimeUnit.SECONDS);
                        if (result == WorkManager.UpdateResult.NOT_APPLIED) {
                            Log.w(LOG_TAG, "Wi-Fi setting not applied to job " + job.id);
                        } else {
                            changed = true;
                        }
                    }
                } catch (Exception e) {
                    // Could not ask / update WorkManager: the job keeps its old constraint.
                    Log.w(LOG_TAG, "Wi-Fi setting not applied to job " + job.id, e);
                }
            }
            if (changed) broadcast(app);
        });
    }

    /** Keeps the status attached to a recording that was renamed. */
    public void onRenamed(File oldFile, File newFile) {
        io.execute(() -> {
            String oldPath = oldFile.getAbsolutePath();
            PublishJob latest = dao.latestFor(oldPath);
            boolean waiting = latest != null && PublishJob.WAITING_PROCESSING.equals(latest.status);
            dao.rename(oldPath, newFile.getAbsolutePath(), newFile.getName());
            // The processing service still knows the old path: remember it so the job is found
            // (and its crop picked) when the processing ends.
            if (waiting) aliases.renamed(oldPath, newFile.getAbsolutePath());
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
        long now = System.currentTimeMillis();
        for (PublishJob job : active) {
            // A row written a moment ago may be between its DB update and its WorkManager enqueue
            // (auto-publish release): no work yet does not mean the work is gone.
            if (AutoPublishRelease.isSettling(job.updatedAt, now)) continue;
            try {
                List<WorkInfo> infos = WorkManager.getInstance(app)
                        .getWorkInfosForUniqueWork(workName(job.id)).get(5, TimeUnit.SECONDS);
                boolean alive = false;
                for (WorkInfo w : infos) alive |= !w.getState().isFinished();
                if (!alive) {
                    // Re-read: the row may have moved on (released, cancelled) while we asked.
                    PublishJob fresh = dao.get(job.id);
                    if (fresh == null || !fresh.isActive()
                            || AutoPublishRelease.isSettling(fresh.updatedAt, System.currentTimeMillis())) {
                        continue;
                    }
                    job = fresh;
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
        schedule(context, jobId, 0L);
    }

    /**
     * @param delayMs the auto-publish undo window (0 = start now). Implemented as a {@link
     *                GateWorker} with an initial delay followed by the expedited upload; WorkManager
     *                persists it, so it survives the process being killed.
     */
    public static void schedule(Context context, long jobId, long delayMs) {
        OneTimeWorkRequest upload = uploadBuilder(context, jobId).build();
        WorkManager wm = WorkManager.getInstance(context);
        if (delayMs > 0) {
            // WorkManager refuses setInitialDelay + setExpedited on one request, and un-expedited
            // upload work loses the foreground-service exemption (job time cap, Doze). So the
            // delay is its own do-nothing request and the expedited upload runs after it. Both
            // live under one unique name: cancelUniqueWork and the reconcile look-up cover the
            // whole chain, and REPLACE drops a chain still waiting (a
            // new schedule() is an explicit retry / release; a setting change uses updateWork).
            OneTimeWorkRequest gate = new OneTimeWorkRequest.Builder(GateWorker.class)
                    .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                    .build();
            wm.beginUniqueWork(workName(jobId), ExistingWorkPolicy.REPLACE, gate)
                    .then(upload)
                    .enqueue();
        } else {
            wm.enqueueUniqueWork(workName(jobId), ExistingWorkPolicy.REPLACE, upload);
        }
    }

    /**
     * The upload request: carries {@link #TAG_UPLOAD} so the Wi-Fi setting can tell it from the
     * undo gate. {@link WorkManager#updateWork} replaces the tags with the new request's, so
     * every request that updates this work must be built here too.
     */
    private static OneTimeWorkRequest.Builder uploadBuilder(Context context, long jobId) {
        return new OneTimeWorkRequest.Builder(PublishWorker.class)
                .setInputData(new Data.Builder().putLong(PublishWorker.KEY_JOB_ID, jobId).build())
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(PublishNetwork.requiredNetwork(PublishNetwork.isWifiOnly(context)))
                        .build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag(TAG_UPLOAD);
    }

    public static void broadcast(Context context) {
        context.sendBroadcast(new Intent(ACTION_STATE_CHANGED).setPackage(context.getPackageName()));
    }
}
