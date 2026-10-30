package com.jovoc.facecampresentationrecorder.publish;

import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;

import com.jovoc.facecampresentationrecorder.auth.AuthRepository;
import com.jovoc.facecampresentationrecorder.auth.SessionManager;
import com.jovoc.facecampresentationrecorder.db.AppDatabase;
import com.jovoc.facecampresentationrecorder.db.AudioSessionStore;
import com.jovoc.facecampresentationrecorder.db.PublishJob;
import com.jovoc.facecampresentationrecorder.entitlements.Feature;
import com.jovoc.facecampresentationrecorder.entitlements.FeatureSnapshot;
import com.jovoc.facecampresentationrecorder.subtitle.SubtitleCache;
import com.jovoc.facecampresentationrecorder.util.RecordSettings;

import java.io.File;
import java.util.UUID;

/**
 * Transcribes a freshly recorded video in the background without a publish job or a DB row.
 * Used when the user has subtitles enabled: the captions are saved to
 * the subtitle cache and a broadcast signals listeners (e.g. VideoPlayerDialog, VideoEditorActivity).
 *
 * <p>Call {@link #start} right after recording stops. It returns immediately; all work
 * (audio extraction, upload, polling) happens on a daemon background thread.
 * Every failure is swallowed silently so user / app flow is never blocked.
 *
 * <p>Privacy note: This class never logs transcript or subtitle words.
 */
public final class PostRecordTranscriber {

    private static final String TAG = "PostRecordTranscriber";

    public static final String ACTION_SUBTITLES_READY =
            "com.jovoc.facecampresentationrecorder.SUBTITLES_READY";
    public static final String EXTRA_VIDEO_PATH = "video_path";

    private static final java.util.Set<String> activeTranscriptions = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    public static boolean isTranscribing(String videoPath) {
        if (videoPath == null) return false;
        if (activeTranscriptions.contains(videoPath)) return true;
        String base = SubtitleCache.stripCropSuffix(new File(videoPath).getName().replace(".mp4", ""));
        for (String active : activeTranscriptions) {
            String activeBase = SubtitleCache.stripCropSuffix(new File(active).getName().replace(".mp4", ""));
            if (base.equals(activeBase)) return true;
        }
        return false;
    }

    private PostRecordTranscriber() {}

    /**
     * Starts a background transcription of {@code video} if all guards pass:
     * - Subtitles enabled in settings
     * - User has an active session
     * - AI captions feature is allowed
     *
     * <p>Returns immediately; transcription happens on a background worker thread.
     */
    public static void start(Context context, File video) {
        try {
            if (context == null || video == null || !video.isFile()) return;

            // Guard 1: subtitles must be enabled
            if (!RecordSettings.subtitlesEnabled(context)) {
                Log.d(TAG, "Subtitles disabled in settings, skipping post-recording transcription");
                return;
            }
            // Guard 2: user must be signed in
            if (!SessionManager.get(context).hasSession()) {
                Log.d(TAG, "No active session, skipping post-recording transcription");
                return;
            }
            // Guard 3: AI captions feature must be allowed
            if (!FeatureSnapshot.allows(Feature.AI_CAPTIONS)) {
                Log.d(TAG, "AI_CAPTIONS feature not allowed, skipping post-recording transcription");
                return;
            }

            final Context appContext = context.getApplicationContext();
            Thread t = new Thread(() -> run(appContext, video), "post-record-transcriber");
            t.setDaemon(false);
            t.start();
        } catch (Exception e) {
            Log.w(TAG, "Failed to start post-record transcriber thread", e);
        }
    }

    private static void run(Context context, File video) {
        if (context == null || video == null || !video.isFile()) return;

        final String videoPath = video.getAbsolutePath();
        activeTranscriptions.add(videoPath);

        // Deterministic job id derived from video path hash, no DB row needed
        final long VIDEO_ONLY_JOB_ID = Math.abs((long) video.getAbsolutePath().hashCode());
        File wav = null;
        DirectUploader direct = null;
        AudioSessionStore audioSessions = null;
        PowerManager.WakeLock wakeLock = null;

        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FaceCam:transcribe");
                wakeLock.acquire(180_000L);
            }

            // Step 1: extract speech WAV
            wav = AudioExtractor.extract(context.getCacheDir(), VIDEO_ONLY_JOB_ID, video);
            if (wav == null || !wav.isFile()) return;

