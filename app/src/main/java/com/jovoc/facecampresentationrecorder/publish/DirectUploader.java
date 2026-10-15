package com.jovoc.facecampresentationrecorder.publish;

import com.jovoc.facecampresentationrecorder.auth.ApiClient;
import com.jovoc.facecampresentationrecorder.db.UploadSession;
import com.jovoc.facecampresentationrecorder.db.UploadSessionDao;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Sends a recording straight to R2 in the parts the server decided on (api.mobile-direct-upload),
 * so no video byte crosses the backend. Blocking: call off the main thread (the publish worker).
 *
 * <ul>
 *   <li>One {@link UploadSession} row per job is written as soon as the upload exists and after
 *       every finished part, so a kill loses at most the parts in flight.</li>
 *   <li>Resume: a stored session is checked with GET status and R2's list of parts is trusted;
 *       only the missing parts are sent. A file whose size changed invalidates the session
 *       (abort, start over).</li>
 *   <li>Part PUTs use their own OkHttp client with no interceptor, so the device token never
 *       reaches R2 (see {@link #newR2Client()}).</li>
 *   <li>Two parts in flight; per part up to {@link UploadPlan#MAX_TRIES} tries with exponential
 *       backoff; 403 (expired URL) asks for a fresh URL; a response without ETag fails the part.</li>
 * </ul>
 *
 * <p>The session row is kept after a successful {@link #upload}: if the process dies before the
 * caller saved the media id, the next run replays {@code complete} (idempotent on the server) and
 * gets the same media instead of uploading again. The caller deletes the row with
 * {@link #finish} once the media id is stored, and with {@link #cancel} when the job is cancelled.
 */
public class DirectUploader {

    /** Sleeps for the backoff; replaced in tests. */
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /** Polls of GET status (every 4 s, about 2 min) while the server says "completing". */
    static final int COMPLETING_POLLS = 30;
    static final long COMPLETING_POLL_MS = 4_000L;
    /** Safety bound of the resume / complete loop; each round is a distinct server answer. */
    static final int MAX_ROUNDS = 8;
    /** Cancel checks happen at least this often while waiting. */
    private static final long SLEEP_SLICE_MS = 250L;

    private final DirectUploadApi api;
    private final OkHttpClient r2;
    private final Sleeper sleeper;
    private final LongSupplier clockMs;

    public DirectUploader(DirectUploadApi api) {
        this(api, newR2Client(), Thread::sleep, System::currentTimeMillis);
    }

    DirectUploader(DirectUploadApi api, OkHttpClient r2, Sleeper sleeper, LongSupplier clockMs) {
        this.api = api;
        this.r2 = r2;
        this.sleeper = sleeper;
        this.clockMs = clockMs;
    }

    /**
     * The client for part PUTs to R2: no interceptor and no authenticator of any kind, never
     * shared with the backend client. No overall call timeout (a 16 MiB part on a slow link
     * may take minutes), a stall limit per write and per read.
     */
    public static OkHttpClient newR2Client() {
        return new OkHttpClient.Builder()
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    /** {@link #upload(long, File, String, UploadSessionDao, ApiClient.ProgressListener, BooleanSupplier, String)} without a poster. */
    public CompletedUpload upload(long jobId, File file, String workspaceId, UploadSessionDao sessions,
                                  ApiClient.ProgressListener progress, BooleanSupplier cancelled)
            throws PublishException {
        return upload(jobId, file, workspaceId, sessions, progress, cancelled, (String) null);
    }

    /**
     * Uploads {@code file} (or resumes the job's stored upload) and completes it.
     *
     * @param jobId the publish job; its session row is the resume point
     * @param workspaceId the job's workspace (x-workspace-id); null uses the device's pick
     * @param sessions where the session and its finished parts are persisted
     * @param progress total bytes sent out of the file size, throttled; may be null
     * @param cancelled polled between chunks; true stops with {@link PublishException.Kind#CANCELLED}
     * @param posterBase64 optional poster for {@code complete} (a refused poster is dropped and
     *                     the call repeated without it); null or empty sends none
     * @return the media ({@code id}, {@code path}) to give to the post
     * @throws PublishException the mapped failure; the session row stays, so a retry resumes
     */
    public CompletedUpload upload(long jobId, File file, String workspaceId, UploadSessionDao sessions,
                                  ApiClient.ProgressListener progress, BooleanSupplier cancelled,
                                  String posterBase64) throws PublishException {
        return upload(jobId, file, workspaceId, sessions, progress, cancelled, () -> posterBase64);
    }

    /**
     * Like the poster overload, but the poster is made only when {@code complete} is about to be
     * sent (once), so a frame grab never delays the first byte and a resume that needs no
     * complete never makes one. The supplier may return null; if it throws, no poster is sent.
     */
    public CompletedUpload upload(long jobId, File file, String workspaceId, UploadSessionDao sessions,
                                  ApiClient.ProgressListener progress, BooleanSupplier cancelled,
                                  java.util.function.Supplier<String> posterSource)
            throws PublishException {
        if (file == null || !file.isFile() || file.length() == 0) {
            throw new PublishException(PublishException.Kind.FILE);
        }
        final long size = file.length();
        final BooleanSupplier stop = cancelled != null ? cancelled : () -> false;
        String poster = null;
        boolean posterMade = false;

        UploadSession row = sessions.get(jobId);
        if (row != null && !UploadPlan.isUsable(row, size)) {
            drop(jobId, row.uploadId, workspaceId, sessions, true);
            row = null;
        }
        boolean needStatus = row != null;
        Map<Integer, String> finished = row != null
                ? UploadPlan.partsFromJson(row.partsJson) : new TreeMap<>();

        for (int round = 0; round < MAX_ROUNDS; round++) {
            checkStopped(stop);

            if (row == null) {
                row = create(jobId, file, size, workspaceId, sessions);
                finished = new TreeMap<>();
                needStatus = false;
            } else if (needStatus) {
                UploadStatus st;
                try {
                    st = api.status(row.uploadId, workspaceId);
                } catch (PublishException e) {
                    if (e.kind != PublishException.Kind.UPLOAD_NOT_FOUND) throw e;
                    drop(jobId, row.uploadId, workspaceId, sessions, false);
                    row = null;
                    continue;
                }
                if ((st.size > 0 && st.size != size)
                        || (st.partSize > 0 && st.partSize != row.partSize)) {
                    // Not the upload this row describes: start over rather than mix the two.
                    drop(jobId, row.uploadId, workspaceId, sessions, true);
                    row = null;
                    continue;
                }
                if (st.isPending()) {
                    Map<Integer, String> resumed = UploadPlan.resumeParts(
                            st.parts, finished, size, row.partSize, row.partCount);
                    if (!resumed.equals(finished)) {
                        finished = resumed;
                        sessions.setParts(jobId, UploadPlan.partsToJson(finished));
                    }
                } else if (UploadStatus.COMPLETING.equals(st.status)) {
                    // Another call is finishing it (seconds); wait, then look again.
                    waitWhileCompleting(row.uploadId, workspaceId, stop);
                    continue;
                } else if (UploadStatus.COMPLETED.equals(st.status)) {
                    if (!UploadPlan.isComplete(finished.keySet(), row.partCount)) {
                        // Finished elsewhere and the etags are gone: nothing to replay with.
                        drop(jobId, row.uploadId, workspaceId, sessions, false);
                        row = null;
                        continue;
                    }
                } else {
                    // aborted, expired or unknown: it cannot be continued.
                    drop(jobId, row.uploadId, workspaceId, sessions, false);
                    row = null;
                    continue;
                }
                needStatus = false;
            }

            try {
                new Transfer(jobId, file, size, row, finished, workspaceId, sessions, progress, stop)
                        .run();
                List<UploadedPart> parts = new ArrayList<>();
                for (Map.Entry<Integer, String> e : finished.entrySet()) {
                    parts.add(new UploadedPart(e.getKey(), e.getValue(), -1));
                }
                if (!posterMade) {
                    posterMade = true;
                    try {
                        poster = posterSource == null ? null : posterSource.get();
                    } catch (Exception | OutOfMemoryError ignored) {
                        poster = null; // a bonus only: never fails the upload
                    }
                }
                CompletedUpload done = api.complete(row.uploadId, parts, poster, workspaceId);
                sessions.setStatus(jobId, UploadSession.COMPLETED);
                if (progress != null) progress.onProgress(size, size);
                return done;
            } catch (PublishException e) {
                switch (e.kind) {
                    case INVALID_POSTER:
                        if (poster == null || poster.isEmpty()) throw e;
                        poster = null; // never fatal for the upload
                        continue;
                    case INVALID_PARTS:        // R2 refused a part: ask what it really holds
                    case UPLOAD_NOT_PENDING:   // finishing, finished or gone: ask
                        needStatus = true;
                        continue;
                    case UPLOAD_NOT_FOUND:
                        drop(jobId, row.uploadId, workspaceId, sessions, false);
                        row = null;
                        continue;
                    default:
                        throw e;
                }
            }
        }
        // The server keeps contradicting itself: leave a clean slate for the next attempt.
        if (row != null) drop(jobId, row.uploadId, workspaceId, sessions, true);
        throw new PublishException(PublishException.Kind.SERVER);
    }

    /**
     * The job is finished (its media id is saved) or no longer needs the upload: forget the
     * session. Does not talk to the server.
     */
    public void finish(long jobId, UploadSessionDao sessions) {
        sessions.delete(jobId);
    }

    /**
     * The job was cancelled: abort the server-side upload (best-effort; a failure only leaves it
     * to expire) and delete the row. Safe when the job has no session.
     */
    public void cancel(long jobId, String workspaceId, UploadSessionDao sessions) {
        UploadSession row = sessions.get(jobId);
        if (row == null) return;
        drop(jobId, row.uploadId, workspaceId, sessions, true);
    }

    // ---- internals ----

    private UploadSession create(long jobId, File file, long size, String workspaceId,
                                 UploadSessionDao sessions) throws PublishException {
        UploadSessionInfo info = api.create(file.getName(), size, workspaceId);
        if (UploadPlan.partCountFor(size, info.partSize) != info.partCount) {
            // The server's own numbers do not fit the file: nothing to send against.
            abortQuietly(info.id, workspaceId);
            throw new PublishException(PublishException.Kind.SERVER);
        }
        UploadSession row = new UploadSession();
        row.jobId = jobId;
        row.uploadId = info.id;
        row.size = size;
        row.partSize = info.partSize;
        row.partCount = info.partCount;
        row.partsJson = UploadPlan.partsToJson(new TreeMap<>());
        row.status = UploadSession.PENDING;
        sessions.put(row); // before anything else: a lost id keeps one of the 5 pending slots
        return row;
    }

    private void drop(long jobId, String uploadId, String workspaceId, UploadSessionDao sessions,
                      boolean abortFirst) {
        if (abortFirst) abortQuietly(uploadId, workspaceId);
        sessions.delete(jobId);
    }

    private void abortQuietly(String uploadId, String workspaceId) {
        if (uploadId == null || uploadId.isEmpty()) return;
        try {
            api.abort(uploadId, workspaceId);
        } catch (PublishException | RuntimeException ignored) {
            // Best-effort: the server expires it anyway.
        }
    }

    /** Polls status every few seconds while "completing"; gives up (retryable) after about 2 min. */
    private void waitWhileCompleting(String uploadId, String workspaceId, BooleanSupplier stop)
            throws PublishException {
        for (int i = 0; i < COMPLETING_POLLS; i++) {
            pause(COMPLETING_POLL_MS, stop);
            UploadStatus st = api.status(uploadId, workspaceId);
            if (!UploadStatus.COMPLETING.equals(st.status)) return;
        }
        throw new PublishException(PublishException.Kind.SERVER);
    }

    private static void checkStopped(BooleanSupplier stop) throws PublishException {
        if (stop.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new PublishException(PublishException.Kind.CANCELLED);
        }
    }

    /** Waits {@code millis}, in slices so a cancel is noticed quickly. */
    private void pause(long millis, BooleanSupplier stop) throws PublishException {
        long left = millis;
        while (left > 0) {
            checkStopped(stop);
            long slice = Math.min(SLEEP_SLICE_MS, left);
            try {
                sleeper.sleep(slice);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PublishException(PublishException.Kind.CANCELLED);
            }
            left -= slice;
        }
    }

    /** A part PUT that R2 answered with something other than 2xx. */
    private static final class PartHttpException extends IOException {
        final int code;

        PartHttpException(int code) {
            this(code, "HTTP " + code);
        }

        PartHttpException(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** One pass over the parts that are still missing, 2 at a time. */
    private final class Transfer {
        private final long jobId;
        private final File file;
        private final long size;
        private final String uploadId;
        private final long partSize;
        private final String workspaceId;
        private final UploadSessionDao sessions;
        private final ApiClient.ProgressListener progress;
        private final BooleanSupplier cancelled;

        /** Shared with the caller: part number to ETag; guarded by itself. */
        private final Map<Integer, String> finished;
        private final ConcurrentLinkedQueue<Integer> queue;
        private final Map<Integer, long[]> urlStamp = new HashMap<>(); // n -> {signedAtMs, lifeSec}
        private final Map<Integer, String> urls = new HashMap<>();
        private final Object urlLock = new Object();
        private final AtomicLong sent;
        private final ProgressThrottle throttle;
        private final Object progressLock = new Object();
        private final AtomicBoolean stop = new AtomicBoolean();
        private PublishException failure;

        Transfer(long jobId, File file, long size, UploadSession row, Map<Integer, String> finished,
                 String workspaceId, UploadSessionDao sessions, ApiClient.ProgressListener progress,
                 BooleanSupplier cancelled) {
            this.jobId = jobId;
            this.file = file;
            this.size = size;
            this.uploadId = row.uploadId;
            this.partSize = row.partSize;
            this.workspaceId = workspaceId;
            this.sessions = sessions;
            this.progress = progress;
            this.cancelled = cancelled;
            this.finished = finished;
            this.queue = new ConcurrentLinkedQueue<>(UploadPlan.missing(row.partCount, finished.keySet()));
            this.sent = new AtomicLong(UploadPlan.bytesOf(finished.keySet(), size, partSize));
            this.throttle = new ProgressThrottle(size, clockMs);
        }

        void run() throws PublishException {
            report();
            int workers = Math.min(UploadPlan.PARALLEL_PARTS, queue.size());
            if (workers == 0) return;
            ExecutorService pool = Executors.newFixedThreadPool(workers, r -> {
                Thread t = new Thread(r, "direct-upload");
                t.setDaemon(true);
                return t;
            });
            List<Future<?>> futures = new ArrayList<>();
            try {
                for (int i = 0; i < workers; i++) futures.add(pool.submit(this::workerLoop));
                for (Future<?> f : futures) {
                    try {
                        f.get();
                    } catch (ExecutionException e) {
                        fail(new PublishException(PublishException.Kind.SERVER));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        fail(new PublishException(PublishException.Kind.CANCELLED));
                    }
                }
            } finally {
                stop.set(true);
                pool.shutdownNow();
            }
            synchronized (this) {
                if (failure != null) throw failure;
            }
        }

        private void workerLoop() {
            while (!stop.get()) {
                Integer n = queue.poll();
                if (n == null) return;
                try {
                    sendPart(n);
                } catch (PublishException e) {
                    fail(e);
                    return;
                }
            }
        }

        private synchronized void fail(PublishException e) {
            if (failure == null) failure = e;
            stop.set(true);
        }

        private boolean mustStop() {
            return stop.get() || cancelled.getAsBoolean();
        }

        private void sendPart(int n) throws PublishException {
            long offset = UploadPlan.partOffset(partSize, n);
            long length = UploadPlan.partLength(size, partSize, n);
            boolean fresh = false;
            IOException last = null;
            for (int attempt = 1; attempt <= UploadPlan.MAX_TRIES; attempt++) {
                if (mustStop()) throw new PublishException(PublishException.Kind.CANCELLED);
                String url = urlFor(n, fresh);
                fresh = false;
                AtomicLong thisTry = new AtomicLong();
                try {
                    String etag = put(url, offset, length, thisTry);
                    record(n, etag);
                    return;
                } catch (FilePartBody.StoppedException e) {
                    throw new PublishException(PublishException.Kind.CANCELLED);
                } catch (FilePartBody.ShortFileException e) {
                    throw new PublishException(PublishException.Kind.FILE);
                } catch (PartHttpException e) {
                    last = e;
                    if (e.code == 403) fresh = true; // expired URL (or a length it refused)
                } catch (IOException e) {
                    last = e;
                }
                sent.addAndGet(-thisTry.get()); // this try's bytes are not in R2
                if (attempt < UploadPlan.MAX_TRIES && !fresh) {
                    pause(UploadPlan.backoffMillis(attempt), this::mustStop);
                }
            }
            if (mustStop() && cancelled.getAsBoolean()) {
                throw new PublishException(PublishException.Kind.CANCELLED);
            }
            // Out of tries: a dropped connection is a network problem, anything else the server's.
            boolean httpAnswer = last instanceof PartHttpException;
            throw new PublishException(httpAnswer
                    ? PublishException.Kind.SERVER : PublishException.Kind.NETWORK);
        }

        /** PUTs one range; returns the ETag exactly as R2 sent it. */
        private String put(String url, long offset, long length, AtomicLong thisTry) throws IOException {
            FilePartBody body = new FilePartBody(file, offset, length, bytes -> {
                thisTry.addAndGet(bytes);
                sent.addAndGet(bytes);
                report();
            }, this::mustStop);
            Request request;
            try {
                request = new Request.Builder().url(url).put(body).build();
            } catch (IllegalArgumentException e) {
                throw new IOException("bad part url");
            }
            try (Response r = r2.newCall(request).execute()) {
                if (!r.isSuccessful()) throw new PartHttpException(r.code());
                String etag = r.header("ETag");
                if (etag == null || etag.isEmpty()) {
                    // An answer from R2, not a dropped connection: if it persists it is the
                    // server's problem (SERVER), not "offline" (NETWORK), so it cannot loop silently.
                    throw new PartHttpException(r.code(), "no ETag in the part response");
                }
                return etag;
            }
        }

        /** Persists the finished part before anything else happens to it. */
        private void record(int n, String etag) {
            synchronized (finished) {
                finished.put(n, etag);
                sessions.setParts(jobId, UploadPlan.partsToJson(finished));
            }
        }

        /**
         * A URL for part n, signing as needed: this part plus the next waiting ones, a few per
         * call. A URL is never reused past most of its life, and {@code fresh} (a 403) signs again.
         */
        private String urlFor(int n, boolean fresh) throws PublishException {
            synchronized (urlLock) {
                long now = clockMs.getAsLong();
                if (!fresh && usable(n, now)) return urls.get(n);
                List<Integer> batch = fresh
                        ? Collections.singletonList(n)
                        : UploadPlan.nextBatch(n, queue, m -> usable(m, now), UploadPlan.URL_BATCH);
                PartUrls signed = api.signParts(uploadId, batch, workspaceId);
                long signedAt = clockMs.getAsLong();
                for (Map.Entry<Integer, String> e : signed.urls.entrySet()) {
                    urls.put(e.getKey(), e.getValue());
                    urlStamp.put(e.getKey(), new long[]{signedAt, signed.expiresInSeconds});
                }
                return signed.urls.get(n);
            }
        }

        private boolean usable(int n, long now) {
            long[] stamp = urlStamp.get(n);
            return stamp != null && urls.containsKey(n)
                    && UploadPlan.urlUsable(stamp[0], now, (int) stamp[1]);
        }

        private void report() {
            if (progress == null) return;
            synchronized (progressLock) {
                long s = Math.min(sent.get(), size);
                if (throttle.accept(s)) progress.onProgress(s, size);
            }
        }
    }
}
