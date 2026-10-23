package com.jovoc.facecampresentationrecorder.publish;

import com.jovoc.facecampresentationrecorder.activitylog.ActivityLog;
import com.jovoc.facecampresentationrecorder.auth.ApiClient;
import com.jovoc.facecampresentationrecorder.db.PublishJob;
import com.jovoc.facecampresentationrecorder.db.UploadSession;
import com.jovoc.facecampresentationrecorder.db.UploadSessionDao;
import com.jovoc.facecampresentationrecorder.entitlements.EntitlementsRefresher;

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
 * Either call may answer 403 FEATURE_NOT_IN_PLAN: the job then fails as NEEDS_PLAN at once (no
 * retry) and the stored plan is fetched again.
 *
 * <p>A video goes to R2 through {@link DirectUploader} (the backend carries no video byte);
 * anything else, and a server that answers 404 to the direct routes (remembered per job), uses
 * the legacy POST /upload. None of the upload calls carries the post's Idempotency-Key, so a
 * failure of the upload step never rotates it.
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
    /** Null: every file takes the legacy POST /upload. */
    private final DirectUploader direct;
    private final UploadSessionDao sessions;
    private final Store store;
    private final Listener listener;
    private final BooleanSupplier cancelled;
    private final int maxAttempts;
    private final LongSupplier clock;
    /** Makes the poster of a video; null: no poster is sent. */
    private final PosterSource posters;
    /** AI captions next to the video upload; null: no captions. */
    private final CaptionFlow captions;

    /** Base64 of the poster image of a video file, or null for none; must not throw. */
    public interface PosterSource {
        String posterFor(File video);
    }

    /** Legacy upload only (no direct upload). */
    public PublishPipeline(PublishApi api, Store store, Listener listener, BooleanSupplier cancelled,
                           int maxAttempts, LongSupplier clock) {
        this(api, null, null, store, listener, cancelled, maxAttempts, clock, null);
    }

    /**
     * @param direct sends videos straight to R2; null for the legacy upload only
     * @param sessions where {@code direct} keeps each job's upload (and the legacy choice)
     */
    public PublishPipeline(PublishApi api, DirectUploader direct, UploadSessionDao sessions,
                           Store store, Listener listener, BooleanSupplier cancelled,
                           int maxAttempts, LongSupplier clock) {
        this(api, direct, sessions, store, listener, cancelled, maxAttempts, clock, null);
    }

    /** @param posters makes the poster sent with the direct upload's {@code complete}; may be null */
    public PublishPipeline(PublishApi api, DirectUploader direct, UploadSessionDao sessions,
                           Store store, Listener listener, BooleanSupplier cancelled,
                           int maxAttempts, LongSupplier clock, PosterSource posters) {
        this(api, direct, sessions, store, listener, cancelled, maxAttempts, clock, posters, null);
    }

    /** @param captions runs the AI captions of jobs that want them; may be null (none) */
    public PublishPipeline(PublishApi api, DirectUploader direct, UploadSessionDao sessions,
                           Store store, Listener listener, BooleanSupplier cancelled,
                           int maxAttempts, LongSupplier clock, PosterSource posters,
                           CaptionFlow captions) {
        this.api = api;
        this.direct = direct;
        this.sessions = sessions;
        this.store = store;
        this.listener = listener;
        this.cancelled = cancelled;
        this.maxAttempts = maxAttempts;
        this.clock = clock;
        this.posters = posters;
        this.captions = captions;
    }

    /** @param attempt zero-based run count of this job (WorkManager's run attempt count) */
    public Outcome run(long jobId, int attempt) {
        PublishJob job = store.load(jobId);
        if (job == null) {
            forgetUpload(jobId);
            return Outcome.GONE;
        }
        if (PublishJob.DONE.equals(job.status)) return Outcome.DONE;
        job.errorKind = null;
        job.errorDetail = null;

        CaptionFlow.Run caption = null;
        try {
            boolean uploaded = job.mediaId != null && !job.mediaId.isEmpty();
            if (job.isAuto() && !uploaded) preflight(job);
            // Thread A (audio -> caption) runs beside the video upload; it never throws.
            caption = startCaption(job);
            if (!uploaded) upload(job);
            // A job deleted while uploading (cancelled) must not go on to post.
            if (store.load(jobId) == null) {
                forgetUpload(jobId);
                return Outcome.GONE;
            }
            settleCaption(job, caption);
            caption = null;
            createPost(job);
            return Outcome.DONE;
        } catch (PublishException e) {
            if (caption != null) caption.abandon(); // before fail() saves the job
            return fail(job, e, attempt);
        } finally {
            // Cancel, failure or done: the extracted WAV and thread A end with the run.
            if (caption != null) caption.abandon();
            if (captions != null) captions.discard(jobId);
        }
    }

    /** Starts thread A for a job that wants AI captions and has none yet; null when not needed. */
    private CaptionFlow.Run startCaption(PublishJob job) {
        if (captions == null) return null;
        try {
            return captions.start(job, cancelled, this::save);
        } catch (RuntimeException e) {
            return null; // AI never fails the job
        }
    }

    /**
     * After the video: waits for the caption until it is final (or {@link CaptionFlow#CAPTION_WAIT_MS},
     * the server's own limit; a scheduled post's time if sooner), then fixes what the publish call will send and stores it, so every retry under the same
     * Idempotency-Key sends the same body: the caption id (or "none") and, for a manual "now" post,
     * its date. Only a cancel escapes this method.
     */
    private void settleCaption(PublishJob job, CaptionFlow.Run caption) throws PublishException {
        long waitStart = clock.getAsLong();
        if (caption != null) caption.finish();
        synchronized (job) {
            // Counts and states only, never caption or transcript text.
            if (caption != null) {
                ActivityLog.log("ai_captions_result", "state", String.valueOf(job.aiCaptionState),
                        "wait_ms", Math.max(0L, clock.getAsLong() - waitStart), "mode", job.mode);
            }
            if (job.aiCaptionId == null || job.aiCaptionId.isEmpty()) {
                job.aiCaptionId = PublishJob.AI_CAPTION_NONE;
            }
            if (PublishJob.MODE_NOW.equals(job.mode) && !job.isAuto()
                    && (job.publishDate == null || job.publishDate.isEmpty())) {
                job.publishDate = PublishRequestBuilder.isoUtc(clock.getAsLong());
            }
            save(job);
        }
    }

    /**
     * Asks the server whether this recording can be auto-published: some channel will receive it
     * and the balance covers it. Both answers are the server's; the post call still decides.
     */
    private void preflight(PublishJob job) throws PublishException {
        if (cancelled.getAsBoolean()) throw new PublishException(PublishException.Kind.CANCELLED);
        AutoPublishInfo info = api.autoPublishInfo(job.workspaceId);
        if (!info.configured || info.activeChannels().isEmpty()) {
            // No detail: the fix is the Auto upload screen, not the server's setup link.
            throw new PublishException(PublishException.Kind.AUTO_NOT_CONFIGURED);
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
        // No size check here: the direct upload's cap (MOBILE_UPLOAD_MAX_BYTES, 4 GiB) is the
        // server's to enforce (FILE_TOO_LARGE on create). The 1 GiB cap is checked below, only
        // for the legacy /upload route.
        if (cancelled.getAsBoolean()) throw new PublishException(PublishException.Kind.CANCELLED);

        job.progress = 0;
        move(job, PublishJob.UPLOADING);

        final int[] lastSaved = {0};
        ApiClient.ProgressListener progress = (sent, total) -> {
            int pct = total <= 0 ? 0 : (int) Math.min(99, sent * 100 / total);
            // Saving every chunk would hammer the database; every few percent is plenty.
            if (pct >= lastSaved[0] + 2) {
                lastSaved[0] = pct;
                job.progress = pct;
                save(job);
            }
        };

        PublishApi.Media media = null;
        if (useDirect(job, file)) {
            try {
                // The poster is made lazily, just before complete, from the file uploaded here.
                media = direct.upload(job.id, file, job.workspaceId, sessions, progress, cancelled,
                        (java.util.function.Supplier<String>) () -> posterFor(file)).toMedia();
            } catch (PublishException e) {
                if (!isRouteMissing(e)) throw directFailure(job, e);
                // An older server without /upload/multipart: use the legacy upload, and remember
                // it for this job so a retry does not ask again.
                rememberLegacy(job);
            }
        }
        if (media == null) {
            // Legacy route (non-mp4, older server, or the fallback just above): its cap is 1 GiB.
            if (file.length() > PublishApi.LEGACY_MAX_VIDEO_BYTES) {
                throw new PublishException(PublishException.Kind.TOO_LARGE);
            }
            try {
                media = api.upload(file, job.workspaceId, progress, cancelled);
            } catch (PublishException e) {
                throw e.forUploadStep();
            }
        }

        job.mediaId = media.id;
        job.mediaPath = media.path;
        job.progress = 100;
        save(job);
        // The media id is stored: the upload row has done its job (a kill before this line
        // replays the idempotent complete and gets the same media).
        forgetUpload(job.id);
    }

    /** Videos the server accepts on the direct routes (by extension, as the server decides). */
    static boolean isDirectType(File file) {
        return file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".mp4");
    }

    private boolean useDirect(PublishJob job, File file) {
        if (direct == null || sessions == null || !isDirectType(file)) return false;
        UploadSession row = sessions.get(job.id);
        return row == null || !UploadSession.LEGACY.equals(row.status);
    }

    /**
     * A 4xx without an upload code from a direct route: the server has no such route (404), or
     * a proxy in front of it says so. Anything with a code is a real answer of the routes.
     */
    private static boolean isRouteMissing(PublishException e) {
        return e.isRouteMissing();
    }

    private void rememberLegacy(PublishJob job) {
        UploadSession row = new UploadSession();
        row.jobId = job.id;
        row.uploadId = "";
        row.size = 0;
        row.partSize = 0;
        row.partCount = 0;
        row.partsJson = UploadPlan.partsToJson(new java.util.TreeMap<>());
        row.status = UploadSession.LEGACY;
        sessions.put(row);
    }

    /**
     * A failure of the direct upload, as the pipeline reports it: marked as an upload-step
     * failure (the idempotency key is never spent), and a session that cannot continue is
     * dropped so the user's Retry starts a new one.
     */
    private PublishException directFailure(PublishJob job, PublishException e) {
        boolean cannotContinue = e.kind != PublishException.Kind.CANCELLED
                && e.isUploadSession() && !e.isRetryable();
        if (cannotContinue) forgetUpload(job.id);
        return e.forUploadStep();
    }

    /** Forgets the job's upload row (no server call). */
    private void forgetUpload(long jobId) {
        if (direct != null && sessions != null) direct.finish(jobId, sessions);
    }

    /**
     * The poster image sent with {@code complete} (base64 of a jpeg, png or webp up to 512 KiB),
     * or null for none. It is made from {@code video}, the file actually uploaded (the edited
     * crop when the user edited, never the master). Never fails the upload: any error is null.
     */
    String posterFor(File video) {
        if (posters == null) return null;
        try {
            String poster = posters.posterFor(video);
            return poster == null || poster.isEmpty() ? null : poster;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
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
        ActivityLog.log("publish_result", "ok", true, "mode", job.mode);
        move(job, PublishJob.DONE);
    }

    private Outcome fail(PublishJob job, PublishException e, int attempt) {
        if (e.kind == PublishException.Kind.CANCELLED) return Outcome.GONE;

        if (e.kind == PublishException.Kind.NEEDS_PLAN) {
            // The plan decides, so waiting cannot help: no retry. Fetch the plan again so the
            // switch on the Auto upload screen follows the server; the caller tells the user.
            EntitlementsRefresher.refreshNow();
        }

        if (e.isRetryable() && attempt + 1 < maxAttempts) {
            // Waiting for a connection / the server: keep the media id and the progress, and the
            // key unless the server stored a refusal under it (the throttler's 429).
            job.errorKind = e.kind.name();
            job.errorDetail = null;
            if (e.spendsKey()) rotateKey(job);
            move(job, PublishJob.QUEUED);
            return Outcome.RETRY;
        }

        job.errorKind = e.kind.name();
        job.errorDetail = e.detail;
        if (e.spendsKey()) rotateKey(job);
        if (e.kind == PublishException.Kind.MEDIA_NOT_FOUND) {
            // The server lost the upload: a Retry must send the video again.
            job.mediaId = null;
            job.mediaPath = null;
        }
        if (e.kind == PublishException.Kind.MEDIA_PURGED) {
            // The server deleted the video: never retried on its own. A Retry (offered only when
            // the local file exists) uploads it again in a new session and gets a new media id,
            // so the removed id and any stored upload session of this job are dropped now.
            PurgedMedia.forgetMedia(job);
            forgetUpload(job.id);
        }
        ActivityLog.log("publish_result", "ok", false, "kind", e.kind.name(), "mode", job.mode);
        move(job, PublishJob.FAILED);
        return Outcome.FAILED;
    }

    /** A new publish key means a new body is allowed: the stored "now" date is made again. */
    private static void rotateKey(PublishJob job) {
        job.idempotencyKey = newKey();
        job.publishDate = null;
    }

    public static String newKey() {
        return UUID.randomUUID().toString();
    }

    private void move(PublishJob job, String status) {
        job.status = status;
        save(job);
    }

    /** Thread A saves the same job object: one writer at a time. */
    private void save(PublishJob job) {
        synchronized (job) {
            job.updatedAt = clock.getAsLong();
            store.save(job);
        }
        if (listener != null) listener.onChanged(job);
    }
}
