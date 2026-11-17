package com.jovoc.facecampresentationrecorder.aiedit.engine.render;

import android.media.MediaExtractor;
import android.media.MediaFormat;

import com.jovoc.facecampresentationrecorder.aiedit.engine.EngineException;

import java.io.File;
import java.io.IOException;

/** What the renderer needs to know about a source file, and the extractor helpers it shares. */
public final class MediaProbe {

    private static final int DEFAULT_FRAME_RATE = 30;

    /** Width and height are upright (after the file's rotation), as the decoder draws them. */
    public final int width;
    public final int height;
    public final long durationMs;
    public final int frameRate;
    public final boolean hasAudio;
    public final int audioRate;
    public final int audioChannels;

    private MediaProbe(int width, int height, long durationMs, int frameRate, boolean hasAudio,
                       int audioRate, int audioChannels) {
        this.width = width;
        this.height = height;
        this.durationMs = durationMs;
        this.frameRate = frameRate;
        this.hasAudio = hasAudio;
        this.audioRate = audioRate;
        this.audioChannels = audioChannels;
    }

    /** Reads the tracks of {@code file}; its length is the longer of video and audio. */
    public static MediaProbe of(File file) throws EngineException {
        if (file == null || !file.isFile()) {
            throw new EngineException(EngineException.SOURCE_UNREADABLE, "Source not found");
        }
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(file.getAbsolutePath());
            int v = findTrack(ex, "video/");
            if (v < 0) throw new EngineException(EngineException.SOURCE_UNREADABLE, "No video track");
            MediaFormat vf = ex.getTrackFormat(v);
            int w = vf.getInteger(MediaFormat.KEY_WIDTH);
            int h = vf.getInteger(MediaFormat.KEY_HEIGHT);
            int rotation = vf.containsKey(MediaFormat.KEY_ROTATION) ? vf.getInteger(MediaFormat.KEY_ROTATION) : 0;
            int fps = vf.containsKey(MediaFormat.KEY_FRAME_RATE) ? intOf(vf, MediaFormat.KEY_FRAME_RATE) : 0;
            long durUs = longOr(vf, MediaFormat.KEY_DURATION, 0);
            boolean hasAudio = false;
            int rate = 0;
            int channels = 0;
            int a = findTrack(ex, "audio/");
            if (a >= 0) {
                MediaFormat af = ex.getTrackFormat(a);
                hasAudio = true;
                durUs = Math.max(durUs, longOr(af, MediaFormat.KEY_DURATION, 0));
                rate = af.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? af.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 0;
                channels = af.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                        ? af.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 0;
            }
            if (durUs <= 0) throw new EngineException(EngineException.SOURCE_UNREADABLE, "Source has no duration");
            boolean turned = rotation % 180 != 0;
            return new MediaProbe(turned ? h : w, turned ? w : h, durUs / 1000,
                    fps > 0 ? fps : DEFAULT_FRAME_RATE, hasAudio, rate, channels);
        } catch (IOException | RuntimeException e) {
            if (e instanceof EngineException) throw (EngineException) e;
            throw new EngineException(EngineException.SOURCE_UNREADABLE, "Source cannot be read", e);
        } finally {
            ex.release();
        }
    }

    static int findTrack(MediaExtractor extractor, String mimePrefix) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(mimePrefix)) return i;
        }
        return -1;
    }

    /** A format value that may be an int or a long depending on the muxer that wrote it. */
    static long longOr(MediaFormat format, String key, long fallback) {
        if (!format.containsKey(key)) return fallback;
        try {
            return format.getLong(key);
        } catch (ClassCastException e) {
            return format.getInteger(key);
        }
    }

    /** A format value that may be an int or a float (frame rate is either). */
    private static int intOf(MediaFormat format, String key) {
        try {
            return format.getInteger(key);
        } catch (ClassCastException e) {
            return Math.round(format.getFloat(key));
        }
    }
}
