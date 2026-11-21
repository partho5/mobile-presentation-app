package com.jovoc.facecampresentationrecorder.tracker;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.jovoc.facecampresentationrecorder.BuildConfig;
import com.jovoc.facecampresentationrecorder.auth.ApiClient;
import com.jovoc.facecampresentationrecorder.auth.SessionManager;
import com.jovoc.facecampresentationrecorder.db.AppDatabase;
import com.jovoc.facecampresentationrecorder.db.TrackedActivity;
import com.jovoc.facecampresentationrecorder.db.TrackedActivityDao;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Uploads the buffered activity records in batches. Runs only with a validated internet connection
 * (see {@link TrackerScheduler}). Checks {@link UploadPolicy} first unless the input says "force".
 * Never shows anything to the user; a failed run is retried by WorkManager, up to a limit.
 */
public class TrackerUploadWorker extends Worker {

    static final String KEY_FORCE = "force";

    public TrackerUploadWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            Context c = getApplicationContext();
            if (!Connectivity.hasInternet(c)) return again();                  // check internet before sending
            TrackedActivityDao dao = AppDatabase.getInstance(c).trackedActivityDao();
            TrackerPrefs prefs = new TrackerPrefs(c);
            boolean force = getInputData().getBoolean(KEY_FORCE, false);
            if (!force && !UploadPolicy.isDue(dao.count(), prefs.lastSentAt(), System.currentTimeMillis())) {
                return Result.success();
            }
            TrackerApi api = new TrackerApi(new ApiClient(BuildConfig.API_BASE_URL, BuildConfig.DEBUG),
                    () -> SessionManager.get(c).repository().quietAccessToken());
            String deviceId = DeviceId.get(c);
            for (int i = 0; i < TrackerConfig.MAX_BATCHES_PER_RUN; i++) {
                String batchId = dao.openBatchId();                            // an unconfirmed batch is resent unchanged
                if (batchId == null) {
                    batchId = UUID.randomUUID().toString();
                    if (dao.assignBatch(batchId, TrackerConfig.BATCH_SIZE) == 0) return Result.success();
                }
                List<TrackedActivity> rows = dao.inBatch(batchId);
                if (rows == null || rows.isEmpty()) continue;
                String body = TrackerPayload.build(batchId, deviceId, System.currentTimeMillis(),
                        appInfo(), deviceInfo(), jsonsOf(rows));
                if (body == null) {
                    dao.deleteBatch(batchId);
                    continue;
                }
                switch (api.send(body)) {
                    case SENT:
                        dao.deleteBatch(batchId);
                        prefs.setLastSentAt(System.currentTimeMillis());
                        break;
                    case DROP:
                        dao.deleteBatch(batchId);
                        break;
                    default:
                        return again();
                }
            }
            if (dao.count() > 0) TrackerScheduler.drainNow(c);                 // more left: a follow-up run
            return Result.success();
        } catch (Throwable t) {
            return again();
        }
    }

    /**
     * Result.success() after the last attempt is on purpose: WorkManager stops retrying, and the next
     * trigger or the daily job tries again.
     */
    private Result again() {
        return getRunAttemptCount() >= TrackerConfig.MAX_UPLOAD_ATTEMPTS ? Result.success() : Result.retry();
    }

    private static TrackerPayload.AppInfo appInfo() {
        return new TrackerPayload.AppInfo(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.APPLICATION_ID);
    }

    private static TrackerPayload.DeviceInfo deviceInfo() {
        return new TrackerPayload.DeviceInfo("Android " + Build.VERSION.RELEASE, Build.VERSION.SDK_INT,
                Build.MANUFACTURER, Build.MODEL, Locale.getDefault().toLanguageTag());
    }

    private static List<String> jsonsOf(List<TrackedActivity> rows) {
        List<String> out = new ArrayList<>(rows.size());
        for (TrackedActivity r : rows) out.add(r.json);
        return out;
    }
}
