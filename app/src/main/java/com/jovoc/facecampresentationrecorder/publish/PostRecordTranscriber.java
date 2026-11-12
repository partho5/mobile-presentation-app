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
import com.jovoc.facecampresentationrecorder.subtitle.SubtitleEmbedStore;
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
    public static final String ACTION_SUBTITLES_FAILED =
            "com.jovoc.facecampresentationrecorder.SUBTITLES_FAILED";
    public static final String EXTRA_VIDEO_PATH = "video_path";
    public static final String EXTRA_REASON = "reason";

    public static final String REASON_NO_AUDIO = "no_audio";
    public static final String REASON_NO_SPEECH = "no_speech";
    public static final String REASON_SKIPPED_CREDITS = "skipped_credits";
    public static final String REASON_NEEDS_PLAN = "needs_plan";
    public static final String REASON_FAILED = "failed";

    private static final java.util.Set<String> activeTranscriptions = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    public static boolean isTranscribing(String videoPath) {
        if (videoPath == null) return false;
        synchronized (activeTranscriptions) {
            if (activeTranscriptions.contains(videoPath)) return true;
            String base = SubtitleCache.stripCropSuffix(new File(videoPath).getName().replace(".mp4", ""));
            for (String active : activeTranscriptions) {
                String activeBase = SubtitleCache.stripCropSuffix(new File(active).getName().replace(".mp4", ""));
                if (base.equals(activeBase)) return true;
            }
        }
        return false;
    }

    private PostRecordTranscriber() {}

    /**
     * Starts a background transcription of {@code video} if all guards pass:
     * - Subtitles enabled in settings
     * - User has an active session
     * - AI captions feature is allowed
     * - Not already cached or in flight
     *
     * <p>Returns immediately; transcription happens on a background worker thread.
     */
    public static void start(Context context, File video) {
        startInternal(context, video, true);
    }

    /**
     * Starts an on-demand transcription (e.g. user tapped "Sub" in preview) regardless of the
     * automatic post-recording subtitle setting. Still avoids duplicate work / double charging if
     * subtitles are already cached, in flight, or already have a saved {@code aiCaptionId}.
     */
    public static void startOnDemand(Context context, File video) {
        startInternal(context, video, false);
    }

    private static void startInternal(Context context, File video, boolean requireSettingEnabled) {
        try {
            if (context == null || video == null || !video.isFile()) return;

            // Guard 1: subtitles must be enabled (for automatic post-record flow)
            if (requireSettingEnabled && !RecordSettings.subtitlesEnabled(context)) {
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
            // Guard 4: do not duplicate an in-flight transcription
            if (isTranscribing(video.getAbsolutePath())) {
                Log.d(TAG, "Transcription already active for video");
                return;
            }
            // Guard 5: never re-charge or re-transcribe if subtitles are already cached
            if (SubtitleCache.loadForVideo(context.getCacheDir(), video) != null) {
                sendReady(context.getApplicationContext(), video.getAbsolutePath());
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

    private static void sendReady(Context context, String videoPath) {
        Intent i = new Intent(ACTION_SUBTITLES_READY);
        i.setPackage(context.getPackageName());
        i.putExtra(EXTRA_VIDEO_PATH, videoPath);
        context.sendBroadcast(i);
    }

    private static void sendFailed(Context context, String videoPath, String reason) {
        Intent i = new Intent(ACTION_SUBTITLES_FAILED);
        i.setPackage(context.getPackageName());
        i.putExtra(EXTRA_VIDEO_PATH, videoPath);
        i.putExtra(EXTRA_REASON, reason);
        context.sendBroadcast(i);
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
        boolean finishedReady = false;
        String failReason = REASON_FAILED;

        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FaceCam:transcribe");
                wakeLock.acquire(180_000L);
            }

            AuthRepository repo = SessionManager.get(context).repository();
            String workspaceId = repo.store().workspaceId();
            PublishApi api = new PublishApi(repo);

            // Reuse any existing aiCaptionId so we never call POST /ai-captions or charge credits twice.
            String captionId = SubtitleEmbedStore.getCaptionId(context, video);
            if (captionId == null) {
                // Step 1: extract speech WAV
                wav = AudioExtractor.extract(context.getCacheDir(), VIDEO_ONLY_JOB_ID, video);
                if (wav == null || !wav.isFile()) {
                    failReason = REASON_NO_AUDIO;
                    return;
                }

                // Step 2: DB + audio session store + direct upload
                AppDatabase db = AppDatabase.getInstance(context);
                audioSessions = new AudioSessionStore(db.audioUploadSessionDao());
                DirectUploadApi directApi = new DirectUploadApi(repo);
                direct = new DirectUploader(directApi);

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

                // Step 3: synthetic publish job (MODE_AUTO builds title from filename without needing channels)
                PublishJob job = new PublishJob();
                job.id = VIDEO_ONLY_JOB_ID;
                job.filePath = video.getAbsolutePath();
                job.fileName = video.getName();
                job.workspaceId = workspaceId;
                job.mode = PublishJob.MODE_AUTO;
                job.channelsJson = "[]";
                job.caption = "";
                job.aiCaptionKey = UUID.randomUUID().toString();

                // Step 4: create AI caption
                AiCaptionCreated created = api.createCaption(job, audioId);

                // Forget the audio upload session now that caption has been created
                try {
                    direct.finish(VIDEO_ONLY_JOB_ID, audioSessions);
                } catch (Exception ignored) {
                }

                if (created != null && created.skippedCredits) {
                    failReason = REASON_SKIPPED_CREDITS;
                    return;
                }
                if (created == null || created.id == null || created.id.isEmpty()) {
                    return;
                }
                captionId = created.id;
                // Save immediately so a crash/process-death between here and READY
                // lets the next launch resume polling this job without re-charging credits.
                SubtitleEmbedStore.saveCaptionId(context, video, captionId);
            }

            // Step 5: poll up to 180 seconds (3 minutes) every 3 seconds.
            // Sleep first: the server needs a moment after createCaption before the
            // job even appears in the status endpoint (restores the pre-249b153 delay).
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
                    AiCaptionStatus status = api.captionStatus(captionId, workspaceId);
                    if (status != null) {
                        if (AiCaptionStatus.READY.equals(status.status)) {
                            if (status.subtitlesJson != null && !status.subtitlesJson.trim().isEmpty()) {
                                // Persist only on success so the ID is never stale.
                                SubtitleEmbedStore.saveCaptionId(context, video, captionId);
                                SubtitleCache.saveForVideo(context.getCacheDir(), video, status.subtitlesJson);
                                finishedReady = true;
                                sendReady(context, video.getAbsolutePath());
                            }
                            return;
                        }
                        if (AiCaptionStatus.NO_SPEECH.equals(status.status)) {
                            SubtitleEmbedStore.clearCaptionId(context, video);
                            failReason = REASON_NO_SPEECH;
                            return;
                        }
                        if (AiCaptionStatus.FAILED.equals(status.status)) {
                            SubtitleEmbedStore.clearCaptionId(context, video);
                            failReason = REASON_FAILED;
                            return;
                        }
                    }
                } catch (PublishException pe) {
                    if (pe.kind == PublishException.Kind.SIGNED_OUT
                            || pe.kind == PublishException.Kind.NEEDS_PLAN) {
                        failReason = REASON_NEEDS_PLAN;
                        return;
                    }
                    // Transient errors (NETWORK, SERVER, RATE_LIMITED): keep polling until deadline
                } catch (Exception ignored) {
                }
            }
        } catch (PublishException pe) {
            if (pe.kind == PublishException.Kind.INSUFFICIENT_CREDITS) {
                failReason = REASON_SKIPPED_CREDITS;
            } else if (pe.kind == PublishException.Kind.NEEDS_PLAN
                    || pe.kind == PublishException.Kind.SIGNED_OUT) {
                failReason = REASON_NEEDS_PLAN;
            }
            Log.w(TAG, "Post-recording transcription failed: " + pe.kind);
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
            if (!finishedReady) {
                sendFailed(context, videoPath, failReason);
            }
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
