package com.jovoc.facecampresentationrecorder.util;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derives the human-readable format tag shown on each saved-recording card.
 *
 * The tag is computed from the video's real pixel dimensions rather than from
 * its filename, so it stays truthful after a rename. The "_WxH" suffix that
 * {@link VideoCropHelper} appends is used only to decide whether a file is an
 * auto-cropped variant or the untouched original.
 */
public final class VideoFormatHelper {

    /** Matches the "_9x16" style suffix VideoCropHelper appends to cropped files. */
    private static final Pattern CROP_SUFFIX =
            Pattern.compile("_(\\d{1,2})x(\\d{1,2})\\.mp4$", Pattern.CASE_INSENSITIVE);

    /**
     * Common aspect ratios, used to snap near-misses caused by encoder rounding
     * (e.g. a 1080x1918 crop that is 9:16 in intent but not exactly on the nose).
     */
    private static final int[][] KNOWN_RATIOS = {
            {9, 16}, {16, 9}, {4, 5}, {5, 4}, {1, 1},
            {3, 4}, {4, 3}, {2, 3}, {3, 2}, {21, 9}, {9, 21}
    };

    /** Largest relative deviation still treated as a match for a known ratio. */
    private static final float SNAP_TOLERANCE = 0.03f;

    private VideoFormatHelper() {}

    /** True if the filename carries the auto-crop suffix, i.e. it is a cropped variant. */
    public static boolean isCroppedVariant(String fileName) {
        return fileName != null && CROP_SUFFIX.matcher(fileName).find();
    }

    /**
     * Builds the card tag for a recording.
     *
     * @param fileName    the file's current name
     * @param videoWidth  pixel width, or <= 0 if unknown
     * @param videoHeight pixel height, or <= 0 if unknown
     * @return "Original" for uncropped files, otherwise a ratio such as "9:16".
     *         Falls back to the filename suffix, then to "Original", when the
     *         dimensions are unreadable.
     */
    public static String getFormatTag(String fileName, int videoWidth, int videoHeight) {
        boolean cropped = isCroppedVariant(fileName);

        if (!cropped) {
            return "Original";
        }

        String ratio = describeRatio(videoWidth, videoHeight);
        if (ratio != null) {
            return ratio;
        }

        // Dimensions unreadable — fall back to what the filename claims.
        Matcher m = CROP_SUFFIX.matcher(fileName);
        if (m.find()) {
            return m.group(1) + ":" + m.group(2);
        }
        return "Original";
    }

    /**
     * Converts pixel dimensions into a ratio label such as "9:16".
     *
     * Snaps to a known ratio when within {@link #SNAP_TOLERANCE}; otherwise
     * reduces the raw dimensions by their GCD so unusual ratios still render.
     *
     * @return the label, or null if the dimensions are invalid.
     */
    public static String describeRatio(int width, int height) {
        if (width <= 0 || height <= 0) return null;

        float actual = (float) width / (float) height;

        for (int[] candidate : KNOWN_RATIOS) {
            float target = (float) candidate[0] / (float) candidate[1];
            if (Math.abs(actual - target) / target <= SNAP_TOLERANCE) {
                return candidate[0] + ":" + candidate[1];
            }
        }

        // Unknown ratio — reduce to lowest terms so it is still readable.
        int divisor = gcd(width, height);
        int w = width / divisor;
        int h = height / divisor;

        // A reduction like 853:1280 helps nobody; show a decimal instead.
        if (w > 32 || h > 32) {
            return String.format(Locale.US, "%.2f:1", actual);
        }
        return w + ":" + h;
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int t = b;
            b = a % b;
            a = t;
        }
        return a == 0 ? 1 : a;
    }
}
