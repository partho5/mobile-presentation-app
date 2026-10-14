package com.jovoc.facecampresentationrecorder.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import com.jovoc.facecampresentationrecorder.R;

import java.util.Random;

/**
 * Onboarding background: a navy to near-black vertical gradient, one soft cyan glow that the
 * activity moves between screens, and a fine noise layer that hides gradient banding on
 * low-end displays. Nothing here animates on its own.
 */
public class OnboardingBackgroundView extends View {

    /** Glow opacity at its centre. */
    private static final float GLOW_ALPHA = 0.08f;
    /** Glow diameter relative to the shorter side of the view. */
    private static final float GLOW_SIZE = 0.8f;
    /** Falloff from the centre to the rim, eased so the glow has no visible edge. */
    private static final float[] GLOW_STOPS = {0f, 0.25f, 0.5f, 0.75f, 1f};
    private static final float[] GLOW_FALLOFF = {1f, 0.72f, 0.35f, 0.1f, 0f};

    private static final int NOISE_TILE_PX = 128;
    /** About 2.4% opacity. */
    private static final int NOISE_ALPHA = 6;

    private final Paint basePaint = new Paint();
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint noisePaint = new Paint();
    private final int topColor;
    private final int bottomColor;
    private final int[] glowColors = new int[GLOW_FALLOFF.length];

    private float glowRadius;
    private float glowX = 0.5f;
    private float glowY = 0.3f;

    public OnboardingBackgroundView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        topColor = ContextCompat.getColor(context, R.color.onb_bg_top);
        bottomColor = ContextCompat.getColor(context, R.color.onb_bg_bottom);
        int accent = ContextCompat.getColor(context, R.color.onb_accent);
        for (int i = 0; i < glowColors.length; i++) {
            glowColors[i] = ColorUtils.setAlphaComponent(accent,
                    Math.round(255 * GLOW_ALPHA * GLOW_FALLOFF[i]));
        }
        noisePaint.setShader(new BitmapShader(noiseTile(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        noisePaint.setAlpha(NOISE_ALPHA);
    }

    /** Moves the glow. Both values are fractions of the view's width and height. */
    public void setGlowCenter(float xFraction, float yFraction) {
        if (xFraction == glowX && yFraction == glowY) return;
        glowX = xFraction;
        glowY = yFraction;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w == 0 || h == 0) return;
        basePaint.setShader(new LinearGradient(0, 0, 0, h, topColor, bottomColor, Shader.TileMode.CLAMP));
        glowRadius = GLOW_SIZE * Math.min(w, h) / 2f;
        glowPaint.setShader(new RadialGradient(0, 0, glowRadius, glowColors, GLOW_STOPS, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawPaint(basePaint);
        canvas.save();
        canvas.translate(glowX * getWidth(), glowY * getHeight());
        canvas.drawCircle(0, 0, glowRadius, glowPaint);
        canvas.restore();
        canvas.drawPaint(noisePaint);
    }

    private static Bitmap noiseTile() {
        int[] pixels = new int[NOISE_TILE_PX * NOISE_TILE_PX];
        Random random = new Random(7); // fixed seed: the same texture on every launch
        for (int i = 0; i < pixels.length; i++) {
            int gray = random.nextInt(256);
            pixels[i] = 0xFF000000 | (gray << 16) | (gray << 8) | gray;
        }
        return Bitmap.createBitmap(pixels, NOISE_TILE_PX, NOISE_TILE_PX, Bitmap.Config.ARGB_8888);
    }
}
