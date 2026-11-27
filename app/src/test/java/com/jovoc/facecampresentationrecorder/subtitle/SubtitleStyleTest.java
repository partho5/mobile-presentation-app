package com.jovoc.facecampresentationrecorder.subtitle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SubtitleStyleTest {

    @Test
    public void testGreenPillTemplate() {
        SubtitleStyle style = SubtitleStyle.greenPill();
        assertEquals(SubtitleStyle.TEMPLATE_GREEN_PILL, style.templateId);
        assertEquals(0xFF00E575, style.pillColor);
        assertEquals(0xFF00E575, style.pillEndColor);
        assertFalse(style.isGradient());
        assertEquals(0xFF000000, style.activeTextColor);
        assertEquals(0xFFFFFFFF, style.textColor);
        assertEquals(0xCC000000, style.outlineColor);
        assertEquals(1.05f, style.scaleActiveWord, 0.001f);
    }

    @Test
    public void testYellowPillTemplate() {
        SubtitleStyle style = SubtitleStyle.yellowPill();
        assertEquals(SubtitleStyle.TEMPLATE_YELLOW_PILL, style.templateId);
        assertEquals(0xFFFFD700, style.pillColor);
        assertEquals(0xFFFFD700, style.pillEndColor);
        assertFalse(style.isGradient());
        assertEquals(0xFF000000, style.activeTextColor);
        assertEquals(0xFFFFFFFF, style.textColor);
    }

    @Test
    public void testCyanHighlightTemplate() {
        SubtitleStyle style = SubtitleStyle.cyanHighlight();
        assertEquals(SubtitleStyle.TEMPLATE_CYAN_HIGHLIGHT, style.templateId);
        assertEquals(0xFF000000, style.pillColor);
        assertEquals(0xFF000000, style.outlineColor);
        assertEquals(0xFF00E5FF, style.activeTextColor);
        assertEquals(0xFFFFFFFF, style.textColor);
    }

    @Test
    public void testPremiumTemplateGradientAndScale() {
        SubtitleStyle style = SubtitleStyle.premium();
        assertEquals(SubtitleStyle.TEMPLATE_PREMIUM, style.templateId);
        assertEquals(0xFF8B5CF6, style.pillColor);
        assertEquals(0xFFEC4899, style.pillEndColor);
        assertTrue(style.isGradient());
        assertEquals(0xFFFFFFFF, style.activeTextColor);
        assertEquals(1.10f, style.scaleActiveWord, 0.001f);
    }

    @Test
    public void testFromTemplateHelper() {
        SubtitleStyle green = SubtitleStyle.fromTemplate("green_pill");
        assertEquals(SubtitleStyle.TEMPLATE_GREEN_PILL, green.templateId);

        SubtitleStyle yellow = SubtitleStyle.fromTemplate("yellow_pill");
        assertEquals(SubtitleStyle.TEMPLATE_YELLOW_PILL, yellow.templateId);

        SubtitleStyle white = SubtitleStyle.fromTemplate("cyan_highlight");
        assertEquals(SubtitleStyle.TEMPLATE_CYAN_HIGHLIGHT, white.templateId);

        SubtitleStyle pro = SubtitleStyle.fromTemplate("pro_bounce");
        assertEquals(SubtitleStyle.TEMPLATE_PREMIUM, pro.templateId);

        // Unknown fallback
        SubtitleStyle unknown = SubtitleStyle.fromTemplate("non_existent");
        assertEquals(SubtitleStyle.TEMPLATE_CYAN_HIGHLIGHT, unknown.templateId);

        SubtitleStyle missing = SubtitleStyle.fromTemplate(null);
        assertEquals(SubtitleStyle.TEMPLATE_CYAN_HIGHLIGHT, missing.templateId);

        // The pre-rename id that may still be saved on devices lands on the same cyan style
        SubtitleStyle legacy = SubtitleStyle.fromTemplate("clean_white");
        assertEquals(SubtitleStyle.TEMPLATE_CYAN_HIGHLIGHT, legacy.templateId);
    }

    @Test
    public void testWithModifiers() {
        SubtitleStyle base = SubtitleStyle.greenPill();
        SubtitleStyle custom = base.withFontSize(28.0f)
                .withPositionY(0.85f)
                .withScaleActiveWord(1.20f);

        assertEquals(28.0f, custom.fontSizeSp, 0.001f);
        assertEquals(0.85f, custom.positionY, 0.001f);
        assertEquals(1.20f, custom.scaleActiveWord, 0.001f);
        assertEquals(base.templateId, custom.templateId);
    }
}
