package com.jovoc.facecampresentationrecorder.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class RecordSettingsTest {

    @Test
    public void nothingTickedMeansNoCrops() {
        assertTrue(RecordSettings.ratios(false, false, false).isEmpty());
    }

    @Test
    public void ratiosComeNineSixteenFirst() {
        List<int[]> ratios = RecordSettings.ratios(true, true, true);

        assertEquals(3, ratios.size());
        assertArrayEquals(new int[]{9, 16}, ratios.get(0));
        assertArrayEquals(new int[]{4, 5}, ratios.get(1));
        assertArrayEquals(new int[]{1, 1}, ratios.get(2));
    }

    @Test
    public void aSubsetKeepsTheOrder() {
        List<int[]> ratios = RecordSettings.ratios(true, false, true);

        assertEquals(2, ratios.size());
        assertArrayEquals(new int[]{9, 16}, ratios.get(0));
        assertArrayEquals(new int[]{1, 1}, ratios.get(1));
    }
}
