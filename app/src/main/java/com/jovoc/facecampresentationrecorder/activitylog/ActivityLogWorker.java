package com.jovoc.facecampresentationrecorder.activitylog;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.jovoc.facecampresentationrecorder.db.ActivityEvent;
import com.jovoc.facecampresentationrecorder.db.ActivityEventDao;
import com.jovoc.facecampresentationrecorder.db.AppDatabase;

import java.util.List;
import java.util.UUID;

/**
 * Uploads the buffered activity events. Runs only on an unmetered network (see
 * {@link ActivityLogScheduler}), also when the app is closed. Checks {@link UploadPolicy} first, so
 * a run that is no longer needed does nothing; a "force" run (the follow-up for a big buffer) skips
 * that check. Sends oldest first, up to {@link UploadPolicy#MAX_BATCHES_PER_RUN} requests per run.
 */
public class ActivityLogWorker extends Worker {

    static final String KEY_FORCE = "force";

    /** Failed runs in a row before this run gives up; the next trigger or period tries again. */
    private static final int MAX_ATTEMPTS = 8;

    public ActivityLogWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            Context context = getApplicationContext();
            ActivityLogPrefs prefs = new ActivityLogPrefs(context);
            ActivityEventDao dao = AppDatabase.getInstance(context).activityEventDao();
            boolean force = getInputData().getBoolean(KEY_FORCE, false);
            if (!force && !UploadPolicy.isDue(dao.count(), prefs.lastSendAt(), System.currentTimeMillis())) {
                return Result.success();
            }
            return drain(context, dao, prefs, ActivityLogApi.create(context), DeviceId.get(context));
        } catch (Exception e) {
            return retryOrGiveUp();
        }
    }

    private Result drain(Context context, ActivityEventDao dao, ActivityLogPrefs prefs,
                         ActivityLogApi api, String deviceId) {
        for (int i = 0; i < UploadPolicy.MAX_BATCHES_PER_RUN; i++) {
            // A batch that was started but not confirmed is resent as it was (same id, same rows).
            String batchId = dao.openBatchId();
            if (batchId == null) {
                batchId = UUID.randomUUID().toString();
                if (dao.assignBatch(batchId, UploadPolicy.BATCH_MAX) == 0) return Result.success();
            }
            List<ActivityEvent> rows = dao.inBatch(batchId);
            if (rows.isEmpty()) continue;
            switch (api.send(batchId, deviceId, rows)) {
                case SENT:
                    dao.deleteBatch(batchId);
                    prefs.setLastSendAt(System.currentTimeMillis());
                    break;
                case DROP:
                    dao.deleteBatch(batchId);
                    break;
                default:
                    return retryOrGiveUp();
            }
        }
        if (dao.count() > 0) ActivityLogScheduler.enqueueOneTime(context, 0L, true);
        return Result.success();
    }

    private Result retryOrGiveUp() {
        return getRunAttemptCount() >= MAX_ATTEMPTS ? Result.failure() : Result.retry();
    }
}
