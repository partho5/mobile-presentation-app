package com.jovoc.facecampresentationrecorder.subtitle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Canvas;
import android.graphics.Paint;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class SubtitleDrawerTest {

    private SubtitleDrawer drawer;

    @Before
    public void setUp() {
        drawer = new SubtitleDrawer();
        drawer.setTextMeasurer(new SubtitleDrawer.SimpleTextMeasurer(0.5f, -0.8f, 0.2f));
    }

    /**
     * Spy DrawTarget recording operations for verification without Android runtime dependencies.
     */
    private static class RecordingDrawTarget implements SubtitleDrawer.DrawTarget {
        final List<String> drawnTexts = new ArrayList<>();
        final List<float[]> roundRects = new ArrayList<>();
        float lastScaleX = 1f;
        float lastScaleY = 1f;
        float lastPivotX = 0f;
        float lastPivotY = 0f;
        int saveCount = 0;
        int restoreCount = 0;

        @Override
        public void drawRoundRect(float left, float top, float right, float bottom, float rx, float ry, Paint paint) {
            roundRects.add(new float[]{left, top, right, bottom, rx, ry});
        }

        @Override
        public void drawText(String text, float x, float y, Paint paint) {
            drawnTexts.add(text);
        }

        @Override
        public void save() {
            saveCount++;
        }

        @Override
        public void scale(float sx, float sy, float px, float py) {
            lastScaleX = sx;
            lastScaleY = sy;
            lastPivotX = px;
            lastPivotY = py;
        }

        @Override
        public void restore() {
            restoreCount++;
        }
    }

    @Test
    public void testFontSizeScalingAcrossResolutions() {
        float baseFontSizeSp = 20.0f;

        // 1080p width (reference 1080px -> density 3.0)
        float size1080 = SubtitleDrawer.measureScaledFontSize(baseFontSizeSp, 1080);
        assertEquals(60.0f, size1080, 0.001f);

        // 720p width (720px -> density 2.0)
        float size720 = SubtitleDrawer.measureScaledFontSize(baseFontSizeSp, 720);
        assertEquals(40.0f, size720, 0.001f);

        // Aspect ratio proportionality: 60 / 40 == 1080 / 720 = 1.5
        assertEquals(1.5f, size1080 / size720, 0.001f);

        // 4K portrait width (2160px -> density 6.0)
        float size4k = SubtitleDrawer.measureScaledFontSize(baseFontSizeSp, 2160);
        assertEquals(120.0f, size4k, 0.001f);

        // Graceful fallback for non-positive dimensions
        assertEquals(baseFontSizeSp, SubtitleDrawer.measureScaledFontSize(baseFontSizeSp, 0), 0.001f);
        assertEquals(baseFontSizeSp, SubtitleDrawer.measureScaledFontSize(baseFontSizeSp, -100), 0.001f);
    }

    @Test
    public void testWordBoundingBoxComputationAndOffsets() {
        SubtitleWord w1 = new SubtitleWord("Hello", 0, 500);
        SubtitleWord w2 = new SubtitleWord("Amazing", 600, 1100);
        SubtitleWord w3 = new SubtitleWord("World", 1200, 1800);
        SubtitlePhrase phrase = new SubtitlePhrase(Arrays.asList(w1, w2, w3));
        SubtitleStyle style = SubtitleStyle.greenPill();

        int viewWidth = 1080;
        int viewHeight = 1920;

        SubtitleDrawer.Bounds phraseBounds = drawer.computePhraseBounds(phrase, style, viewWidth, viewHeight);
        assertNotNull(phraseBounds);

        SubtitleDrawer.Bounds word0 = drawer.computeWordBounds(phrase, 0, style, viewWidth, viewHeight);
        SubtitleDrawer.Bounds word1 = drawer.computeWordBounds(phrase, 1, style, viewWidth, viewHeight);
        SubtitleDrawer.Bounds word2 = drawer.computeWordBounds(phrase, 2, style, viewWidth, viewHeight);

        assertNotNull(word0);
        assertNotNull(word1);
        assertNotNull(word2);

        // Word 0 starts at phrase left
        assertEquals(phraseBounds.left, word0.left, 0.001f);

        // Consecutive words have space offset between them
        assertTrue(word1.left > word0.right);
        assertTrue(word2.left > word1.right);

        // Last word ends at phrase right
        assertEquals(phraseBounds.right, word2.right, 0.001f);

        // Horizontal centering: (viewWidth - phraseWidth) / 2
        float expectedLeft = (viewWidth - phraseBounds.width()) / 2.0f;
        assertEquals(expectedLeft, phraseBounds.left, 0.001f);
        assertEquals(viewWidth - expectedLeft, phraseBounds.right, 0.001f);

        // Vertical alignment: all words have same top and bottom matching phrase
        assertEquals(phraseBounds.top, word0.top, 0.001f);
        assertEquals(phraseBounds.bottom, word0.bottom, 0.001f);
        assertEquals(phraseBounds.top, word1.top, 0.001f);
        assertEquals(phraseBounds.bottom, word1.bottom, 0.001f);
    }

    @Test
    public void testActiveWordPillBoundsContainingPadding() {
        SubtitleWord w1 = new SubtitleWord("ActiveWord", 100, 500);
        SubtitlePhrase phrase = new SubtitlePhrase(Collections.singletonList(w1));
        SubtitleStyle style = SubtitleStyle.greenPill();

        int viewWidth = 1080;
        int viewHeight = 1920;

        SubtitleDrawer.Bounds wordBounds = drawer.computeWordBounds(phrase, 0, style, viewWidth, viewHeight);
        assertNotNull(wordBounds);

        SubtitleDrawer.Bounds pillBounds = new SubtitleDrawer.Bounds();
        SubtitleDrawer.computePillBounds(wordBounds, style, viewWidth, pillBounds);

        float padX = SubtitleDrawer.measureScaledDimension(style.paddingHorizontalDp, viewWidth);
        float padY = SubtitleDrawer.measureScaledDimension(style.paddingVerticalDp, viewWidth);

        // Padding applied symmetrically around active word
        assertEquals(wordBounds.left - padX, pillBounds.left, 0.001f);
        assertEquals(wordBounds.right + padX, pillBounds.right, 0.001f);
        assertEquals(wordBounds.top - padY, pillBounds.top, 0.001f);
        assertEquals(wordBounds.bottom + padY, pillBounds.bottom, 0.001f);

        // Total pill dimensions include double padding
        assertEquals(wordBounds.width() + (2 * padX), pillBounds.width(), 0.001f);
        assertEquals(wordBounds.height() + (2 * padY), pillBounds.height(), 0.001f);
    }

    @Test
    public void testScaleTransformationCentering() {
        SubtitleDrawer.Bounds box = new SubtitleDrawer.Bounds(100f, 200f, 300f, 400f);
        assertEquals(200f, SubtitleDrawer.computeWordCenterX(box), 0.001f);
        assertEquals(300f, SubtitleDrawer.computeWordCenterY(box), 0.001f);
        assertEquals(200f, box.centerX(), 0.001f);
        assertEquals(300f, box.centerY(), 0.001f);

        // Visual vertical centering test: centerY must match viewHeight * style.positionY
        SubtitleWord word = new SubtitleWord("CenterMe", 0, 500);
        SubtitlePhrase phrase = new SubtitlePhrase(Collections.singletonList(word));
        SubtitleStyle style = SubtitleStyle.greenPill().withPositionY(0.80f);

        int viewHeight = 2000;
        SubtitleDrawer.Bounds bounds = drawer.computeWordBounds(phrase, 0, style, 1000, viewHeight);
        assertNotNull(bounds);

        float expectedCenterY = viewHeight * 0.80f; // 1600f
        assertEquals(expectedCenterY, bounds.centerY(), 0.001f);
    }

    @Test
    public void testNullTrackAndOutOfBoundsPlayheadEarlyExit() {
        RecordingDrawTarget target = new RecordingDrawTarget();
        SubtitleStyle style = SubtitleStyle.greenPill();
        SubtitleWord word = new SubtitleWord("Hello", 1000, 2000);
        SubtitleTrack track = new SubtitleTrack("en", Collections.singletonList(word));

        // Null canvas / null target
        assertFalse(drawer.draw((Canvas) null, track, style, 1500L, 1080, 1920));
        assertFalse(drawer.draw((SubtitleDrawer.DrawTarget) null, track, style, 1500L, 1080, 1920));

        // Null track
        assertFalse(drawer.draw(target, null, style, 1500L, 1080, 1920));

        // Null style
        assertFalse(drawer.draw(target, track, null, 1500L, 1080, 1920));

        // Negative playhead
        assertFalse(drawer.draw(target, track, style, -1L, 1080, 1920));
        assertFalse(drawer.draw(target, track, style, -500L, 1080, 1920));

        // Invalid dimensions
        assertFalse(drawer.draw(target, track, style, 1500L, 0, 1920));
        assertFalse(drawer.draw(target, track, style, 1500L, 1080, 0));
        assertFalse(drawer.draw(target, track, style, 1500L, -100, 1920));

        // Out-of-bounds playhead (before first phrase)
        assertFalse(drawer.draw(target, track, style, 500L, 1080, 1920));

        // Out-of-bounds playhead (after last phrase)
        assertFalse(drawer.draw(target, track, style, 5000L, 1080, 1920));

        // Nothing drawn
        assertEquals(0, target.drawnTexts.size());
        assertEquals(0, target.roundRects.size());
    }

    @Test
    public void testSuccessfulDrawForMatchingPlayheadTime() {
        RecordingDrawTarget target = new RecordingDrawTarget();
        SubtitleStyle style = SubtitleStyle.greenPill();

        SubtitleWord w1 = new SubtitleWord("Hello", 100, 500);
        SubtitleWord w2 = new SubtitleWord("World", 600, 1000);
        SubtitleTrack track = new SubtitleTrack("en", Arrays.asList(w1, w2));

        int viewWidth = 1080;
        int viewHeight = 1920;

        // Playhead matching word 1 ("Hello")
        boolean rendered = drawer.draw(target, track, style, 250L, viewWidth, viewHeight);
        assertTrue(rendered);

        // Active word pill drawn exactly once
        assertEquals(1, target.roundRects.size());

        // Words rendered
        assertTrue(target.drawnTexts.contains("Hello"));
        assertTrue(target.drawnTexts.contains("World"));

        // Save and restore called for active word pop
        assertEquals(1, target.saveCount);
        assertEquals(1, target.restoreCount);

        // Scale pop applied
        assertEquals(style.scaleActiveWord, target.lastScaleX, 0.001f);
        assertEquals(style.scaleActiveWord, target.lastScaleY, 0.001f);

        // Pivot matches visual center
        assertEquals(viewHeight * style.positionY, target.lastPivotY, 0.001f);
    }

    @Test
    public void testGradientPillTemplatePremium() {
        RecordingDrawTarget target = new RecordingDrawTarget();
        SubtitleStyle style = SubtitleStyle.premium();
        assertTrue(style.isGradient());

        SubtitleWord w = new SubtitleWord("Premium", 0, 1000);
        SubtitleTrack track = new SubtitleTrack("en", Collections.singletonList(w));

        boolean drawn = drawer.draw(target, track, style, 500L, 1080, 1920);
        assertTrue(drawn);
        assertEquals(1, target.roundRects.size());
        assertEquals(SubtitleStyle.PREMIUM_SCALE_ACTIVE_WORD, target.lastScaleX, 0.001f);
    }

    @Test
    public void testWorldwideLanguageSupportBengaliAndArabic() {
        RecordingDrawTarget target = new RecordingDrawTarget();
        SubtitleStyle style = SubtitleStyle.yellowPill();

        // Bengali script
        SubtitleWord b1 = new SubtitleWord("আমি", 0, 300);
        SubtitleWord b2 = new SubtitleWord("বাংলায়", 310, 700);
        SubtitleWord b3 = new SubtitleWord("গান", 710, 1000);
        SubtitleWord b4 = new SubtitleWord("গাই", 1010, 1300);
        SubtitleTrack bengaliTrack = new SubtitleTrack("bn", Arrays.asList(b1, b2, b3, b4));

        boolean drawnBengali = drawer.draw(target, bengaliTrack, style, 500L, 1080, 1920);
        assertTrue(drawnBengali);
        assertTrue(target.drawnTexts.contains("বাংলায়"));

        // Arabic script
        RecordingDrawTarget targetArabic = new RecordingDrawTarget();
        SubtitleWord a1 = new SubtitleWord("مرحبا", 0, 500);
        SubtitleWord a2 = new SubtitleWord("بالعالم", 550, 1200);
        SubtitleTrack arabicTrack = new SubtitleTrack("ar", Arrays.asList(a1, a2));

        boolean drawnArabic = drawer.draw(targetArabic, arabicTrack, style, 200L, 1080, 1920);
        assertTrue(drawnArabic);
        assertTrue(targetArabic.drawnTexts.contains("مرحبا"));
    }

    @Test
    public void testComputeBoundsNullAndInvalidInputs() {
        SubtitleWord w = new SubtitleWord("Test", 0, 500);
        SubtitlePhrase phrase = new SubtitlePhrase(Collections.singletonList(w));
        SubtitleStyle style = SubtitleStyle.greenPill();

        SubtitleDrawer.Bounds out = new SubtitleDrawer.Bounds();
        assertFalse(drawer.computePhraseBounds(null, style, 1080, 1920, out));
        assertFalse(drawer.computePhraseBounds(phrase, null, 1080, 1920, out));
        assertFalse(drawer.computePhraseBounds(phrase, style, 0, 1920, out));
        assertFalse(drawer.computePhraseBounds(phrase, style, 1080, 0, out));
        assertFalse(drawer.computePhraseBounds(phrase, style, 1080, 1920, (SubtitleDrawer.Bounds) null));

        // Invalid word indices
        assertFalse(drawer.computeWordBounds(phrase, -1, style, 1080, 1920, out));
        assertFalse(drawer.computeWordBounds(phrase, 5, style, 1080, 1920, out));
        assertNull(drawer.computeWordBounds(phrase, 5, style, 1080, 1920));
        assertNull(drawer.computePhraseBounds(null, style, 1080, 1920));
    }
}
