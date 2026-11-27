package com.jovoc.facecampresentationrecorder.subtitle;

import android.content.Context;

import com.jovoc.facecampresentationrecorder.util.RecordSettings;

import java.util.Objects;

/**
 * Visual styling configuration for subtitle rendering and karaoke highlight.
 */
public final class SubtitleStyle {

    public static final String TEMPLATE_GREEN_PILL = "green_pill";
    public static final String TEMPLATE_YELLOW_PILL = "yellow_pill";
    public static final String TEMPLATE_CYAN_HIGHLIGHT = "cyan_highlight";
    public static final String TEMPLATE_PREMIUM = "pro_bounce";

    public static final float DEFAULT_POSITION_Y = 0.95f;
    public static final float DEFAULT_SCALE_ACTIVE_WORD = 1.05f;
    public static final float PREMIUM_SCALE_ACTIVE_WORD = 1.10f;
    public static final float DEFAULT_FONT_SIZE_SP = 20.0f;
    public static final float DEFAULT_CORNER_RADIUS_DP = 6.0f;
    public static final float DEFAULT_PADDING_HORIZONTAL_DP = 8.0f;
    public static final float DEFAULT_PADDING_VERTICAL_DP = 4.0f;

    public final String templateId;
    public final int pillColor;
    public final int pillEndColor;
    public final int textColor;
    public final int activeTextColor;
    public final int outlineColor;
    public final float cornerRadiusDp;
    public final float paddingHorizontalDp;
    public final float paddingVerticalDp;
    public final float fontSizeSp;
    public final float positionY;
    public final float scaleActiveWord;

    public SubtitleStyle(String templateId, int pillColor, int pillEndColor, int textColor,
                         int activeTextColor, int outlineColor, float cornerRadiusDp,
                         float paddingHorizontalDp, float paddingVerticalDp,
                         float fontSizeSp, float positionY, float scaleActiveWord) {
        this.templateId = templateId != null ? templateId : TEMPLATE_CYAN_HIGHLIGHT;
        this.pillColor = pillColor;
        this.pillEndColor = pillEndColor;
        this.textColor = textColor;
        this.activeTextColor = activeTextColor;
        this.outlineColor = outlineColor;
        this.cornerRadiusDp = cornerRadiusDp;
        this.paddingHorizontalDp = paddingHorizontalDp;
        this.paddingVerticalDp = paddingVerticalDp;
        this.fontSizeSp = fontSizeSp;
        this.positionY = positionY;
        this.scaleActiveWord = scaleActiveWord;
    }

    /**
     * True if the pill background uses a gradient (different start and end colors).
     */
    public boolean isGradient() {
        return pillEndColor != 0 && pillEndColor != pillColor;
    }

    public static SubtitleStyle greenPill(float fontSizeSp, float positionY) {
        return new SubtitleStyle(
                TEMPLATE_GREEN_PILL,
                0xFF00E575,
                0xFF00E575,
                0xFFFFFFFF,
                0xFF000000,
                0xCC000000,
                DEFAULT_CORNER_RADIUS_DP,
                DEFAULT_PADDING_HORIZONTAL_DP,
                DEFAULT_PADDING_VERTICAL_DP,
                fontSizeSp,
                positionY,
                DEFAULT_SCALE_ACTIVE_WORD
        );
    }

    public static SubtitleStyle yellowPill(float fontSizeSp, float positionY) {
        return new SubtitleStyle(
                TEMPLATE_YELLOW_PILL,
                0xFFFFD700,
                0xFFFFD700,
                0xFFFFFFFF,
                0xFF000000,
                0xCC000000,
                DEFAULT_CORNER_RADIUS_DP,
                DEFAULT_PADDING_HORIZONTAL_DP,
                DEFAULT_PADDING_VERTICAL_DP,
                fontSizeSp,
                positionY,
                DEFAULT_SCALE_ACTIVE_WORD
        );
    }

    public static SubtitleStyle cyanHighlight(float fontSizeSp, float positionY) {
        return new SubtitleStyle(
                TEMPLATE_CYAN_HIGHLIGHT,
                0xFF000000,
                0xFF000000,
                0xFFFFFFFF,
                0xFF00E5FF,
                0xFF000000,
                DEFAULT_CORNER_RADIUS_DP,
                DEFAULT_PADDING_HORIZONTAL_DP,
                DEFAULT_PADDING_VERTICAL_DP,
                fontSizeSp,
                positionY,
                DEFAULT_SCALE_ACTIVE_WORD
        );
    }

    public static SubtitleStyle premium(float fontSizeSp, float positionY) {
        return new SubtitleStyle(
                TEMPLATE_PREMIUM,
                0xFF8B5CF6,
                0xFFEC4899,
                0xFFFFFFFF,
                0xFFFFFFFF,
                0xCC000000,
                DEFAULT_CORNER_RADIUS_DP,
                DEFAULT_PADDING_HORIZONTAL_DP,
                DEFAULT_PADDING_VERTICAL_DP,
                fontSizeSp,
                positionY,
                PREMIUM_SCALE_ACTIVE_WORD
        );
    }

