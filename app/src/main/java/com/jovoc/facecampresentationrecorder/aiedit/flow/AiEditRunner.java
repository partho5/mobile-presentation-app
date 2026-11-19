package com.jovoc.facecampresentationrecorder.aiedit.flow;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.util.Log;

import com.jovoc.facecampresentationrecorder.aiedit.capture.EventLogData;
import com.jovoc.facecampresentationrecorder.aiedit.capture.EventLogStore;
import com.jovoc.facecampresentationrecorder.aiedit.engine.Engine;
import com.jovoc.facecampresentationrecorder.aiedit.engine.EngineException;
import com.jovoc.facecampresentationrecorder.aiedit.engine.Listener;
import com.jovoc.facecampresentationrecorder.aiedit.engine.RenderJob;
import com.jovoc.facecampresentationrecorder.aiedit.engine.captions.CaptionPainter;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.Capabilities;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.ContractException;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.EngineContract;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.Plan;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.PlanParseException;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.PlanParser;
import com.jovoc.facecampresentationrecorder.aiedit.engine.render.MediaProbe;
import com.jovoc.facecampresentationrecorder.aiedit.engine.timeline.OutputTimeline;
import com.jovoc.facecampresentationrecorder.auth.AuthRepository;
import com.jovoc.facecampresentationrecorder.auth.SessionManager;
import com.jovoc.facecampresentationrecorder.db.AppDatabase;
import com.jovoc.facecampresentationrecorder.db.AudioSessionStore;
import com.jovoc.facecampresentationrecorder.db.UploadSessionStore;
import com.jovoc.facecampresentationrecorder.edit.EditGroupMath;
import com.jovoc.facecampresentationrecorder.publish.AiCaptionStatus;
import com.jovoc.facecampresentationrecorder.publish.AudioExtractor;
import com.jovoc.facecampresentationrecorder.publish.CompletedAudio;
import com.jovoc.facecampresentationrecorder.publish.DirectUploadApi;
import com.jovoc.facecampresentationrecorder.publish.DirectUploader;
import com.jovoc.facecampresentationrecorder.publish.PublishApi;
import com.jovoc.facecampresentationrecorder.publish.PublishException;
import com.jovoc.facecampresentationrecorder.subtitle.SubtitleCache;
import com.jovoc.facecampresentationrecorder.subtitle.SubtitleEmbedStore;
import com.jovoc.facecampresentationrecorder.subtitle.SubtitleStyle;
import com.jovoc.facecampresentationrecorder.subtitle.SubtitleTrack;
import com.jovoc.facecampresentationrecorder.util.VideoCropHelper;
import com.jovoc.facecampresentationrecorder.util.VideoGroupStore;

import org.json.JSONException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs one {@link AiEditJob} from where it stands to the finished files, blocking: uploads (the words' audio unless
 * the recording has a caption, and the proxy pieces for Standard and Heavy), the create, the wait for the plan, and
 * the renders. Every step saves the job first, so a killed process or a dropped network continues from the last
 * finished step and never makes a second server job or a second charge. A failure ends the call with the job saved
 * and the error reported; it never leaves a partial output (the engine writes to a temp file and renames).
 *
 * <p>Privacy: never logs words, plan texts or event log contents.
 */
final class AiEditRunner {

    private static final String TAG = "AiEditRunner";
    /** Poll the plan every 3 s for the first minute, then every 5 s. */
    static final long FAST_POLL_MS = 3_000L;
    static final long SLOW_POLL_MS = 5_000L;
    static final long FAST_POLL_WINDOW_MS = 60_000L;
    /** Give up waiting for the plan after this long (the job may still finish on the server). */
    static final long GIVE_UP_MS = 15 * 60_000L;
    private static final long SLEEP_SLICE_MS = 250L;
    /** The server takes proxy pieces of at most this long, at most {@link #MAX_PIECES} of them. */
    static final long PIECE_MAX_MS = Engine.PROXY_MAX_MS;
    static final int MAX_PIECES = 10;
    /** An event log whose length differs from the video's by more than this is not this video's. */
    private static final long LOG_LENGTH_SLACK_MS = 2_000L;

    private final Context context;
    private final AiEditStore store;
    private final AiEditListener listener;
    private final AtomicBoolean cancel;

    AiEditRunner(Context context, AiEditStore store, AiEditListener listener, AtomicBoolean cancel) {
        this.context = context.getApplicationContext();
        this.store = store;
        this.listener = listener;
        this.cancel = cancel;
    }

