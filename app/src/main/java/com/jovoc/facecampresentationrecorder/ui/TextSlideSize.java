package com.jovoc.facecampresentationrecorder.ui;

/**
 * The font size of a text topic from its length: one rule shared by the recorder and the Practice screen, so a
 * hook looks the same in both. Never looks at the language, only at how long the text is.
 */
public final class TextSlideSize {

    private static final float MAX_SP = 26.0f;
    private static final float MIN_SP = 15.0f;

    private TextSlideSize() {
    }

    /** 26sp up to 100 characters, then linear down to 15sp at 200 characters and more. */
    public static float sizeSp(int length) {
        if (length <= 100) return MAX_SP;
        if (length >= 200) return MIN_SP;
        float fraction = (length - 100) / 100.0f;
        return MAX_SP - fraction * (MAX_SP - MIN_SP);
    }
}