            // Step 2: workspace id
            String workspaceId = SessionManager.get(context).repository().store().workspaceId();

            // Step 3: DB + audio session store
            AppDatabase db = AppDatabase.getInstance(context);
            audioSessions = new AudioSessionStore(db.audioUploadSessionDao());

            // Step 4: auth repository
            AuthRepository repo = SessionManager.get(context).repository();

            // Step 5: direct upload
            DirectUploadApi directApi = new DirectUploadApi(repo);
            direct = new DirectUploader(directApi);

            // Step 6: upload the WAV
            CompletedAudio completedAudio = direct.uploadAudio(
                    VIDEO_ONLY_JOB_ID, wav, workspaceId, audioSessions, null, () -> false);
            if (completedAudio == null || completedAudio.id == null || completedAudio.id.isEmpty()) {
                return;
            }
            String audioId = completedAudio.id;

            // Cleanup: WAV was uploaded successfully to R2
            AudioExtractor.delete(AudioExtractor.outputFor(context.getCacheDir(), VIDEO_ONLY_JOB_ID));
            if (wav != null) {
                AudioExtractor.delete(wav);
                wav = null;
            }

            // Step 7: publish API
            PublishApi api = new PublishApi(repo);

            // Step 8: synthetic publish job (MODE_AUTO builds title from filename without needing channels)
            PublishJob job = new PublishJob();
            job.id = VIDEO_ONLY_JOB_ID;
            job.filePath = video.getAbsolutePath();
            job.fileName = video.getName();
            job.workspaceId = workspaceId;
            job.mode = PublishJob.MODE_AUTO;
            job.channelsJson = "[]";
            job.caption = "";
            job.aiCaptionKey = UUID.randomUUID().toString();

            // Step 9: create AI caption
            AiCaptionCreated created = api.createCaption(job, audioId);

            // Forget the audio upload session now that caption has been created
            try {
                direct.finish(VIDEO_ONLY_JOB_ID, audioSessions);
            } catch (Exception ignored) {
            }

            // Step 10: skipped due to insufficient credits or invalid id
            if (created == null || created.skippedCredits || created.id == null || created.id.isEmpty()) {
                return;
            }

            // Step 11: poll up to 180 seconds (3 minutes) every 3 seconds
            long pollStart = System.currentTimeMillis();
            long pollDeadlineMs = pollStart + 180_000L;
            while (System.currentTimeMillis() < pollDeadlineMs) {
                try {
                    Thread.sleep(3_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                try {
                    AiCaptionStatus status = api.captionStatus(created.id, workspaceId);
                    if (status != null) {
                        if (AiCaptionStatus.READY.equals(status.status)) {
                            if (status.subtitlesJson != null && !status.subtitlesJson.trim().isEmpty()) {
                                SubtitleCache.saveForVideo(context.getCacheDir(), video, status.subtitlesJson);
                                Intent i = new Intent(ACTION_SUBTITLES_READY);
                                i.setPackage(context.getPackageName());
                                i.putExtra(EXTRA_VIDEO_PATH, video.getAbsolutePath());
                                context.sendBroadcast(i);
                            }
                            return;
                        }
                        if (AiCaptionStatus.NO_SPEECH.equals(status.status)
                                || AiCaptionStatus.FAILED.equals(status.status)) {
                            return;
                        }
                    }
                } catch (PublishException pe) {
                    if (pe.kind == PublishException.Kind.SIGNED_OUT
                            || pe.kind == PublishException.Kind.NEEDS_PLAN) {
                        return;
                    }
                    // Transient errors (NETWORK, SERVER, RATE_LIMITED): keep polling until deadline
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Post-recording transcription failed", e);
        } finally {
            if (wakeLock != null && wakeLock.isHeld()) {
                try {
                    wakeLock.release();
                } catch (Exception ignored) {
                }
            }
            activeTranscriptions.remove(videoPath);
            AudioExtractor.delete(AudioExtractor.outputFor(context.getCacheDir(), VIDEO_ONLY_JOB_ID));
            if (wav != null) {
                AudioExtractor.delete(wav);
            }
            if (direct != null && audioSessions != null) {
                try {
                    direct.finish(VIDEO_ONLY_JOB_ID, audioSessions);
                } catch (Exception ignored) {
                }
            }
        }
    }
}
