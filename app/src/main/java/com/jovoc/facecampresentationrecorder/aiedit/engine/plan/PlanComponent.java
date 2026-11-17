package com.jovoc.facecampresentationrecorder.aiedit.engine.plan;

/**
 * What an overlay item draws: a styled text, or a Lottie or icon image of the plan's asset list. One class for
 * both, the fields of the other kind are null or 0.
 */
public final class PlanComponent {
    public static final String STYLED_TEXT = "styled_text";
    public static final String LOTTIE = "lottie";
    public static final String ICON_IMAGE = "icon_image";

    /** {@link #STYLED_TEXT}, {@link #LOTTIE} or {@link #ICON_IMAGE}. */
    public final String type;

    // styled_text
    /** {@code keyword_echo}, {@code hook}, {@code commentary} or {@code complementary}. */
    public final String kind;
    public final String text;
    /** Id of a {@code TextLook}. */
    public final String stylePreset;
    public final int colorSlot;

    // lottie, icon_image
    public final String assetId;
    public final int nativeDurationMs;
    /** {@code speed_up} or {@code native}; null when the plan gave no fit. */
    public final String fitMode;
    public final double fitFactor;

    private PlanComponent(String type, String kind, String text, String stylePreset, int colorSlot,
                          String assetId, int nativeDurationMs, String fitMode, double fitFactor) {
        this.type = type;
        this.kind = kind;
        this.text = text;
        this.stylePreset = stylePreset;
        this.colorSlot = colorSlot;
        this.assetId = assetId;
        this.nativeDurationMs = nativeDurationMs;
        this.fitMode = fitMode;
        this.fitFactor = fitFactor;
    }

    public static PlanComponent styledText(String kind, String text, String stylePreset, int colorSlot) {
        return new PlanComponent(STYLED_TEXT, kind, text, stylePreset, colorSlot, null, 0, null, 1.0);
    }

    public static PlanComponent asset(String type, String assetId, int nativeDurationMs,
                                      String fitMode, double fitFactor) {
        return new PlanComponent(type, null, null, null, 0, assetId, nativeDurationMs, fitMode, fitFactor);
    }

    public boolean isText() {
        return STYLED_TEXT.equals(type);
    }
}