    /**
     * Continues {@code job} to the end. Returns when it is done or has stopped; either way the listener has been
     * told and the job saved.
     */
    void run(AiEditJob job) {
        job.clearError();
        try {
            File source = new File(job.sourcePath);
            if (!source.isFile() || source.length() == 0) {
                throw new AiEditException(AiEditException.SOURCE_UNREADABLE, false);
            }
            AuthRepository repo = SessionManager.get(context).repository();
            AiEditApi api = new AiEditApi(repo);
            if (AiEditJob.STAGE_UPLOAD.equals(job.stage)) upload(job, source, repo, api);
            if (AiEditJob.STAGE_PLAN.equals(job.stage)) awaitPlan(job, source, api);
            if (AiEditJob.STAGE_RENDER.equals(job.stage)) render(job, source, repo);
            job.stage = AiEditJob.STAGE_DONE;
            store.save(job, System.currentTimeMillis());
            listener.onDone(job.sourcePath, job.level);
        } catch (AiEditException e) {
            stop(job, e);
        } catch (CancellationException e) {
            stop(job, new AiEditException(AiEditException.CANCELLED, true, e));
        } catch (RuntimeException | JSONException e) {
            Log.w(TAG, "AI edit crashed", e);
            stop(job, new AiEditException(AiEditException.RENDER_FAILED, true, e));
        }
    }

    private void stop(AiEditJob job, AiEditException e) {
        job.error = e.code;
        job.errorRetryable = e.retryable;
        store.save(job, System.currentTimeMillis());
        // The kind and the message of the cause only (a path or a codec word, never the plan's texts)
        Throwable cause = e.getCause();
        Log.w(TAG, "AI edit stopped: " + e.code
                + (cause != null ? " (" + cause.getClass().getSimpleName() + ": " + cause.getMessage() + ")" : ""));
        listener.onFailed(job.sourcePath, job.level, e.code, e.retryable);
    }

    // ---- upload and create ----

    private void upload(AiEditJob job, File source, AuthRepository repo, AiEditApi api)
            throws AiEditException, JSONException {
        listener.onProgress(job.sourcePath, job.level, AiEditListener.Stage.UPLOADING, null, -1);
        refreshContract(api, job.workspaceId);
        EngineContract contract = loadContract();
        MediaProbe probe = probe(source);

        AppDatabase db = AppDatabase.getInstance(context);
        DirectUploader direct = new DirectUploader(new DirectUploadApi(repo));
        if (needsAudio(job) && job.audioUploadId == null) {
            uploadAudio(job, source, direct, new AudioSessionStore(db.audioUploadSessionDao()));
        }
        if (!AiEditSettings.LEVEL_BASIC.equals(job.level)) {
            uploadProxies(job, source, probe, direct, db.uploadSessionDao());
        }

        String body = AiEditRequestBuilder.build(job,
                new AiEditRequestBuilder.Video(probe.durationMs, probe.width, probe.height, probe.frameRate),
                logFor(source, probe), SubtitleStyle.fromSettings(context), contract, Capabilities.current(),
                languageOf(source));
        checkCancelled();
        AiEditStatus created;
        try {
            created = api.create(body, job.workspaceId);
        } catch (AiEditException e) {
            if (AiEditException.CAPTION_NOT_FOUND.equals(e.code)) {
                // The caption is gone from the server: the next try sends the audio instead.
                job.aiCaptionId = null;
                job.forceAudio = true;
                store.save(job, System.currentTimeMillis());
            }
            throw e;
        }
        job.editId = created.id;
        job.stage = AiEditJob.STAGE_PLAN;
        store.save(job, System.currentTimeMillis());
    }

    /** True when the words come from the speech audio: no READY caption of the recording to use. */
    private static boolean needsAudio(AiEditJob job) {
        return job.aiCaptionId == null || job.forceAudio;
    }

    private void uploadAudio(AiEditJob job, File source, DirectUploader direct, AudioSessionStore sessions)
            throws AiEditException {
        long id = uploadJobId(job);
        File wav = AudioExtractor.extract(context.getCacheDir(), id, source);
        if (wav == null || !wav.isFile()) throw new AiEditException(AiEditException.NO_AUDIO, false);
        try {
            CompletedAudio done = direct.uploadAudio(id, wav, job.workspaceId, sessions, null, cancel::get);
            job.audioUploadId = done.id;
            store.save(job, System.currentTimeMillis());
            direct.finish(id, sessions);
        } catch (PublishException e) {
            throw AiEditException.from(e);
        } finally {
            AudioExtractor.delete(wav);
        }
    }

