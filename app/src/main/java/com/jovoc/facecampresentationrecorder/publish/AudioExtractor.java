package com.jovoc.facecampresentationrecorder.publish;

import android.util.Log;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.File;
import java.util.function.BooleanSupplier;

/**
 * Pulls the speech of a recording out as a small mono 16 kHz WAV (about 1.9 MB a minute, capped at
 * 60 minutes) for AI captions. Blocking (FFmpegKit sync call, like {@code VideoCropHelper}): call
 * off the main thread. The WAV lives in the app cache dir and is deleted by {@link #delete} once
 * it was uploaded or the job is cancelled. Never logs anything about the audio itself.
 *
 * <p>An auto-publish job can have it made early: {@link #prepare} runs while the undo window is
 * still open (no network, nothing charged), and {@link #extract} then finds the finished file and
 * returns at once. The file is written under a temporary name and renamed when whole, so a reader
 * never sees a half-written WAV, and one extraction runs at a time.
 */
public final class AudioExtractor {

    private static final String TAG = "AudioExtractor";
    /** Subfolder of the cache dir; a stale file here is only ever a leftover of a killed run. */
    static final String DIR = "ai_audio";
    /** Longest audio taken, seconds. */
    static final int MAX_SECONDS = 3600;
    /** A file older than this is a leftover (killed run, cancel that raced an extraction). */
    static final long STALE_MS = 24L * 60 * 60 * 1000;

    private static final Object LOCK = new Object();

    private AudioExtractor() {
    }

    /** What really reads the video and runs ffmpeg; replaced in tests. */
    interface Engine {
        /** Length of the audio track in seconds; negative when there is none or it cannot be read. */
        long audioSeconds(File video);

        /** Writes the 16 kHz mono WAV of {@code in} to {@code out}; true when it succeeded. */
        boolean run(File in, File out);
    }

    private static final Engine REAL = new Engine() {
        @Override
        public long audioSeconds(File video) {
            return AudioDuration.seconds(video);
        }

        @Override
        public boolean run(File in, File out) {
            try {
                FFmpegSession session = FFmpegKit.executeWithArguments(command(in, out));
                if (ReturnCode.isSuccess(session.getReturnCode())) return true;
                Log.w(TAG, "ffmpeg failed, code " + session.getReturnCode());
            } catch (RuntimeException e) {
                Log.w(TAG, "ffmpeg threw", e);
            }
            return false;
        }
    };

    /** The file {@link #extract} writes for a job. */
    public static File outputFor(File cacheDir, long jobId) {
        return new File(new File(cacheDir, DIR), "job-" + jobId + ".wav");
    }

    /** Where the WAV is written until it is whole. */
    static File partFor(File cacheDir, long jobId) {
        return new File(new File(cacheDir, DIR), "job-" + jobId + ".part.wav");
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
     * Extracts the audio track of {@code video} (the exact file that is uploaded), or returns the
     * WAV that {@link #prepare} already made for the job.
     *
     * @return the WAV, or null when the file has no audio track (a recording made with audio off), is
     *         unreadable, or ffmpeg failed; the caller then publishes without AI captions
     */
    public static File extract(File cacheDir, long jobId, File video) {
        return extract(REAL, cacheDir, jobId, video, System.currentTimeMillis());
    }

    /**
     * Makes the WAV ahead of the upload, during the undo window. If the job is no longer wanted by the
     * time it is done ({@code stillWanted} false: undone, cancelled), the file is deleted again.
     */
    public static void prepare(File cacheDir, long jobId, File video, BooleanSupplier stillWanted) {
        prepare(REAL, cacheDir, jobId, video, stillWanted, System.currentTimeMillis());
    }

    static void prepare(Engine engine, File cacheDir, long jobId, File video,
                        BooleanSupplier stillWanted, long now) {
        extract(engine, cacheDir, jobId, video, now);
        if (!stillWanted.getAsBoolean()) delete(outputFor(cacheDir, jobId));
    }

    static File extract(Engine engine, File cacheDir, long jobId, File video, long now) {
        if (video == null || !video.isFile()) return null;
        File out = outputFor(cacheDir, jobId);
        File part = partFor(cacheDir, jobId);
        File dir = out.getParentFile();
        synchronized (LOCK) {
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return null;
            purgeStale(dir, now);
            if (out.isFile() && out.length() > 44) return out; // made ahead by prepare()
            if (engine.audioSeconds(video) < 0) return null; // no audio track
            delete(part);
            if (engine.run(video, part) && part.isFile() && part.length() > 44 && part.renameTo(out)) {
                return out;
            }
            delete(part);
            delete(out);
            return null;
        }
    }

    /** Deletes what a killed run or a cancel that raced an extraction left in {@code dir}. */
    static void purgeStale(File dir, long now) {
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isFile() && now - f.lastModified() > STALE_MS) delete(f);
        }
    }

    /** Removes the WAV (and nothing else); safe when it is null or already gone. */
    public static void delete(File wav) {
        // Not logged (plain JVM tests run this too); a leftover is purged after a day.
        if (wav != null && wav.exists()) wav.delete();
    }
}
