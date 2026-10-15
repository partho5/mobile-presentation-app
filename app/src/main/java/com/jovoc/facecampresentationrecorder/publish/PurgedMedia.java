package com.jovoc.facecampresentationrecorder.publish;

import com.jovoc.facecampresentationrecorder.db.PublishJob;

import java.io.File;

/**
 * A job that ended because the server deleted the uploaded video ({@code 410 MEDIA_PURGED}, see
 * the retention rules in the API guide section 4.9). The card shows "Video removed from server";
 * Retry uploads the local file again (a new upload, a new media id) when it still exists, and is
 * not offered when it does not.
 */
public final class PurgedMedia {

    /** {@link PublishException.Kind#MEDIA_PURGED} as stored in {@link PublishJob#errorKind}. */
    public static final String KIND = "MEDIA_PURGED";

    private PurgedMedia() {}

    /** True for a failed job whose video the server removed. */
    public static boolean is(PublishJob job) {
        return job != null && PublishJob.FAILED.equals(job.status) && KIND.equals(job.errorKind);
    }

    /** True when the recording the job uploads is still on the phone and not empty. */
    public static boolean fileExists(PublishJob job) {
        if (job == null || job.filePath == null || job.filePath.isEmpty()) return false;
        File f = new File(job.filePath);
        return f.isFile() && f.length() > 0;
    }

    /** True when the removed video cannot be uploaded again: Retry is not offered. */
    public static boolean cannotRetry(PublishJob job) {
        return is(job) && !fileExists(job);
    }

    /**
     * Forgets the uploaded video so the next run uploads the file as a new media (the removed
     * id would be refused again). The upload session is dropped by the pipeline / manager.
     */
    public static void forgetMedia(PublishJob job) {
        job.mediaId = null;
        job.mediaPath = null;
        job.progress = 0;
    }
}