    private void uploadProxies(AiEditJob job, File source, MediaProbe probe, DirectUploader direct,
                               UploadSessionStore sessions) throws AiEditException {
        List<long[]> pieces = pieces(probe.durationMs);
        if (pieces.size() > MAX_PIECES) throw new AiEditException(AiEditException.VIDEO_TOO_LONG, false);
        job.proxyCount = pieces.size();
        File dir = new File(context.getCacheDir(), "aiedit/proxy");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new AiEditException(AiEditException.NO_SPACE, true);
        for (int i = job.proxyUploadIds.size(); i < pieces.size(); i++) {
            checkCancelled();
            listener.onProgress(job.sourcePath, job.level, AiEditListener.Stage.UPLOADING, null,
                    i * 100 / pieces.size());
            File piece = new File(dir, "proxy-" + Math.abs(uploadJobId(job)) + "-" + i + ".mp4");
            long id = proxyJobId(job, i);
            try {
                Engine.renderProxy(source, pieces.get(i)[0], pieces.get(i)[1], piece, cancel);
                CompletedAudio done = direct.uploadEditProxy(id, piece, job.workspaceId, sessions, null, cancel::get);
                job.proxyUploadIds.add(done.id);
                store.save(job, System.currentTimeMillis());
                direct.finish(id, sessions);
            } catch (PublishException e) {
                throw AiEditException.from(e);
            } catch (EngineException e) {
                throw AiEditException.from(e);
            } catch (IOException e) {
                throw new AiEditException(AiEditException.RENDER_FAILED, true, e);
            } finally {
                piece.delete();
            }
        }
    }

    /**
     * The proxy pieces of a source {@code durationMs} long as {@code [startMs, endMs)}: the fewest pieces of at
     * most {@link #PIECE_MAX_MS}, all of the same length (the last may be a few ms shorter), in time order.
     */
    static List<long[]> pieces(long durationMs) {
        List<long[]> out = new ArrayList<>();
        long n = Math.max(1, (durationMs + PIECE_MAX_MS - 1) / PIECE_MAX_MS);
        long len = (durationMs + n - 1) / n;
        for (long start = 0; start < durationMs; start += len) {
            out.add(new long[]{start, Math.min(durationMs, start + len)});
        }
        return out;
    }

    /** The job id of the job's audio upload row: stable per recording and level, never a publish job's. */
    static long uploadJobId(AiEditJob job) {
        return ((job.sourcePath + "|" + job.level + "#aiedit").hashCode() & 0x3fffffffL) + 1;
    }

    /** The job id of proxy piece {@code i}: negative, so it can never meet a publish job's id in {@code upload_sessions}. */
    static long proxyJobId(AiEditJob job, int i) {
        return -(uploadJobId(job) * 16 + i + 1);
    }

    /** A newer contract of the server replaces the bundled copy; any failure keeps the one in use. */
    private void refreshContract(AiEditApi api, String workspaceId) {
        try {
            String json = api.contract(workspaceId);
            EngineContract.fromJson(json); // only a contract this build can read is kept
            File file = EngineContract.downloadedFile(context);
            File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return;
            writeAtomically(file, json.getBytes(StandardCharsets.UTF_8));
        } catch (AiEditException | ContractException | IOException ignored) {
            // The bundled or last downloaded contract is used.
        }
    }

    private EngineContract loadContract() throws AiEditException {
        try {
            return EngineContract.load(context);
        } catch (ContractException e) {
            throw new AiEditException(AiEditException.UNSUPPORTED_PLAN, false, e);
        }
    }

    /**
     * The recording's event log, when it describes this very file: not an edited video (its times moved with
     * the manual cuts), and the same size and length as the video.
     */
    private EventLogData logFor(File source, MediaProbe probe) {
        if (EditGroupMath.isEditedName(source.getName())) return null;
        EventLogData log = EventLogStore.load(EventLogStore.dir(context), source);
        if (log == null || log.width != probe.width || log.height != probe.height
                || Math.abs(log.durationMs - probe.durationMs) > LOG_LENGTH_SLACK_MS) {
            return null;
        }
        return log;
    }

