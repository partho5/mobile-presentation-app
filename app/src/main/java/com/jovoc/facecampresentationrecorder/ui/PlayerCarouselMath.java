package com.jovoc.facecampresentationrecorder.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure geometry and ordering for the video preview carousel (no Android imports, so it is
 * unit-testable). Page position {@code d}: 0 = centred, +1 = one page to the right.
 */
public final class PlayerCarouselMath {

    /** Size of a neighbouring page relative to the centred one. */
    public static final float SIDE_SCALE = 0.80f;
    /** Opacity of a page exactly one position away from the centre. */
    public static final float SIDE_ALPHA = 0.5f;

    private PlayerCarouselMath() {}

    /**
     * Card size hugging {@code aspect} (width / height) inside maxW x maxH.
     * Returns {width, height}; the width is never below {@code minW}.
     */
    public static int[] fitCard(float aspect, int maxW, int maxH, int minW) {
        int w, h;
        if (aspect < (float) maxW / maxH) {
            h = maxH;
            w = Math.round(maxH * aspect);
        } else {
            w = maxW;
            h = Math.round(maxW / aspect);
        }
        return new int[]{Math.max(w, minW), h};
    }

    /**
     * Left-to-right display order of versions given tallest first (smallest width/height
     * first): [L2] [L0] [L1] [L3] [L4...]. The original is L0 and 9:16 (the tallest crop)
     * is L1, so 9:16 is always directly to the right of the original.
     */
    public static <T> List<T> displayOrder(List<T> sortedTallestFirst) {
        int n = sortedTallestFirst.size();
        List<T> out = new ArrayList<>(n);
        if (n >= 3) out.add(sortedTallestFirst.get(2));
        for (int i = 0; i < n && i < 2; i++) out.add(sortedTallestFirst.get(i));
        for (int i = 3; i < n; i++) out.add(sortedTallestFirst.get(i));
        return out;
    }

    public static float scale(float d) {
        return lerp(1f, SIDE_SCALE, Math.min(Math.abs(d), 1f));
    }

    public static float alpha(float d) {
        float a = Math.abs(d);
        if (a <= 1f) return lerp(1f, SIDE_ALPHA, a);
        return Math.max(0f, lerp(SIDE_ALPHA, 0f, a - 1f));
    }

    /**
     * Where the page centre sits relative to the stage centre. Keeps exactly {@code gap}
     * between the centred card and its neighbour during the whole swipe.
     *
     * @param ownW          this page's card width
     * @param towardCentreW card width of the neighbour on the side facing the centre
     */
    public static float centreOffset(float d, float ownW, float towardCentreW, float gap, float stageW) {
        float a = Math.abs(d);
        if (a < 1e-6f) return 0f;
        float big = towardCentreW / 2f + gap + SIDE_SCALE * ownW / 2f;
        if (a <= 1f) return d * big;
        return Math.signum(d) * (big + (a - 1f) * stageW);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
