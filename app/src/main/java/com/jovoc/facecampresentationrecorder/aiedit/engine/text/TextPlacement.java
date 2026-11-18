package com.jovoc.facecampresentationrecorder.aiedit.engine.text;

/**
 * Where a text overlay sits: its zone centre, pulled inside the output's safe rectangle (which ends above the
 * caption band) and moved off the facecam. Pure maths, all numbers in output pixels.
 */
public final class TextPlacement {

    /** Where the facecam circle is at an output time. */
    public interface Keepout {
        /**
         * Writes {@code cx, cy, r} (output pixels) of the facecam at {@code outputMs} into {@code out}.
         *
         * @return false when there is no facecam
         */
        boolean circleAt(long outputMs, float[] out);
    }

    /** Moves the search takes, in steps of this share of the output height / width. */
    private static final float STEP = 0.02f;
    private static final int SAMPLES = 5;
    /** Clear air kept around the facecam circle, a share of the output width. */
    private static final float GAP = 0.01f;

    private TextPlacement() {}

    /**
     * Finds the centre for a {@code halfW} × {@code halfH} box wanted at ({@code wantX}, {@code wantY}), shown from
     * {@code startMs} to {@code endMs}.
     *
     * @param safe   left, top, right, bottom of the safe rectangle
     * @param out    receives the centre {@code x, y}
     * @return false when the box fits nowhere clear of the facecam, so the overlay must not be drawn
     */
    public static boolean place(float wantX, float wantY, float halfW, float halfH, float[] safe, float outW,
                                float outH, Keepout facecam, long startMs, long endMs, float[] out) {
        float[][] circles = circles(facecam, startMs, endMs);
        float gap = GAP * outW;
        float x = clampAxis(wantX, halfW, safe[0], safe[2]);
        float y = clampAxis(wantY, halfH, safe[1], safe[3]);
        if (clear(x, y, halfW, halfH, circles, gap)) return set(out, x, y);

        // Up and down first (a zone is a height), nearest to the wanted spot, then sideways
        float stepY = STEP * outH;
        float maxY = safe[3] - safe[1];
        for (float d = stepY; d <= maxY; d += stepY) {
            for (int sign = -1; sign <= 1; sign += 2) {
                float cy = clampAxis(y + sign * d, halfH, safe[1], safe[3]);
                if (clear(x, cy, halfW, halfH, circles, gap)) return set(out, x, cy);
            }
        }
        float stepX = STEP * outW;
        float maxX = safe[2] - safe[0];
        for (float d = stepX; d <= maxX; d += stepX) {
            for (int sign = -1; sign <= 1; sign += 2) {
                float cx = clampAxis(x + sign * d, halfW, safe[0], safe[2]);
                if (clear(cx, y, halfW, halfH, circles, gap)) return set(out, cx, y);
            }
        }
        return false;
    }

    /** The facecam circles seen while the overlay is on screen (the track is a step function, so sample it). */
    private static float[][] circles(Keepout facecam, long startMs, long endMs) {
        if (facecam == null) return new float[0][];
        float[][] found = new float[SAMPLES][];
        int n = 0;
        for (int i = 0; i < SAMPLES; i++) {
            long t = startMs + (endMs - startMs) * i / (SAMPLES - 1);
            float[] c = new float[3];
            if (facecam.circleAt(Math.min(t, Math.max(startMs, endMs - 1)), c)) found[n++] = c;
        }
        float[][] out = new float[n][];
        System.arraycopy(found, 0, out, 0, n);
        return out;
    }

    /** A centre that keeps the box inside {@code [lo, hi]}; a box wider than the range is centred in it. */
    static float clampAxis(float c, float half, float lo, float hi) {
        if (half * 2 >= hi - lo) return (lo + hi) / 2;
        return Math.max(lo + half, Math.min(hi - half, c));
    }

    /** Whether the box misses every circle by at least {@code gap}. */
    static boolean clear(float cx, float cy, float halfW, float halfH, float[][] circles, float gap) {
        for (float[] c : circles) {
            float nx = Math.max(cx - halfW, Math.min(c[0], cx + halfW));
            float ny = Math.max(cy - halfH, Math.min(c[1], cy + halfH));
            float dx = c[0] - nx;
            float dy = c[1] - ny;
            float reach = c[2] + gap;
            if (dx * dx + dy * dy < reach * reach) return false;
        }
        return true;
    }

    private static boolean set(float[] out, float x, float y) {
        out[0] = x;
        out[1] = y;
        return true;
    }
}
