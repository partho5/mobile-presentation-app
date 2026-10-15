package com.jovoc.facecampresentationrecorder.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.ServiceInfo;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.ForegroundInfo;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.auth.AuthRepository;
import com.jovoc.facecampresentationrecorder.auth.SessionManager;
import com.jovoc.facecampresentationrecorder.db.AppDatabase;
import com.jovoc.facecampresentationrecorder.db.PublishJob;
import com.jovoc.facecampresentationrecorder.db.PublishJobDao;
import com.jovoc.facecampresentationrecorder.db.UploadSessionDao;
import com.jovoc.facecampresentationrecorder.publish.AutoPublishNotifier;
import com.jovoc.facecampresentationrecorder.publish.DirectUploadApi;
import com.jovoc.facecampresentationrecorder.publish.DirectUploader;
import com.jovoc.facecampresentationrecorder.publish.PublishApi;
import com.jovoc.facecampresentationrecorder.publish.PublishManager;
import com.jovoc.facecampresentationrecorder.publish.PublishPipeline;
import com.jovoc.facecampresentationrecorder.publish.UndoNotifier;

/**
 * Runs one {@link PublishJob} in the background (WorkManager): runs only with a connection,
 * shows a notification with progress and a Cancel action while it works, and is retried with
 * backoff when the network drops. All the logic lives in {@link PublishPipeline}.
 */
public class PublishWorker extends Worker {

    public static final String KEY_JOB_ID = "job_id";

    /** Network / server hiccups are retried this many runs before the job is marked failed. */
    static final int MAX_ATTEMPTS = 6;

    private static final String CHANNEL_ID = "publish_upload";

    private final PublishJobDao dao;
    private final UploadSessionDao sessions;
    private int lastNotifiedPercent = -10;

    public PublishWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
        AppDatabase db = AppDatabase.getInstance(context);
        dao = db.publishJobDao();
        sessions = db.uploadSessionDao();
    }

    @NonNull
    @Override
    public Result doWork() {
        long id = getInputData().getLong(KEY_JOB_ID, -1L);
        PublishJob first = dao.get(id);
        if (first == null) return Result.success();

        // The undo-window notification ends here; the progress notification takes over.
        UndoNotifier.cancel(getApplicationContext(), id);
        try {
            setForegroundAsync(foregroundInfo(id, first.fileName, 0));
        } catch (RuntimeException ignored) {
            // Not allowed to start a foreground service right now; the work still runs.
        }

        AuthRepository repo = SessionManager.get(getApplicationContext()).repository();
        PublishApi api = new PublishApi(repo);
        PublishPipeline pipeline = new PublishPipeline(api,
                new DirectUploader(new DirectUploadApi(repo)), sessions,
                new PublishPipeline.Store() {
                    @Override
                    public PublishJob load(long jobId) {
                        return dao.get(jobId);
                    }

                    @Override
                    public void save(PublishJob job) {
                        dao.update(job);
                    }
                },
                this::onJobChanged, this::isStopped, MAX_ATTEMPTS, System::currentTimeMillis);

        PublishPipeline.Outcome outcome = pipeline.run(id, getRunAttemptCount());

        if (isStopped()) {
            settleAfterStop(id);
            return Result.success();
        }
        PublishManager.broadcast(getApplicationContext());
        // Nobody watches an auto-publish: say how it ended. Not again for a job that was already
        // DONE when this run started (a replay after the process was killed).
        boolean ended = outcome == PublishPipeline.Outcome.DONE || outcome == PublishPipeline.Outcome.FAILED;
        if (ended && first.isAuto() && !PublishJob.DONE.equals(first.status)) {
            AutoPublishNotifier.show(getApplicationContext(), dao.get(id));
        }
        return outcome == PublishPipeline.Outcome.RETRY ? Result.retry() : Result.success();
    }

    /** Cancelled by the user (notification Cancel): forget the job. Stopped by the system: queue it again. */
    private void settleAfterStop(long id) {
        PublishJob job = dao.get(id);
        if (job != null && job.isActive()) {
            if (getStopReason() == WorkInfo.STOP_REASON_CANCELLED_BY_APP) {
                dao.deleteById(id);
                // Best-effort: frees the server-side upload (and the row) of a cancelled job.
                try {
                    new DirectUploader(new DirectUploadApi(
                            SessionManager.get(getApplicationContext()).repository()))
                            .cancel(id, job.workspaceId, sessions);
                } catch (RuntimeException ignored) {
                    // It expires on the server anyway.
                }
            } else {
                job.status = PublishJob.QUEUED;
                dao.update(job);
            }
        }
        PublishManager.broadcast(getApplicationContext());
    }

    private void onJobChanged(PublishJob job) {
        PublishManager.broadcast(getApplicationContext());
        if (PublishJob.UPLOADING.equals(job.status) && job.progress >= lastNotifiedPercent + 5) {
            lastNotifiedPercent = job.progress;
            try {
                setForegroundAsync(foregroundInfo(job.id, job.fileName, job.progress));
            } catch (RuntimeException ignored) {
                // see doWork
            }
        }
    }

    @NonNull
    @Override
    public ForegroundInfo getForegroundInfo() {
        long id = getInputData().getLong(KEY_JOB_ID, -1L);
        PublishJob job = dao.get(id);
        return foregroundInfo(id, job == null ? "" : job.fileName, 0);
    }

    private ForegroundInfo foregroundInfo(long jobId, String fileName, int percent) {
        Context ctx = getApplicationContext();
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                    ctx.getString(R.string.publish_channel_name), NotificationManager.IMPORTANCE_LOW));
        }
        Notification n = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(ctx.getString(R.string.publish_notif_title))
                .setContentText(fileName)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, percent, percent <= 0)
                .addAction(0, ctx.getString(R.string.publish_notif_cancel),
                        WorkManager.getInstance(ctx).createCancelPendingIntent(getId()))
                .build();
        return new ForegroundInfo(UndoNotifier.notificationId(jobId), n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    }
}