    /** The caption's language when it is already on the phone, else null. */
    private String languageOf(File source) {
        SubtitleTrack track = SubtitleCache.loadForVideo(context.getCacheDir(), source);
        return track != null ? track.language : null;
    }

    // ---- the plan ----

    private void awaitPlan(AiEditJob job, File source, AiEditApi api) throws AiEditException {
        long start = System.currentTimeMillis();
        // Until the first answer the server has the job queued; a stage is reported only when it changes.
        AiEditListener.Stage shown = AiEditListener.Stage.QUEUED;
        listener.onProgress(job.sourcePath, job.level, shown, null, -1);
        while (true) {
            checkCancelled();
            AiEditStatus ready = null;
            try {
                AiEditStatus st = api.get(job.editId, job.workspaceId);
                AiEditListener.Stage now = AiEditStatus.stageFor(st.status, shown);
                if (now != shown) {
                    shown = now;
                    listener.onProgress(job.sourcePath, job.level, shown, null, -1);
                }
                if (AiEditStatus.READY.equals(st.status)) {
                    ready = st;
                }
                if (AiEditStatus.NO_SPEECH.equals(st.status)) {
                    // Credits are back; asking again would only be told the same.
                    throw new AiEditException(AiEditException.NO_SPEECH, false);
                }
                if (AiEditStatus.FAILED.equals(st.status)) {
                    // Credits are back and the uploads deleted: the next try makes a new job.
                    job.resetForNewServerJob();
                    store.save(job, System.currentTimeMillis());
                    throw new AiEditException(AiEditException.FAILED, true);
                }
            } catch (AiEditException e) {
                if (AiEditException.EDIT_NOT_FOUND.equals(e.code)) {
                    job.resetForNewServerJob();
                    store.save(job, System.currentTimeMillis());
                    throw e;
                }
                if (!e.retryable || AiEditException.FAILED.equals(e.code)) throw e;
                // A dropped connection or a server hiccup: keep waiting until the limit.
            }
            if (ready != null) {
                // Outside the try above: a local failure (the plan cannot be saved) must stop the job at once,
                // not be taken for a dropped connection and polled again until the limit.
                keepPlan(job, source, ready);
                return;
            }
            long waited = System.currentTimeMillis() - start;
            if (waited >= GIVE_UP_MS) throw new AiEditException(AiEditException.TIMEOUT, true);
            sleep(waited < FAST_POLL_WINDOW_MS ? FAST_POLL_MS : SLOW_POLL_MS);
        }
    }

