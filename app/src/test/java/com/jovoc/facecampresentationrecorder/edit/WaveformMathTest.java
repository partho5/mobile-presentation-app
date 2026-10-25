package com.jovoc.facecampresentationrecorder.edit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class WaveformMathTest {

    @Test
    public void remapSkipsCutParts() {
        // 4 slices of 1s: loudness 0.1, 0.2, 0.3, 0.4
        float[] peaks = {0.1f, 0.2f, 0.3f, 0.4f};
        EditProject p = EditProject.wholeFile("/m.mp4").withDeleted(1000, 2000, 4000);
        float[] cols = WaveformMath.remapToOutput(peaks, p, 4000, 3);
        assertArrayEquals(new float[]{0.1f, 0.3f, 0.4f}, cols, 0f);
    }

    @Test
    public void remapWithoutPeaksIsSilent() {
        float[] cols = WaveformMath.remapToOutput(null, EditProject.wholeFile("/m.mp4"), 4000, 5);
        assertEquals(5, cols.length);
        assertEquals(0f, cols[2], 0f);
    }

    @Test
    public void sliceTakesLoudestAndHonoursTrim() {
        float[] peaks = {0.1f, 0.9f, 0.2f, 0.3f};
        // whole file, 2 columns: [0,2s) holds 0.1 and 0.9
        assertArrayEquals(new float[]{0.9f, 0.3f}, WaveformMath.slice(peaks, 4000, 0, 4000, 2), 0f);
        // only the last second
        assertArrayEquals(new float[]{0.3f}, WaveformMath.slice(peaks, 4000, 3000, 1000, 1), 0f);
    }
}
