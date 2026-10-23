package com.jovoc.facecampresentationrecorder.publish;

import android.util.Log;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.File;

/**
 * Pulls the speech of a recording out as a small mono 16 kHz WAV (about 1.9 MB a minute, capped at
 * 60 minutes) for AI captions. Blocking (FFmpegKit sync call, like {@code VideoCropHelper}): call
 * off the main thread. The WAV lives in the app cache dir and is deleted by {@link #delete} once
 * it was uploaded or the job is cancelled. Never logs anything about the audio itself.
 */
public final class AudioExtractor {

    private static final String TAG = "AudioExtractor";
    /** Subfolder of the cache dir; a stale file here is only ever a leftover of a killed run. */
    static final String DIR = "ai_audio";
    /** Longest audio taken, seconds. */
    static final int MAX_SECONDS = 3600;

    private AudioExtractor() {
    }

    /** The file {@link #extract} writes for a job. */
    public static File outputFor(File cacheDir, long jobId) {
        return new File(new File(cacheDir, DIR), "job-" + jobId + ".wav");
    }

    /** FFmpeg arguments: no video, mono, 16 kHz, 16-bit PCM, at most {@link #MAX_SECONDS}. */
    static String[] command(File in, File out) {
        return new String[]{
                "-y", "-i", in.getAbsolutePath(),
                "-vn", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le",
                "-t", String.valueOf(MAX_SECONDS),
                out.getAbsolutePath()};
    }

    /**
     * Extracts the audio track of {@code video} (the exact file that is uploaded).
     *
     * @return the WAV, or null when the file has no audio track (a recording made with audio off), is
     *         unreadable, or ffmpeg failed; the caller then publishes without AI captions
     */
    public static File extract(File cacheDir, long jobId, File video) {
        if (video == null || !video.isFile()) return null;
        if (AudioDuration.seconds(video) < 0) return null; // no audio track
        File out = outputFor(cacheDir, jobId);
        File dir = out.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return null;
        try {
            FFmpegSession session = FFmpegKit.executeWithArguments(command(video, out));
            if (ReturnCode.isSuccess(session.getReturnCode()) && out.isFile() && out.length() > 44) {
                return out;
            }
            Log.w(TAG, "ffmpeg failed, code " + session.getReturnCode());
        } catch (RuntimeException e) {
            Log.w(TAG, "ffmpeg threw", e);
        }
        delete(out);
        return null;
    }

    /** Removes the WAV (and nothing else); safe when it is null or already gone. */
    public static void delete(File wav) {
        if (wav != null && wav.exists() && !wav.delete()) {
            Log.w(TAG, "could not delete the extracted audio");
        }
    }
}
