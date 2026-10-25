package com.jovoc.facecampresentationrecorder.edit;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TimelineZoomTest {

    private static final long DUR = 60_000;

    @Test
    public void maxZoomGivesAOneSecondWindow() {
        assertEquals(60f, TimelineZoom.maxZoom(DUR), 0.001f);
        assertEquals(1000, TimelineZoom.windowMs(DUR, TimelineZoom.maxZoom(DUR)));
    }

    @Test
    public void shortVideosDoNotOverZoom() {
        assertEquals(1f, TimelineZoom.maxZoom(800), 0f);
        assertEquals(1f, TimelineZoom.clampZoom(5f, 800), 0f);
        assertEquals(800, TimelineZoom.windowMs(800, 5f));
        assertEquals(3f, TimelineZoom.clampZoom(10f, 3000), 0.001f);
    }

    @Test
    public void clampZoomLimitsBothEnds() {
        assertEquals(1f, TimelineZoom.clampZoom(0.2f, DUR), 0f);
        assertEquals(1f, TimelineZoom.clampZoom(Float.NaN, DUR), 0f);
        assertEquals(60f, TimelineZoom.clampZoom(500f, DUR), 0.001f);
        assertEquals(4f, TimelineZoom.clampZoom(4f, DUR), 0f);
    }

    @Test
    public void windowIsDurationOverZoom() {
        assertEquals(DUR, TimelineZoom.windowMs(DUR, 1f));
        assertEquals(15_000, TimelineZoom.windowMs(DUR, 4f));
        assertEquals(0, TimelineZoom.windowMs(0, 4f));
    }

    @Test
    public void clampStartKeepsTheWindowInside() {
        assertEquals(0, TimelineZoom.clampStart(-5, 15_000, DUR));
        assertEquals(45_000, TimelineZoom.clampStart(50_000, 15_000, DUR));
        assertEquals(20_000, TimelineZoom.clampStart(20_000, 15_000, DUR));
        assertEquals(0, TimelineZoom.clampStart(7, DUR, DUR));
    }

    @Test
    public void zoomAroundKeepsTheFocusMomentInPlace() {
        // fit -> 4x, focus at 50% = 30 s; new window 15 s so start = 30 - 7.5 = 22.5 s
        assertEquals(22_500, TimelineZoom.zoomAround(0, 1f, 4f, DUR, 0.5f));
        // 4x at start 22.5 s, focus 0.2 -> 22.5 + 3 = 25.5 s; to 20x (3 s window) start = 25.5 - 0.6
        assertEquals(24_900, TimelineZoom.zoomAround(22_500, 4f, 20f, DUR, 0.2f));
    }

    @Test
    public void zoomAroundClampsAtTheEdges() {
        // focus at the very left of fit stays at 0
        assertEquals(0, TimelineZoom.zoomAround(0, 1f, 4f, DUR, 0f));
        // focus at the right edge of fit: start = 60 - 15 = 45 s
        assertEquals(45_000, TimelineZoom.zoomAround(0, 1f, 4f, DUR, 1f));
        // zooming back out to fit always starts at 0
        assertEquals(0, TimelineZoom.zoomAround(30_000, 2f, 1f, DUR, 0.5f));
    }

    @Test
    public void msAndPxMapBothWays() {
        // window 1 s from 2 s, strip 500 px wide: 2.2 s sits at 100 px
        assertEquals(2200, TimelineZoom.msAt(100f, 500f, 2000, 1000));
        assertEquals(100f, TimelineZoom.pxAt(2200, 500f, 2000, 1000), 0.001f);
        // outside the strip clamps to the window
        assertEquals(2000, TimelineZoom.msAt(-30f, 500f, 2000, 1000));
        assertEquals(3000, TimelineZoom.msAt(900f, 500f, 2000, 1000));
        assertEquals(-250f, TimelineZoom.pxAt(1500, 500f, 2000, 1000), 0.001f);
    }

    @Test
    public void followLeavesAVisiblePlayheadAlone() {
        assertEquals(10_000, TimelineZoom.followStart(10_000, 5000, 12_000, DUR));
        assertEquals(10_000, TimelineZoom.followStart(10_000, 5000, 14_500, DUR));
    }

    @Test
    public void followFlipsThePageWhenThePlayheadLeavesIt() {
        // 14.6 s is past 90% of 10..15 s: start = 14.6 - 0.5 = 14.1 s
        assertEquals(14_100, TimelineZoom.followStart(10_000, 5000, 14_600, DUR));
        // jumped before the window: 3 s -> start 2.5 s
        assertEquals(2500, TimelineZoom.followStart(10_000, 5000, 3000, DUR));
        // near the end the window stops at the end
        assertEquals(55_000, TimelineZoom.followStart(50_000, 5000, 59_900, DUR));
        // fit never scrolls
        assertEquals(0, TimelineZoom.followStart(0, DUR, 59_900, DUR));
    }

    @Test
    public void tickStepGrowsWithTheWindow() {
        // 340 px, at least 56 px apart
        assertEquals(200, TimelineZoom.tickStepMs(1000, 340f, 56f));
        assertEquals(2000, TimelineZoom.tickStepMs(10_000, 340f, 56f));
        assertEquals(10_000, TimelineZoom.tickStepMs(DUR, 340f, 56f));
        assertEquals(300_000, TimelineZoom.tickStepMs(1_800_000, 340f, 56f));
        assertEquals(3_600_000, TimelineZoom.tickStepMs(100_000_000, 340f, 56f));
    }

    @Test
    public void tickLabelsShowTenthsOnlyBelowASecond() {
        assertEquals("0:02.2", TimelineZoom.formatTick(2200, 200));
        assertEquals("0:02", TimelineZoom.formatTick(2000, 1000));
        assertEquals("1:10", TimelineZoom.formatTick(70_000, 10_000));
    }
}