    public static SubtitleStyle greenPill() {
        return greenPill(DEFAULT_FONT_SIZE_SP, DEFAULT_POSITION_Y);
    }

    public static SubtitleStyle yellowPill() {
        return yellowPill(DEFAULT_FONT_SIZE_SP, DEFAULT_POSITION_Y);
    }

    public static SubtitleStyle cyanHighlight() {
        return cyanHighlight(DEFAULT_FONT_SIZE_SP, DEFAULT_POSITION_Y);
    }

    public static SubtitleStyle premium() {
        return premium(DEFAULT_FONT_SIZE_SP, DEFAULT_POSITION_Y);
    }

    public static SubtitleStyle fromTemplate(String templateId, float fontSizeSp, float positionY) {
        if (templateId == null) templateId = TEMPLATE_CYAN_HIGHLIGHT;
        switch (templateId) {
            case TEMPLATE_GREEN_PILL:
                return greenPill(fontSizeSp, positionY);
            case TEMPLATE_YELLOW_PILL:
                return yellowPill(fontSizeSp, positionY);
            case TEMPLATE_PREMIUM:
                return premium(fontSizeSp, positionY);
            case TEMPLATE_CYAN_HIGHLIGHT:
            default:
                return cyanHighlight(fontSizeSp, positionY);
        }
    }

    public static SubtitleStyle fromTemplate(String templateId) {
        return fromTemplate(templateId, DEFAULT_FONT_SIZE_SP, DEFAULT_POSITION_Y);
    }

    /**
     * Constructs a {@link SubtitleStyle} matching current user settings.
     */
    public static SubtitleStyle fromSettings(Context context) {
        if (context == null) {
            return cyanHighlight();
        }
        String template = RecordSettings.subtitleTemplate(context);
        String fontSizeStr = RecordSettings.subtitleFontSize(context);
        float positionY = RecordSettings.subtitlePositionY(context);

        float fontSizeSp = DEFAULT_FONT_SIZE_SP;
        if ("small".equalsIgnoreCase(fontSizeStr)) {
            fontSizeSp = 16.0f;
        } else if ("large".equalsIgnoreCase(fontSizeStr)) {
            fontSizeSp = 24.0f;
        }

        return fromTemplate(template, fontSizeSp, positionY);
    }

    public SubtitleStyle withFontSize(float fontSizeSp) {
        return new SubtitleStyle(templateId, pillColor, pillEndColor, textColor, activeTextColor,
                outlineColor, cornerRadiusDp, paddingHorizontalDp, paddingVerticalDp,
                fontSizeSp, positionY, scaleActiveWord);
    }

    public SubtitleStyle withPositionY(float positionY) {
        return new SubtitleStyle(templateId, pillColor, pillEndColor, textColor, activeTextColor,
                outlineColor, cornerRadiusDp, paddingHorizontalDp, paddingVerticalDp,
                fontSizeSp, positionY, scaleActiveWord);
    }

    public SubtitleStyle withScaleActiveWord(float scaleActiveWord) {
        return new SubtitleStyle(templateId, pillColor, pillEndColor, textColor, activeTextColor,
                outlineColor, cornerRadiusDp, paddingHorizontalDp, paddingVerticalDp,
                fontSizeSp, positionY, scaleActiveWord);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SubtitleStyle that = (SubtitleStyle) o;
        return pillColor == that.pillColor &&
                pillEndColor == that.pillEndColor &&
                textColor == that.textColor &&
                activeTextColor == that.activeTextColor &&
                outlineColor == that.outlineColor &&
                Float.compare(that.cornerRadiusDp, cornerRadiusDp) == 0 &&
                Float.compare(that.paddingHorizontalDp, paddingHorizontalDp) == 0 &&
                Float.compare(that.paddingVerticalDp, paddingVerticalDp) == 0 &&
                Float.compare(that.fontSizeSp, fontSizeSp) == 0 &&
                Float.compare(that.positionY, positionY) == 0 &&
                Float.compare(that.scaleActiveWord, scaleActiveWord) == 0 &&
                Objects.equals(templateId, that.templateId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(templateId, pillColor, pillEndColor, textColor, activeTextColor,
                outlineColor, cornerRadiusDp, paddingHorizontalDp, paddingVerticalDp,
                fontSizeSp, positionY, scaleActiveWord);
    }

    @Override
    public String toString() {
        return "SubtitleStyle{" +
                "templateId='" + templateId + '\'' +
                ", pillColor=" + String.format("#%08X", pillColor) +
                ", pillEndColor=" + String.format("#%08X", pillEndColor) +
                ", textColor=" + String.format("#%08X", textColor) +
                ", activeTextColor=" + String.format("#%08X", activeTextColor) +
                ", outlineColor=" + String.format("#%08X", outlineColor) +
                ", fontSizeSp=" + fontSizeSp +
                ", positionY=" + positionY +
                ", scaleActiveWord=" + scaleActiveWord +
                '}';
    }
}
