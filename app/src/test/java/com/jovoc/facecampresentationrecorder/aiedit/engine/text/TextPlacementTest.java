package com.jovoc.facecampresentationrecorder.aiedit.engine.text;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Text placement: inside the safe rectangle, never on the facecam. */
public class TextPlacementTest {

    private static final float W = 1080;
    private static final float H = 1920;
    /** The 9:16 safe rectangle of the contract. */
    private static final float[] SAFE = {0.06f * W, 0.10f * H, 0.94f * W, 0.62f * H};

    private static TextPlacement.Keepout circle(final float cx, final float cy, final float r) {
        return (t, out) -> {
            out[0] = cx;
            out[1] = cy;
            out[2] = r;
            return true;
        };
    }

    @Test
    public void staysAtTheZoneWhenNothingIsInTheWay() {
        float[] out = new float[2];
        assertTrue(TextPlacement.place(540, 400, 200, 80, SAFE, W, H, null, 0, 1000, out));
        assertEquals(540, out[0], 1e-3f);
        assertEquals(400, out[1], 1e-3f);
    }

    @Test
    public void isPulledInsideTheSafeRectangle() {
        float[] out = new float[2];
        assertTrue(TextPlacement.place(540, 1700, 200, 80, SAFE, W, H, null, 0, 1000, out));
        assertEquals(SAFE[3] - 80, out[1], 1e-3f);
        assertTrue(TextPlacement.place(10, 100, 200, 80, SAFE, W, H, null, 0, 1000, out));
        assertEquals(SAFE[0] + 200, out[0], 1e-3f);
        assertEquals(SAFE[1] + 80, out[1], 1e-3f);
    }

    @Test
    public void movesOffTheFacecam() {
        float[] out = new float[2];
        // A facecam right on the wanted spot
        assertTrue(TextPlacement.place(540, 400, 200, 80, SAFE, W, H, circle(540, 400, 150), 0, 1000, out));
        float[][] c = {{540, 400, 150}};
        assertTrue(TextPlacement.clear(out[0], out[1], 200, 80, c, 0));
        assertTrue(out[0] - 200 >= SAFE[0] - 1e-3f && out[0] + 200 <= SAFE[2] + 1e-3f);
        assertTrue(out[1] - 80 >= SAFE[1] - 1e-3f && out[1] + 80 <= SAFE[3] + 1e-3f);
    }

    @Test
    public void refusesWhenTheFacecamCoversTheWholeSafeRectangle() {
        float[] out = new float[2];
        assertFalse(TextPlacement.place(540, 400, 200, 80, SAFE, W, H, circle(540, 600, 2000), 0, 1000, out));
    }

    @Test
    public void clearChecksTheNearestPointOfTheBox() {
        float[][] c = {{300, 300, 50}};
        assertFalse(TextPlacement.clear(300, 300, 10, 10, c, 0));
        assertFalse(TextPlacement.clear(300, 340, 10, 10, c, 0));
        assertTrue(TextPlacement.clear(300, 380, 10, 10, c, 0));
        assertFalse(TextPlacement.clear(300, 380, 10, 10, c, 25));
    }
}
