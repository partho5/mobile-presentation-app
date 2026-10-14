package com.jovoc.facecampresentationrecorder.publish;

import com.jovoc.facecampresentationrecorder.db.PublishJob;

import java.io.File;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Runs one publish job: upload the video once, then create the post with the job's
 * Idempotency-Key. Every step is written to the {@link Store} first, so a killed process or a
 * dropped connection resumes where it stopped and never posts twice:
 *
 * - the media id is saved right after the upload, so a retry skips the upload;
 * - the same key is sent on every retry of the post call, so the server replays the first
 *   answer instead of creating a second post;
 * - the key is replaced only after the server gave a final 4xx answer (which it would replay),
 *   or the throttler's 429 (stored under the key too; nothing was created, wait, then a new key).
 *
 * An auto-publish job ({@link PublishJob#isAuto()}) takes the same road with two differences: a
 * pre-flight GET /auto-publish runs before the upload (so a missing preset or a short balance
 * costs no 1 GB upload), and the post goes to POST /auto-publish, which builds it from the
 * server's preset. The pre-flight is skipped once the video is uploaded: a retry after an
 * unknown outcome must not be failed by a balance the post itself may already have spent.
 */
public final class PublishPipeline {

    public enum Outcome {
        /** Post accepted by the backend. */
        DONE,
        /** Try again later (offline, 5xx, 429); the job is back to QUEUED. */
        RETRY,
        /** Gave up; the job is FAILED with a reason. */
        FAILED,
        /** The job was cancelled or deleted while running. */
        GONE
    }

    /** Where jobs live (Room in the app, memory in tests). */
    public interface Store {
        PublishJob load(long id);

        void save(PublishJob job);
    }

    public interface Listener {
        /** Called after a state or progress change has been saved. */
        void onChanged(PublishJob job);
    }

    private final PublishApi api;
    private final Store store;
    private final Listener listener;
    private final BooleanSupplier cancelled;
    private final int maxAttempts;
    private final LongSupplier clock;

    public PublishPipeline(PublishApi api, Store store, Listener listener, BooleanSupplier cancelled,
                           int maxAttempts, LongSupplier clock) {
        this.api = api;
        this.store = store;
        this.listener = listener;
        this.cancelled = cancelled;
        this.maxAttempts = maxAttempts;
        this.clock = clock;
    }

    /** @param attempt zero-based run count of this job (WorkManager's run attempt count) */
    public Outcome run(long jobId, int attempt) {
        PublishJob job = store.load(jobId);
        if (job == null) return Outcome.GONE;
        if (PublishJob.DONE.equals(job.status)) return Outcome.DONE;
        job.errorKind = null;
        job.errorDetail = null;

        try {
            boolean uploaded = job.mediaId != null && !job.mediaId.isEmpty();
            if (job.isAuto() && !uploaded) preflight(job);
            if (!uploaded) upload(job);
            // A job deleted while uploading (cancelled) must not go on to post.
            if (store.load(jobId) == null) return Outcome.GONE;
            createPost(job);
            return Outcome.DONE;
        } catch (PublishException e) {
            return fail(job, e, attempt);
        }
    }

    /**
     * Asks the server whether this recording can be auto-published: a preset exists and the
     * balance covers it. Both answers are the server's; the post call still decides.
     */
    private void preflight(PublishJob job) throws PublishException {
        if (cancelled.getAsBoolean()) throw new PublishException(PublishException.Kind.CANCELLED);
        AutoPublishInfo info = api.autoPublishInfo(job.workspaceId);
        if (!info.configured || info.activeChannels().isEmpty()) {
            throw new PublishException(PublishException.Kind.AUTO_NOT_CONFIGURED, 0,
                    info.setupUrl.isEmpty() ? null : info.setupUrl, -1);
        }
        if (!info.canAfford()) {
            throw new PublishException(PublishException.Kind.CREDITS_SHORT, 0,
                    info.cost + "|" + info.available, -1);
        }
        // Remembered so the card and the notification can say "Scheduled" for a next-slot preset.
        job.mode = "next_slot".equals(info.timing) ? PublishJob.MODE_AUTO_SLOT : PublishJob.MODE_AUTO;
        // Kept so a later "Failed on <channel>" can name the channel.
        job.channelsJson = Channel.toJson(info.activeChannels());
        save(job);
    }

    private void upload(PublishJob job) throws PublishException {
        File file = new File(job.filePath);
        if (!file.isFile() || file.length() == 0) throw new PublishException(PublishException.Kind.FILE);
        if (file.length() > PublishApi.MAX_VIDEO_BYTES) {
            throw new PublishException(PublishException.Kind.TOO_LARGE);
        }
        if (cancelled.getAsBoolean()) throw new PublishException(PublishException.Kind.CANCELLED);

        job.progress = 0;
        move(job, PublishJob.UPLOADING);

        final int[] lastSaved = {0};
        PublishApi.Media media = api.upload(file, job.workspaceId, (sent, total) -> {
            int pct = total <= 0 ? 0 : (int) Math.min(99, sent * 100 / total);
            // Saving every chunk would hammer the database; every few percent is plenty.
            if (pct >= lastSaved[0] + 2) {
                lastSaved[0] = pct;
                job.progress = pct;
                save(job);
            }
        }, cancelled);

        job.mediaId = media.id;
        job.mediaPath = media.path;
        job.progress = 100;
        save(job);
    }

    private void createPost(PublishJob job) throws PublishException {
        if (cancelled.getAsBoolean()) throw new PublishException(PublishException.Kind.CANCELLED);
        move(job, PublishJob.PUBLISHING);
        PublishApi.Posted posted = job.isAuto()
                ? api.autoPublish(job)
                : api.createPost(job, clock.getAsLong());
        job.postIds = String.join(",", posted.ids);
        job.postIntegrations = String.join(",", posted.integrations);
        // A new set of posts: whatever the server said about earlier ones no longer applies.
        job.remoteState = null;
        job.remoteChannel = null;
        job.remoteError = null;
        job.remoteUrl = null;
        job.errorKind = null;
        job.errorDetail = null;
        move(job, PublishJob.DONE);
    }

    private Outcome fail(PublishJob job, PublishException e, int attempt) {
        if (e.kind == PublishException.Kind.CANCELLED) return Outcome.GONE;

        if (e.isRetryable() && attempt + 1 < maxAttempts) {
            // Waiting for a connection / the server: keep the media id and the progress, and the
            // key unless the server stored a refusal under it (the throttler's 429).
            job.errorKind = e.kind.name();
            job.errorDetail = null;
            if (e.spendsKey()) job.idempotencyKey = newKey();
            move(job, PublishJob.QUEUED);
            return Outcome.RETRY;
        }

        job.errorKind = e.kind.name();
        job.errorDetail = e.detail;
        if (e.spendsKey()) job.idempotencyKey = newKey();
        if (e.kind == PublishException.Kind.MEDIA_NOT_FOUND) {
            // The server lost the upload: a Retry must send the video again.
            job.mediaId = null;
            job.mediaPath = null;
        }
        move(job, PublishJob.FAILED);
        return Outcome.FAILED;
    }

    public static String newKey() {
        return UUID.randomUUID().toString();
    }

    private void move(PublishJob job, String status) {
        job.status = status;
        save(job);
    }

    private void save(PublishJob job) {
        job.updatedAt = clock.getAsLong();
        store.save(job);
        if (listener != null) listener.onChanged(job);
    }
}