    /** The app-private folder of the plan files (created on first use). */
    private File plansDir() {
        File dir = AiEditNames.plansDir(context.getFilesDir());
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    /** Saves the plan in the app's own folder and moves the job on to rendering. */
    private void keepPlan(AiEditJob job, File source, AiEditStatus st) throws AiEditException {
        if (job.outputBase == null) job.outputBase = AiEditNames.nextOutputBase(source, job.ratios, plansDir());
        try {
            writeAtomically(AiEditNames.planFile(plansDir(), job.outputBase), st.body.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new AiEditException(AiEditException.NO_SPACE, true, e);
        }
        job.stage = AiEditJob.STAGE_RENDER;
        store.save(job, System.currentTimeMillis());
    }

    // ---- render ----

    private void render(final AiEditJob job, final File source, AuthRepository repo) throws AiEditException {
        EngineContract contract = loadContract();
        final Plan plan;
        try {
            plan = PlanParser.parse(readText(AiEditNames.planFile(plansDir(), job.outputBase)), contract);
        } catch (PlanParseException e) {
            throw AiEditException.from(e);
        } catch (IOException e) {
            throw new AiEditException(AiEditException.SOURCE_UNREADABLE, false, e);
        }
        MediaProbe probe = probe(source);
        final CaptionPainter painter = painterFor(job, source, plan, probe, contract, repo);
        // Icons, icon clips and sounds: downloaded once by id; one that fails is simply left out of the render
        File assetsDir = new AssetCache(context, cancel).ensure(plan);

        // The soundtrack's temp file cannot sit beside the outputs in DCIM (only video files may be made there)
        File tempDir = new File(context.getCacheDir(), "aiedit_tmp");
        List<RenderJob> jobs = new ArrayList<>();
        for (String ratio : job.ratios) {
            if (job.doneRatios.contains(ratio)) continue;
            jobs.add(new RenderJob(source, plan, ratio, AiEditNames.outputFile(job.outputBase, ratio), assetsDir,
                    painter, cancel, tempDir));
        }
        try {
            new Engine(contract).render(jobs, new Listener() {
                @Override
                public void onProgress(RenderJob rendering, int percent) {
                    listener.onProgress(job.sourcePath, job.level, AiEditListener.Stage.RENDERING,
                            rendering.ratio, percent);
                }

                @Override
                public void onFinished(RenderJob rendered) {
                    job.doneRatios.add(rendered.ratio);
                    store.save(job, System.currentTimeMillis());
                    register(source, rendered.output, painter != null);
                    listener.onOutput(job.sourcePath, job.level, rendered.output);
                }
            });
        } catch (EngineException e) {
            throw AiEditException.from(e);
        } catch (IOException e) {
            throw new AiEditException(AiEditException.RENDER_FAILED, true, e);
        }
    }

    /**
     * The captions painter of the user's style over the recording's words; null when the words are not on the
     * phone (the recording was never captioned here), and then the outputs are made without captions.
     */
    private CaptionPainter painterFor(AiEditJob job, File source, Plan plan, MediaProbe probe,
                                      EngineContract contract, AuthRepository repo) {
        SubtitleTrack words = SubtitleCache.loadForVideo(context.getCacheDir(), source);
        if (words == null && job.aiCaptionId != null && !job.forceAudio) {
            words = fetchCaption(job, source, repo);
        }
        if (words == null) return null;
        return new SubtitleCaptionPainter(words, SubtitleStyle.fromSettings(context), plan,
                OutputTimeline.fromPlan(plan, probe.durationMs), contract);
    }

    /** The words of the job's caption from the server, kept in the subtitle cache; null when it has none. */
    private SubtitleTrack fetchCaption(AiEditJob job, File source, AuthRepository repo) {
        try {
            AiCaptionStatus status = new PublishApi(repo).captionStatus(job.aiCaptionId, job.workspaceId);
            if (status != null && AiCaptionStatus.READY.equals(status.status) && status.subtitlesJson != null
                    && !status.subtitlesJson.trim().isEmpty()) {
                SubtitleCache.saveForVideo(context.getCacheDir(), source, status.subtitlesJson);
                return SubtitleCache.loadForVideo(context.getCacheDir(), source);
            }
        } catch (PublishException ignored) {
            // Rendering without captions beats not rendering.
        }
        return null;
    }

    /** Puts a finished output in the recording's group and tells the list and the gallery. */
    private void register(File source, File output, boolean captioned) {
        synchronized (VideoGroupStore.lockForRegistration()) {
            String groupId = VideoGroupStore.ensureGroup(context, source.getAbsolutePath());
            VideoGroupStore.assign(context, output.getAbsolutePath(), groupId);
        }
        if (captioned) SubtitleEmbedStore.markEmbedded(context, output);
        MediaScannerConnection.scanFile(context, new String[]{output.getAbsolutePath()},
                new String[]{"video/mp4"}, null);
        VideoCropHelper.notifyStateChanged(context);
    }

    // ---- helpers ----

    private static MediaProbe probe(File source) throws AiEditException {
        try {
            return MediaProbe.of(source);
        } catch (EngineException e) {
            throw AiEditException.from(e);
        }
    }

    private void checkCancelled() throws AiEditException {
        if (cancel.get() || Thread.currentThread().isInterrupted()) {
            throw new AiEditException(AiEditException.CANCELLED, true);
        }
    }

    /** Sleeps in short slices so a cancel is seen within a quarter of a second. */
    private void sleep(long millis) throws AiEditException {
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end) {
            checkCancelled();
            try {
                Thread.sleep(Math.min(SLEEP_SLICE_MS, Math.max(1, end - System.currentTimeMillis())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AiEditException(AiEditException.CANCELLED, true, e);
            }
        }
    }

    private static String readText(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** Writes {@code bytes} to a temp file beside {@code target} and renames it into place. */
    private static void writeAtomically(File target, byte[] bytes) throws IOException {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
        }
        if (target.exists() && !target.delete()) {
            tmp.delete();
            throw new IOException("Could not replace " + target.getName());
        }
        if (!tmp.renameTo(target)) {
            tmp.delete();
            throw new IOException("Could not write " + target.getName());
        }
    }
}
