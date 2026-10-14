package com.jovoc.facecampresentationrecorder.publish;

import com.jovoc.facecampresentationrecorder.db.PublishJob;

import java.io.File;

/**
 * The step that turns a waiting auto-publish job (recording processed) into queued work, shared
 * by the end-of-processing release, the startup sweep and "recording stopped with nothing to
 * process". The undo delay is applied here, so every road into the queue respects it, and only a
 * job still in WAITING_PROCESSING moves: a second call (sweep after release) schedules nothing.
 */
public final class AutoPublishRelease {

    private AutoPublishRelease() {}

    /** A row written this recently may still be waiting for its work to be enqueued. */
    static final long SETTLE_MS = 10_000L;

    /** True while {@code updatedAt} is within {@link #SETTLE_MS} of {@code now} (reconcile skips it). */
    public static boolean isSettling(long updatedAt, long now) {
        return now - updatedAt < SETTLE_MS;
    }

    public enum Result {
        /** The job was not waiting (already released, cancelled, or finished): nothing done. */
        NOT_WAITING,
        /** The recording is gone: the row was deleted, nothing to publish. */
        DROPPED,
        /** Queued with no delay (undo set to Off). */
        QUEUED_NOW,
        /** Queued behind the undo window. */
        QUEUED_DELAYED
    }

    /** Where rows are written (Room in the app). */
    public interface Jobs {
        void update(PublishJob job);

        void delete(long id);
    }

    /** Starts the background work for a job after {@code delayMs} (WorkManager in the app). */
    public interface Scheduler {
        void schedule(long jobId, long delayMs);
    }

    /**
     * @param file        the file to publish (crop or processed original)
     * @param undoSeconds the Settings choice; normalised here
     */
    public static Result release(PublishJob job, File file, int undoSeconds, long now,
                                 Jobs jobs, Scheduler scheduler) {
        if (!PublishJob.WAITING_PROCESSING.equals(job.status)) return Result.NOT_WAITING;
        if (!file.isFile() || file.length() == 0) {
            jobs.delete(job.id);
            return Result.DROPPED;
        }
        long delayMs = AutoPublishUndo.delayMillis(undoSeconds);
        job.filePath = file.getAbsolutePath();
        job.fileName = file.getName();
        job.status = PublishJob.QUEUED;
        job.errorKind = null;
        job.errorDetail = null;
        job.scheduleAt = delayMs > 0 ? now + delayMs : 0L;
        job.updatedAt = now;
        jobs.update(job);
        scheduler.schedule(job.id, delayMs);
        return delayMs > 0 ? Result.QUEUED_DELAYED : Result.QUEUED_NOW;
    }
}
