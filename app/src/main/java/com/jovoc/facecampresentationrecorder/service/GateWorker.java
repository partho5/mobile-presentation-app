package com.jovoc.facecampresentationrecorder.service;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * Does nothing: it exists so the auto-publish undo window can be a WorkManager initial delay
 * without delaying the expedited upload request itself (WorkManager does not allow both on one
 * request). The upload is chained after it under the same unique work name.
 */
public class GateWorker extends Worker {

    public GateWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        return Result.success();
    }
}
