package com.jovoc.facecampresentationrecorder.edit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BitratePlannerTest {

    private static final long MIN = 60_000;

    @Test
    public void shortClipGetsOnePointFiveTimesTheSource() {
        assertEquals(12_000_000, BitratePlanner.videoBitrate(8_000_000, 5 * MIN, 128_000));
    }

    @Test
    public void unknownSourceAssumesTheRecordingBitrate() {
        assertEquals(12_000_000, BitratePlanner.videoBitrate(0, MIN, 0));
    }

    @Test
    public void longClipIsLoweredToStayUnderTheCap() {
        long durationMs = 90 * MIN;
        int bps = BitratePlanner.videoBitrate(8_000_000, durationMs, 128_000);
        assertTrue(bps < 12_000_000);
        assertTrue(bps >= BitratePlanner.MIN_VIDEO_BPS);
        long bytes = (bps + 128_000L) / 8 * (durationMs / 1000);
        assertTrue(bytes < BitratePlanner.UPLOAD_MAX_BYTES);
    }

    @Test
    public void tooLongToFitIsRefused() {
        assertEquals(0, BitratePlanner.videoBitrate(8_000_000, 24 * 60 * MIN, 128_000));
    }

    @Test
    public void bitrateIsCapped() {
        assertEquals(40_000_000, BitratePlanner.videoBitrate(100_000_000, MIN, 0));
    }
}
