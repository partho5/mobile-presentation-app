package com.jovoc.facecampresentationrecorder.aiedit.flow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The active-word pop of {@code c_active_pop}: up and back inside {@code pop_ms}, plain before and after. */
public class ActivePopTest {

    @Test
    public void popRisesToTheScaleAndSettlesBackToOne() {
        assertEquals(1f, SubtitleCaptionPainter.popFactor(-5, 120, 1.12), 1e-6);
        assertEquals(1f, SubtitleCaptionPainter.popFactor(0, 120, 1.12), 1e-6);
        assertEquals(1.12f, SubtitleCaptionPainter.popFactor(60, 120, 1.12), 1e-3);
        assertTrue(SubtitleCaptionPainter.popFactor(100, 120, 1.12) < 1.12f);
        assertEquals(1f, SubtitleCaptionPainter.popFactor(120, 120, 1.12), 1e-6);
        assertEquals(1f, SubtitleCaptionPainter.popFactor(500, 120, 1.12), 1e-6);
    }

    @Test
    public void noPopLengthMeansNoPop() {
        assertEquals(1f, SubtitleCaptionPainter.popFactor(10, 0, 1.12), 1e-6);
    }
}
