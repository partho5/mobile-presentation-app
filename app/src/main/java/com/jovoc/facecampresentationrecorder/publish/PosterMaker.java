package com.jovoc.facecampresentationrecorder.publish;

import java.io.File;
import java.util.Base64;

/**
 * Makes the poster image sent with {@code complete} (RET-A2): one frame of the file that is
 * actually uploaded, scaled down and encoded as a jpeg of about 50 KB, never above the server's
 * 512 KiB hard cap. Everything here is plain Java; the Android bits (frame grab, scaling, jpeg
 * encoding) sit behind {@link Imaging}, so the choices (which frame, which size, which quality,
 * what is too big) are unit-testable.
 *
 * <p>The poster is a bonus: every failure, of any kind, ends as {@code null} and never reaches
 * the upload.
 *
 * @param <B> the image type of the backend (a Bitmap in the app)
 */
final class PosterMaker<B> {

    /** The server refuses a poster over 512 KiB decoded (INVALID_POSTER). */
    static final int MAX_BYTES = 512 * 1024;
    /** What the poster aims at; a little above is fine, the cap above is the hard rule. */
    static final int TARGET_BYTES = 50 * 1024;
    /** Long side of the first try, in pixels. */
    static final int MAX_LONG_SIDE = 480;
    /** Smaller than this is not a picture any more: give up instead. */
    static final int MIN_LONG_SIDE = 160;
    /** Jpeg qualities tried, best first, at each size. */
    static final int[] QUALITIES = {80, 65, 50, 35};
    /** The frame is taken this far into the video (or in the middle of a shorter one). */
    static final long FRAME_AT_US = 1_000_000L;

    /** The Android side: frame grab, scale, jpeg. May throw; the maker catches everything. */
    interface Imaging<B> {
        /** Duration of the video in milliseconds, or 0 or less when unknown. */
        long durationMs(File video);

        /** The frame at (about) {@code timeUs}, or null when there is none. */
        B grab(File video, long timeUs);

        int width(B image);

        int height(B image);

        /** A copy of {@code image} at exactly {@code width} x {@code height}. */
        B scale(B image, int width, int height);

        /** The jpeg bytes of {@code image}, or null on failure. */
        byte[] jpeg(B image, int quality);

        /** Frees {@code image}; never called twice for one image. */
        void release(B image);
    }

    private final Imaging<B> imaging;

    PosterMaker(Imaging<B> imaging) {
        this.imaging = imaging;
    }

    /** The poster as plain base64 (no data: prefix, no line breaks), or null for none. */
    String base64For(File video) {
        byte[] jpeg = jpegFor(video);
        return jpeg == null ? null : Base64.getEncoder().encodeToString(jpeg);
    }

    /** The poster's jpeg bytes (at most {@link #MAX_BYTES}), or null for none. */
    byte[] jpegFor(File video) {
        try {
            if (video == null || !video.isFile() || video.length() == 0) return null;
            long timeUs = frameTimeUs(imaging.durationMs(video));
            B frame = imaging.grab(video, timeUs);
            if (frame == null && timeUs > 0) frame = imaging.grab(video, 0);
            if (frame == null) return null;
            try {
                return encode(frame);
            } finally {
                imaging.release(frame);
            }
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private byte[] encode(B frame) {
        int w = imaging.width(frame);
        int h = imaging.height(frame);
        if (w <= 0 || h <= 0) return null;
        int side = MAX_LONG_SIDE;
        while (side >= MIN_LONG_SIDE) {
            int[] size = scaledSize(w, h, side);
            boolean same = size[0] == w && size[1] == h;
            B image = same ? frame : imaging.scale(frame, size[0], size[1]);
            if (image == null) return null;
            try {
                byte[] last = null;
                for (int quality : QUALITIES) {
                    byte[] out = imaging.jpeg(image, quality);
                    if (!isJpeg(out)) continue;
                    if (out.length <= TARGET_BYTES) return out;
                    last = out;
                }
                // Nothing reached the aim at this size: the smallest is fine when under the cap.
                if (last != null && last.length <= MAX_BYTES) return last;
            } finally {
                if (!same) imaging.release(image);
            }
            side = side * 3 / 4;
        }
        return null;
    }

    /**
     * {@code width} x {@code height} scaled (never up) so the long side is at most
     * {@code maxLongSide}, keeping the aspect ratio; each side at least 1.
     */
    static int[] scaledSize(int width, int height, int maxLongSide) {
        int longSide = Math.max(width, height);
        if (longSide <= maxLongSide || maxLongSide <= 0) return new int[]{width, height};
        double scale = (double) maxLongSide / longSide;
        return new int[]{
                Math.max(1, (int) Math.round(width * scale)),
                Math.max(1, (int) Math.round(height * scale))};
    }

    /** One second in; the middle of a video shorter than two seconds; the start when unknown. */
    static long frameTimeUs(long durationMs) {
        if (durationMs <= 0) return 0;
        if (durationMs >= 2 * FRAME_AT_US / 1000) return FRAME_AT_US;
        return durationMs * 500L;
    }

    /** A jpeg by its first bytes (the server decides by the bytes too). */
    static boolean isJpeg(byte[] b) {
        return b != null && b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8
                && (b[2] & 0xFF) == 0xFF;
    }
}
