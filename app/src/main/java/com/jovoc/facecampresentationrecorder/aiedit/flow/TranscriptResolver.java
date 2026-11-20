package com.jovoc.facecampresentationrecorder.aiedit.flow;

import com.jovoc.facecampresentationrecorder.publish.AiCaptionStatus;
import com.jovoc.facecampresentationrecorder.publish.PublishException;

/**
 * Decides where an AI edit gets its words so a recording is never transcribed (and charged) twice.
 *
 * <p>In order: wait for a transcription already running for the recording; use the caption id the recording
 * already has (fetching its words from the server when they are not on the phone); stop when the earlier run
 * found no speech. Audio is sent only when the recording has no caption at all, or the server lost it.
 *
 * <p>Privacy: never logs words; the words pass through {@link Env#keepWords} untouched.
 */
final class TranscriptResolver {

    /** How often a running transcription or a caption's status is looked at. */
    static final long POLL_MS = 3_000L;
    /** The longest a saved caption is waited for to become READY. */
    static final long CAPTION_WAIT_MS = 180_000L;

    /** What the resolver needs from the phone and the server; faked in tests. */
    interface Env {
        /** True while a transcription of this recording is running. */
        boolean isTranscribing();

        /** The caption id saved for this recording, or null. */
        String savedCaptionId();

        /** True when the words of the recording are already on the phone. */
        boolean wordsCached();

        /** The reason the last transcription of this recording failed ("no_speech", ...), or null. */
        String lastFailure();

        AiCaptionStatus captionStatus(String captionId) throws PublishException;

        /** Keeps the words of a READY caption on the phone. */
        void keepWords(String subtitlesJson);

        /** Forgets the saved caption id (it is no use any more). */
        void forgetCaptionId();

        /** Tells the screen the speech is being turned into text. */
        void showTranscribing();

        long now();

        /** Sleeps; throws {@link AiEditException#CANCELLED} when the job was cancelled. */
        void sleep(long millis) throws AiEditException;
    }

    private TranscriptResolver() {}

    /**
     * Sets {@code job.aiCaptionId} when the recording already has a caption; leaves it null (so the audio is
     * sent) only when there is none to use. Does nothing when the job's audio is already chosen.
     */
    static void resolve(AiEditJob job, Env env) throws AiEditException {
        if (job.forceAudio || job.audioUploadId != null) return;

        if (env.isTranscribing()) {
            env.showTranscribing();
            // The transcriber ends itself (it gives up after about three minutes), so this wait is bounded.
            while (env.isTranscribing()) env.sleep(POLL_MS);
        }

        String id = job.aiCaptionId != null ? job.aiCaptionId : env.savedCaptionId();
        if (id == null) {
            if ("no_speech".equals(env.lastFailure())) throw new AiEditException(AiEditException.NO_SPEECH, false);
            return;
        }
        job.aiCaptionId = id;
        if (env.wordsCached()) return;

        env.showTranscribing();
        long deadline = env.now() + CAPTION_WAIT_MS;
        while (true) {
            AiCaptionStatus status = null;
            try {
                status = env.captionStatus(id);
            } catch (PublishException e) {
                if (e.kind == PublishException.Kind.REJECTED) {
                    // The server does not know the caption: the only case where the audio is sent.
                    gone(job, env);
                    return;
                }
                if (!e.isRetryable()) throw AiEditException.from(e);
                // A passing network or server trouble: look again until the deadline.
            }
            if (status != null) {
                if (AiCaptionStatus.READY.equals(status.status)) {
                    if (status.subtitlesJson != null && !status.subtitlesJson.trim().isEmpty()) {
                        env.keepWords(status.subtitlesJson);
                    }
                    return;
                }
                if (AiCaptionStatus.NO_SPEECH.equals(status.status)) {
                    env.forgetCaptionId();
                    job.aiCaptionId = null;
                    throw new AiEditException(AiEditException.NO_SPEECH, false);
                }
                if (AiCaptionStatus.FAILED.equals(status.status)) {
                    gone(job, env);
                    return;
                }
            }
            // Still being made, or not reachable: never send the audio now, that would be a second transcription.
            if (env.now() >= deadline) throw new AiEditException(AiEditException.TIMEOUT, true);
            env.sleep(POLL_MS);
        }
    }

    private static void gone(AiEditJob job, Env env) {
        env.forgetCaptionId();
        job.aiCaptionId = null;
    }
}
