package com.jovoc.facecampresentationrecorder.ui;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.view.MotionEvent;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.ImageView;

/**
 * Active/inactive look of a white tool icon (slide pen, drawing board toggle), animated both ways:
 * a short scale pop, the tint crossfading between white and the active colour, brightness 80%..100%
 * and a soft white disc behind the icon (so an active white pen still reads as "on").
 * A press shrinks the icon a little until the finger lifts.
 */
public final class ToolIconAnimator {

    private static final float IDLE_ALPHA = 0.8f;
    private static final int DISC_MAX_ALPHA = 56; // about 22% white
    private static final long FADE_MS = 240L;
    private static final long POP_MS = 300L;
    private static final long PRESS_MS = 90L;
    private static final long RELEASE_MS = 140L;
    private static final float POP_SCALE = 1.15f;
    private static final float PRESS_SCALE = 0.88f;

    private final ImageView icon;
    private final GradientDrawable disc = new GradientDrawable();
    private final ArgbEvaluator argb = new ArgbEvaluator();

    private boolean active = false;
    private int tint = Color.WHITE; // what is on screen right now
    private float level = 0f;       // 0 = inactive look, 1 = active look
    private ValueAnimator fadeAnim;
    private ValueAnimator popAnim;

    @SuppressLint("ClickableViewAccessibility") // returns false: the click itself is untouched
    public ToolIconAnimator(ImageView icon) {
        this.icon = icon;
        disc.setShape(GradientDrawable.OVAL);
        disc.setColor(Color.WHITE);
        icon.setBackground(disc);
        // The XML had no background, so no shadow: keep it that way despite the elevation.
        icon.setOutlineProvider(null);
        icon.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    cancelPop();
                    v.animate().cancel();
                    v.animate().scaleX(PRESS_SCALE).scaleY(PRESS_SCALE).setDuration(PRESS_MS)
                            .setInterpolator(new DecelerateInterpolator()).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    // A click right after this starts the pop, which takes over from the current scale.
                    v.animate().scaleX(1f).scaleY(1f).setDuration(RELEASE_MS)
                            .setInterpolator(new DecelerateInterpolator()).start();
                    break;
                default:
                    break;
            }
            return false;
        });
        render();
    }

    /**
     * Shows the icon on (lit in {@code color}) or off (white). A state change pops the icon; a colour
     * change alone only crossfades. Not animated while the icon is off screen.
     */
    public void setActive(boolean on, int color, boolean animate) {
        boolean changed = on != active;
        active = on;
        int toTint = on ? color : Color.WHITE;
        float toLevel = on ? 1f : 0f;
        if (fadeAnim != null) fadeAnim.cancel();
        if (!animate || !icon.isShown()) {
            cancelPop();
            icon.animate().cancel();
            icon.setScaleX(1f);
            icon.setScaleY(1f);
            tint = toTint;
            level = toLevel;
            render();
            return;
        }
        int fromTint = tint;
        float fromLevel = level;
        fadeAnim = ValueAnimator.ofFloat(0f, 1f);
        fadeAnim.setDuration(FADE_MS);
        fadeAnim.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
        fadeAnim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            tint = (int) argb.evaluate(f, fromTint, toTint);
            level = fromLevel + (toLevel - fromLevel) * f;
            render();
        });
        fadeAnim.start();
        if (changed) pop();
    }

    private void pop() {
        cancelPop();
        icon.animate().cancel();
        popAnim = ValueAnimator.ofFloat(icon.getScaleX(), POP_SCALE, 1f);
        popAnim.setDuration(POP_MS);
        popAnim.setInterpolator(new PathInterpolator(0.3f, 0f, 0.3f, 1f));
        popAnim.addUpdateListener(a -> {
            float s = (float) a.getAnimatedValue();
            icon.setScaleX(s);
            icon.setScaleY(s);
        });
        popAnim.start();
    }

    private void cancelPop() {
        if (popAnim != null) popAnim.cancel();
    }

    private void render() {
        icon.setColorFilter(tint, PorterDuff.Mode.SRC_IN);
        icon.setAlpha(IDLE_ALPHA + (1f - IDLE_ALPHA) * level);
        disc.setAlpha(Math.round(DISC_MAX_ALPHA * level));
    }
}
