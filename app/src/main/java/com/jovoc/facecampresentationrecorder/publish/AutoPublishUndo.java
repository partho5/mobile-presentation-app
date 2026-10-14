package com.jovoc.facecampresentationrecorder.publish;

import com.jovoc.facecampresentationrecorder.db.PublishJob;

/**
 * The undo window of an auto-publish: after a recording is ready, the upload waits this long so
 * the user can still cancel. The wait is a WorkManager initial delay (it survives the process
 * being killed); this class only holds the numbers and the arithmetic, so it is plain Java.
 *
 * <p>The row remembers when the window ends in {@link PublishJob#scheduleAt}, which is unused for
 * auto jobs otherwise (no Room schema change). A QUEUED auto job with no error whose deadline is
 * still ahead is "waiting out its delay"; once the deadline passes it is simply queued.
 */
public final class AutoPublishUndo {

    private AutoPublishUndo() {}

    public static final int OFF = 0;
    public static final int DEFAULT_SECONDS = 30;
    public static final int MAX_SECONDS = 60;

    /** A stored value that is not one of the offered choices falls back to the default. */
    public static int normalize(int seconds) {
        return seconds == OFF || seconds == 30 || seconds == MAX_SECONDS ? seconds : DEFAULT_SECONDS;
    }

    /** WorkManager initial delay for a setting, in milliseconds (0 = start straight away). */
    public static long delayMillis(int seconds) {
        return normalize(seconds) * 1000L;
    }

    /** Milliseconds left in the window, or 0 when the job is not waiting it out. */
    public static long millisLeft(PublishJob job, long now) {
        if (job == null || !job.isAuto() || !PublishJob.QUEUED.equals(job.status)
                || job.errorKind != null || job.scheduleAt <= now) {
            return 0L;
        }
        // A clock moved back must not show a countdown longer than the longest setting.
        return Math.min(job.scheduleAt - now, MAX_SECONDS * 1000L);
    }

    /** Whole seconds left in the window (rounded up), or 0 when the job is not waiting it out. */
    public static int secondsLeft(PublishJob job, long now) {
        long left = millisLeft(job, now);
        return left <= 0 ? 0 : (int) ((left + 999) / 1000);
    }

    /**
     * True while the card's countdown has to be redrawn: inside the window, plus the tick after it
     * ends so the card moves on to "Queued" / "Uploading".
     */
    public static boolean needsTick(PublishJob job, long now) {
        return job != null && job.isAuto() && PublishJob.QUEUED.equals(job.status)
                && job.errorKind == null && job.scheduleAt > 0 && now < job.scheduleAt + 1500L;
    }
}
