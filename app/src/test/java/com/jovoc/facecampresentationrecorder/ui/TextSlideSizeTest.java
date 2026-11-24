package com.jovoc.facecampresentationrecorder.ui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TextSlideSizeTest {

    @Test
    public void shortTextUsesTheLargestSize() {
        assertEquals(26f, TextSlideSize.sizeSp(0), 0.001f);
        assertEquals(26f, TextSlideSize.sizeSp(100), 0.001f);
    }

    @Test
    public void middleLengthsShrinkLinearly() {
        assertEquals(20.5f, TextSlideSize.sizeSp(150), 0.001f);
    }

    @Test
    public void longTextUsesTheSmallestSize() {
        assertEquals(15f, TextSlideSize.sizeSp(200), 0.001f);
        assertEquals(15f, TextSlideSize.sizeSp(500), 0.001f);
    }
}
