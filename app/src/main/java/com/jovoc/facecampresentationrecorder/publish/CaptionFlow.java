package com.jovoc.facecampresentationrecorder.publish;

import com.jovoc.facecampresentationrecorder.db.PublishJob;
import com.jovoc.facecampresentationrecorder.db.UploadSessionStore;
import com.jovoc.facecampresentationrecorder.entitlements.EntitlementsRefresher;

import java.io.File;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * The AI captions side of one publish job. It runs next to the video upload and must never fail
 * the job: every step catches its own failure and only writes {@link PublishJob#aiCaptionState}.
 *
 * <p>Thread A ({@link #start}): extract the speech WAV, upload it ({@code transcript_audio}), then
 * POST /ai-captions under the job's stable {@code aiCaptionKey}. After the video is up, the pipeline
 * calls {@link Run#finish}: wait for thread A, then poll the caption every {@link #POLL_MS} until it is
 * READY / NO_SPEECH / FAILED or {@link #CAPTION_WAIT_MS} have passed since the video finished. The
 * pipeline then fixes {@code aiCaptionId} (an id or "none") before its first publish call.
 *
 * <p>Nothing here logs or stores transcript or caption text.
 */
public final class CaptionFlow {

    /**
     * The longest a post waits for its captions after the video is up. It is the server's own limit, not
     * the app's: the server ends a caption job that has not moved for 3 hours (it then reads FAILED), so
     * waiting longer can never help. A slow phone or network is never a reason to give up before that;
     * only the user's Cancel, a scheduled post's time ({@link #deadlineFor}) or this limit end the wait.
     */
    public static final long CAPTION_WAIT_MS = 190L * 60_000L;
    static final long POLL_MS = 3_000L;
    /** After {@link #POLL_SLOW_AFTER_MS} of waiting the caption is asked about less often. */
    static final long POLL_SLOW_MS = 10_000L;
    static final long POLL_SLOW_AFTER_MS = 120_000L;
    /** A scheduled post is made this long before its time at the latest. */
    static final long SCHEDULE_MARGIN_MS = 60_000L;
    /**
     * Tries of a thread-A call on a passing SERVER / RATE_LIMITED / IN_PROGRESS answer. A lost connection
     * (NETWORK) is tried again for as long as the run lasts.
     */
    static final int SERVER_ATTEMPTS = 6;
    /** Server errors in a row while polling (about 5 minutes) before the captions are given up. */
    static final int POLL_SERVER_ERRORS = 30;
    /** Pause before the second try; doubled before each later one up to {@link #RETRY_MAX_MS}. */
    static final long RETRY_FIRST_MS = 2_000L;
    static final long RETRY_MAX_MS = 30_000L;

    /** When the wait that starts at {@code start} must end for this job. */
    static long deadlineFor(PublishJob job, long start) {
        long end = start + CAPTION_WAIT_MS;
        if (PublishJob.MODE_SCHEDULE.equals(job.mode) && job.scheduleAt > 0) {
            // A post cannot wait past its own time.
            end = Math.min(end, Math.max(start, job.scheduleAt - SCHEDULE_MARGIN_MS));
        }
        return end;
    }

    /** The speech WAV of a job. */
    public interface Audio {
        /** The WAV of {@code video}, or null when it has no audio (or cannot be read). Blocking. */
        File extract(long jobId, File video);

        /** Deletes the job's WAV, if any. */
        void discard(long jobId);
    }

    /** Uploads the WAV as a transcript_audio upload. */
    public interface AudioUpload {
        /** @return the upload id to give to POST /ai-captions */
        String upload(long jobId, File wav, String workspaceId, BooleanSupplier cancelled)
                throws PublishException;

        /** Forgets the job's stored audio session (no server call). */
        void finish(long jobId);
    }

    public interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }

    /** Writes the job (the pipeline's save: row, listener). */
    public interface Saver {
        void save(PublishJob job);
    }

    /** One thread-A call that may be tried again. */
    private interface Call<T> {
        T run() throws PublishException;
    }

    private final PublishApi api;
    private final Audio audio;
    private final AudioUpload upload;
    private final LongSupplier clock;
    private final Sleeper sleeper;
    private final Runnable onNeedsPlan;

    public CaptionFlow(PublishApi api, Audio audio, AudioUpload upload, LongSupplier clock,
                       Sleeper sleeper, Runnable onNeedsPlan) {
        this.api = api;
        this.audio = audio;
        this.upload = upload;
        this.clock = clock;
        this.sleeper = sleeper;
        this.onNeedsPlan = onNeedsPlan;
    }

    /** The production wiring: ffmpeg extraction into {@code cacheDir}, upload through {@code direct}. */
    public static CaptionFlow create(PublishApi api, DirectUploader direct, UploadSessionStore audioSessions,
                                     File cacheDir, LongSupplier clock) {
        Audio audio = new Audio() {
            @Override
            public File extract(long jobId, File video) {
                return AudioExtractor.extract(cacheDir, jobId, video);
            }

            @Override
            public void discard(long jobId) {
                AudioExtractor.delete(AudioExtractor.outputFor(cacheDir, jobId));
            }
        };
        AudioUpload up = new AudioUpload() {
            @Override
            public String upload(long jobId, File wav, String workspaceId, BooleanSupplier cancelled)
                    throws PublishException {
                return direct.uploadAudio(jobId, wav, workspaceId, audioSessions, null, cancelled).id;
            }

            @Override
            public void finish(long jobId) {
                direct.finish(jobId, audioSessions);
            }
        };
        return new CaptionFlow(api, audio, up, clock, Thread::sleep, EntitlementsRefresher::refreshNow);
    }

    /** True while the job still owes caption work: wanted and no caption id fixed yet. */
    static boolean inFlight(PublishJob job) {
        if (job.aiCaptionId != null) return false;
        String s = job.aiCaptionState;
        return PublishJob.AI_EXTRACTING.equals(s) || PublishJob.AI_UPLOADING.equals(s)
                || PublishJob.AI_PENDING.equals(s);
    }

    /** True for a state the caption can no longer leave. */
    static boolean isSettled(String state) {
        return !(PublishJob.AI_EXTRACTING.equals(state) || PublishJob.AI_UPLOADING.equals(state)
                || PublishJob.AI_PENDING.equals(state));
    }

    /** Deletes the job's WAV (cancel, failure, or after the upload). Never throws. */
    public void discard(long jobId) {
        try {
            audio.discard(jobId);
        } catch (RuntimeException ignored) {
            // a leftover in the cache dir is cleaned by the next run
        }
    }

    /**
     * Starts thread A for a job that wants captions and has no caption id yet, or returns null when
     * there is nothing to do (captions off, or a caption id already stored: only the wait remains).
     */
    public Run start(PublishJob job, BooleanSupplier cancelled, Saver saver) {
        if (!inFlight(job) && !hasPendingId(job)) return null;
        Run run = new Run(job, cancelled, saver);
        if (job.aiCaptionId == null) {
            run.thread = new Thread(run::work, "ai-captions-" + job.id);
            run.thread.start();
        }
        return run;
    }

    private static boolean hasPendingId(PublishJob job) {
        return job.aiCaptionId != null && !PublishJob.AI_CAPTION_NONE.equals(job.aiCaptionId)
                && PublishJob.AI_PENDING.equals(job.aiCaptionState);
    }

    /** One run of the caption job: thread A plus the wait that follows the video. */
    public final class Run {
        private final PublishJob job;
        private final BooleanSupplier cancelled;
        private final Saver saver;
        private volatile boolean aborted;
        private Thread thread;

        Run(PublishJob job, BooleanSupplier cancelled, Saver saver) {
            this.job = job;
            this.cancelled = cancelled;
            this.saver = saver;
        }

        private boolean stop() {
            return aborted || cancelled.getAsBoolean();
        }

        /** Writes the state (and the id, when not null) unless the run was given up. */
        private void set(String state, String id) {
            synchronized (job) {
                if (aborted) return;
                job.aiCaptionState = state;
                if (id != null) job.aiCaptionId = id;
                saver.save(job);
            }
        }

        // ---- thread A ----

        void work() {
            try {
                if (stop()) return;
                set(PublishJob.AI_EXTRACTING, null);
                File wav = audio.extract(job.id, new File(job.filePath));
                if (stop()) return;
                if (wav == null) {
                    set(PublishJob.AI_NO_AUDIO, null);
                    return;
                }
                set(PublishJob.AI_UPLOADING, null);
                // Resumable, so a retry continues where the last try stopped.
                String audioId = retrying(() -> upload.upload(job.id, wav, job.workspaceId, this::stop));
                if (stop()) return;
                // The speech is up: the WAV has done its job.
                audio.discard(job.id);
                synchronized (job) {
                    if (job.aiCaptionKey == null || job.aiCaptionKey.isEmpty()) {
                        job.aiCaptionKey = PublishPipeline.newKey();
                        saver.save(job);
                    }
                }
                // Same aiCaptionKey on every try: the server answers a repeat with the same caption.
                AiCaptionCreated created = retrying(() -> api.createCaption(job, audioId));
                if (created.skippedCredits) {
                    set(PublishJob.AI_SKIPPED_CREDITS, null);
                } else {
                    set(PublishJob.AI_PENDING, created.id);
                }
                // The caption id is stored: the audio row has done its job.
                upload.finish(job.id);
            } catch (PublishException e) {
                fail(e);
            } catch (RuntimeException | OutOfMemoryError e) {
                set(PublishJob.AI_UNAVAILABLE, null);
            } finally {
                audio.discard(job.id);
            }
        }

        /**
         * Runs {@code call}. A lost connection is tried again for as long as the run lasts (the upload
         * resumes where it stopped, so a slow or dropping network costs time, never the captions); a passing
         * server answer (5xx, rate limit) up to {@link #SERVER_ATTEMPTS} times. The pause doubles up to
         * {@link #RETRY_MAX_MS}. It stops at once when the run is given up or cancelled. Any other
         * failure is final.
         */
        private <T> T retrying(Call<T> call) throws PublishException {
            long pause = RETRY_FIRST_MS;
            int passing = 0;
            while (true) {
                try {
                    return call.run();
                } catch (PublishException e) {
                    if (!isTransient(e) || stop()) throw e;
                    if (e.kind != PublishException.Kind.NETWORK && ++passing >= SERVER_ATTEMPTS) throw e;
                }
                try {
                    sleeper.sleep(pause);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new PublishException(PublishException.Kind.CANCELLED);
                }
                if (stop()) throw new PublishException(PublishException.Kind.CANCELLED);
                pause = Math.min(pause * 2, RETRY_MAX_MS);
            }
        }

        /** A caption call failed: only the state changes; the job goes on without captions. */
        private void fail(PublishException e) {
            if (e.kind == PublishException.Kind.CANCELLED || stop()) return;
            switch (e.kind) {
                case NEEDS_PLAN:
                    // The plan changed; fetch it again. No upgrade sheet in the middle of a publish.
                    onNeedsPlan.run();
                    set(PublishJob.AI_UNAVAILABLE, null);
                    break;
                case INSUFFICIENT_CREDITS:
                    set(PublishJob.AI_SKIPPED_CREDITS, null);
                    break;
                default:
                    set(PublishJob.AI_UNAVAILABLE, null);
            }
        }

        /** The job ends (cancel, failure) before the wait: stop thread A; it writes nothing more. */
        public void abandon() {
            synchronized (job) {
                aborted = true;
            }
            if (thread != null) thread.interrupt();
        }

        // ---- the wait after the video ----

        /**
         * Waits for thread A, then polls the caption, until it is final or {@link #deadlineFor} (3 hours,
         * or a scheduled post's time). Always leaves a state on the job, and ends thread A. Throws only
         * CANCELLED.
         */
        public void finish() throws PublishException {
            long start = clock.getAsLong();
            try {
                waitForThread(start);
                poll(start);
            } finally {
                // Whatever happened, no step of thread A may write after this point.
                synchronized (job) {
                    aborted = true;
                }
                if (thread != null) thread.interrupt();
                discard(job.id);
                try {
                    upload.finish(job.id);
                } catch (RuntimeException ignored) {
                    // only a stored row; it is replaced by the next upload of this job
                }
            }
        }

        private long deadline(long start) {
            return deadlineFor(job, start);
        }

        private void waitForThread(long start) throws PublishException {
            while (thread != null && thread.isAlive()) {
                if (cancelled.getAsBoolean()) throw new PublishException(PublishException.Kind.CANCELLED);
                if (clock.getAsLong() >= deadline(start)) return;
                try {
                    thread.join(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new PublishException(PublishException.Kind.CANCELLED);
                }
            }
        }

        private void poll(long start) throws PublishException {
            String id;
            String state;
            synchronized (job) {
                id = job.aiCaptionId;
                state = job.aiCaptionState;
            }
            if (id == null) {
                // Thread A ended without a caption (credits, no audio, unavailable) or is still
                // busy at the deadline: publish with the user's own text.
                if (!isSettled(state)) markTimeout();
                return;
            }
            if (!PublishJob.AI_PENDING.equals(state)) return; // already settled in an earlier run
            int serverErrors = 0; // in a row; a lost connection never counts
            while (true) {
                if (cancelled.getAsBoolean()) throw new PublishException(PublishException.Kind.CANCELLED);
                try {
                    String s = api.captionStatus(id, job.workspaceId).status;
                    serverErrors = 0;
                    if (AiCaptionStatus.READY.equals(s)) {
                        settle(PublishJob.AI_READY, null);
                        return;
                    }
                    if (AiCaptionStatus.NO_SPEECH.equals(s)) {
                        settle(PublishJob.AI_NO_SPEECH, PublishJob.AI_CAPTION_NONE);
                        return;
                    }
                    if (AiCaptionStatus.FAILED.equals(s)) {
                        settle(PublishJob.AI_UNAVAILABLE, PublishJob.AI_CAPTION_NONE);
                        return;
                    }
                } catch (PublishException e) {
                    if (e.kind == PublishException.Kind.CANCELLED) throw e;
                    if (!isTransient(e)) {
                        if (e.kind == PublishException.Kind.NEEDS_PLAN) onNeedsPlan.run();
                        settle(PublishJob.AI_UNAVAILABLE, PublishJob.AI_CAPTION_NONE);
                        return;
                    }
                    if (e.kind != PublishException.Kind.NETWORK && ++serverErrors >= POLL_SERVER_ERRORS) {
                        // The server keeps failing (not the phone's network): post with the user's text.
                        settle(PublishJob.AI_UNAVAILABLE, null);
                        return;
                    }
                }
                long every = clock.getAsLong() - start < POLL_SLOW_AFTER_MS ? POLL_MS : POLL_SLOW_MS;
                if (clock.getAsLong() + every >= deadline(start)) {
                    settle(PublishJob.AI_TIMEOUT, null);
                    return;
                }
                try {
                    sleeper.sleep(every);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new PublishException(PublishException.Kind.CANCELLED);
                }
            }
        }

        /** Gives thread A up for good: it may not write a state or an id after this. */
        private void markTimeout() {
            synchronized (job) {
                aborted = true;
                job.aiCaptionState = PublishJob.AI_TIMEOUT;
                saver.save(job);
            }
        }

        /** Final state after the wait; only the pipeline's thread calls it. */
        private void settle(String state, String id) {
            synchronized (job) {
                job.aiCaptionState = state;
                if (id != null) job.aiCaptionId = id;
                saver.save(job);
            }
        }
    }

    /** A passing failure: worth another try within the wait. */
    static boolean isTransient(PublishException e) {
        return e.kind == PublishException.Kind.NETWORK || e.kind == PublishException.Kind.SERVER
                || e.kind == PublishException.Kind.RATE_LIMITED
                || e.kind == PublishException.Kind.IN_PROGRESS;
    }
}
