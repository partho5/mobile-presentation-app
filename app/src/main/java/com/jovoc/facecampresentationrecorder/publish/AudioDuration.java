package com.jovoc.facecampresentationrecorder.publish;

import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.File;

/** Length of a video file's audio track, which is what AI captions are priced on. */
public final class AudioDuration {

    private AudioDuration() {
    }

    /**
     * Whole seconds (rounded) of the audio track of {@code file}; 0 when it has an audio track
     * without a stated length; -1 when it has no audio track or cannot be read. Blocking; not for
     * the main thread.
     */
    public static long seconds(File file) {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(file.getAbsolutePath());
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime == null || !mime.startsWith("audio/")) continue;
                if (!f.containsKey(MediaFormat.KEY_DURATION)) return 0L;
                return Math.round(f.getLong(MediaFormat.KEY_DURATION) / 1_000_000.0);
            }
            return -1L;
        } catch (Exception e) {
            return -1L;
        } finally {
            ex.release();
        }
    }
}
