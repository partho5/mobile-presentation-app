package com.jovoc.facecampresentationrecorder.ui;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class PlayerCarouselMathTest {

    private static final float EPS = 0.001f;

    @Test
    public void displayOrderPlacesFirstCropRightAndSecondLeft() {
        assertEquals(Arrays.asList("L0"), PlayerCarouselMath.displayOrder(Arrays.asList("L0")));
        assertEquals(Arrays.asList("L0", "L1"),
                PlayerCarouselMath.displayOrder(Arrays.asList("L0", "L1")));
        assertEquals(Arrays.asList("L2", "L0", "L1"),
                PlayerCarouselMath.displayOrder(Arrays.asList("L0", "L1", "L2")));
        assertEquals(Arrays.asList("L2", "L0", "L1", "L3"),
                PlayerCarouselMath.displayOrder(Arrays.asList("L0", "L1", "L2", "L3")));
        assertEquals(Arrays.asList("L2", "L0", "L1", "L3", "L4"),
                PlayerCarouselMath.displayOrder(Arrays.asList("L0", "L1", "L2", "L3", "L4")));
    }

    @Test
    public void nineSixteenIsAlwaysRightOfTheOriginal() {
        // original 1080x2400, then 9:16, 4:5, 1:1 crops (same width, rows dropped)
        List<int[]> all = new ArrayList<>(Arrays.asList(
                new int[]{1080, 1080}, new int[]{1080, 2400}, new int[]{1080, 1350},
                new int[]{1080, 1920}));
        for (int shuffle = 0; shuffle < 4; shuffle++) {
            Collections.rotate(all, 1);
            List<int[]> sorted = new ArrayList<>(all);
            Collections.sort(sorted, Comparator.comparingDouble(v -> (double) v[0] / v[1]));
            List<int[]> shown = PlayerCarouselMath.displayOrder(sorted);
            int originalAt = -1;
            for (int i = 0; i < shown.size(); i++) if (shown.get(i)[1] == 2400) originalAt = i;
            assertEquals(1920, shown.get(originalAt + 1)[1]);
            assertEquals(1350, shown.get(originalAt - 1)[1]);
        }
    }

    @Test
    public void centreOffsetIsZeroAtCentreAndFullDistanceAtOnePosition() {
        float own = 200, toward = 300, gap = 10, stage = 900;
        float big = toward / 2f + gap + PlayerCarouselMath.SIDE_SCALE * own / 2f;
        assertEquals(0f, PlayerCarouselMath.centreOffset(0f, own, toward, gap, stage), EPS);
        assertEquals(big, PlayerCarouselMath.centreOffset(1f, own, toward, gap, stage), EPS);
        assertEquals(-big, PlayerCarouselMath.centreOffset(-1f, own, toward, gap, stage), EPS);
        assertEquals(big + stage, PlayerCarouselMath.centreOffset(2f, own, toward, gap, stage), EPS);
    }

    @Test
    public void gapBetweenNeighbouringCardsStaysConstantWhileSwiping() {
        float wCentre = 250, wNext = 180, gap = 10, stage = 900;
        for (float t = 0f; t <= 1f; t += 0.125f) {
            // swiping left by t: the centre page is at d = -t, its right neighbour at d = 1 - t
            float dA = -t, dB = 1f - t;
            float centreA = PlayerCarouselMath.centreOffset(dA, wCentre, wNext, gap, stage);
            float centreB = PlayerCarouselMath.centreOffset(dB, wNext, wCentre, gap, stage);
            float rightEdgeA = centreA + PlayerCarouselMath.scale(dA) * wCentre / 2f;
            float leftEdgeB = centreB - PlayerCarouselMath.scale(dB) * wNext / 2f;
            assertEquals("t=" + t, gap, leftEdgeB - rightEdgeA, EPS);
        }
    }

    @Test
    public void scaleAndAlphaFallOffAwayFromCentre() {
        assertEquals(1f, PlayerCarouselMath.scale(0f), EPS);
        assertEquals(PlayerCarouselMath.SIDE_SCALE, PlayerCarouselMath.scale(1f), EPS);
        assertEquals(PlayerCarouselMath.SIDE_SCALE, PlayerCarouselMath.scale(-3f), EPS);
        assertEquals(1f, PlayerCarouselMath.alpha(0f), EPS);
        assertEquals(PlayerCarouselMath.SIDE_ALPHA, PlayerCarouselMath.alpha(-1f), EPS);
        assertEquals(0f, PlayerCarouselMath.alpha(2f), EPS);
        assertEquals(0f, PlayerCarouselMath.alpha(5f), EPS);
    }

    @Test
    public void fitCardHugsTheAspectRatio() {
        int maxW = 800, maxH = 1600, minW = 140;
        // tall phone video: height-limited
        assertArrayEquals(new int[]{738, 1600},
                PlayerCarouselMath.fitCard(1080f / 2340f, maxW, maxH, minW));
        // 9:16: wider than the box ratio, so width-limited
        assertArrayEquals(new int[]{800, 1422},
                PlayerCarouselMath.fitCard(1080f / 1920f, maxW, maxH, minW));
        assertArrayEquals(new int[]{800, 800},
                PlayerCarouselMath.fitCard(1f, maxW, maxH, minW));
        assertArrayEquals(new int[]{800, 450},
                PlayerCarouselMath.fitCard(1920f / 1080f, maxW, maxH, minW));
        // never narrower than the minimum width
        assertArrayEquals(new int[]{140, 1600},
                PlayerCarouselMath.fitCard(0.05f, maxW, maxH, minW));
    }
}
