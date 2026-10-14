package com.jovoc.facecampresentationrecorder.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.jovoc.facecampresentationrecorder.R;

/**
 * A vertical line that looks drawn by hand with a brush: it wobbles, swells in the middle and
 * thins out at both ends, with a faint dry-brush streak beside it. Fills the height it is given.
 */
public class BrushLineView extends View {

    private static final int SEED = 7;

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint streak = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path strokePath = new Path();
    private final Path streakPath = new Path();

    public BrushLineView(Context context) {
        this(context, null);
    }

    public BrushLineView(Context context, AttributeSet attrs) {
        super(context, attrs);
        int cyan = ContextCompat.getColor(context, R.color.onb_accent);
        stroke.setColor(cyan);
        streak.setColor(cyan);
        streak.setAlpha(90);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        buildRibbon(strokePath, w, h, w * 0.5f, w * 0.30f, SEED);
        buildRibbon(streakPath, w, h, w * 0.5f + w * 0.28f, w * 0.09f, SEED + 3);
    }

    /** A closed ribbon from top to bottom: wobbling centre line, width swelling and tapering. */
    private static void buildRibbon(Path path, int w, int h, float centerX, float maxHalfWidth, int seed) {
        path.reset();
        if (h <= 0) return;
        int steps = Math.max(8, Math.round(h / (w * 2.2f)));
        float[] lx = new float[steps + 1];
        float[] rx = new float[steps + 1];
        float[] y = new float[steps + 1];
        for (int i = 0; i <= steps; i++) {
            float t = i / (float) steps;
            float wobble = (float) (Math.sin(t * 6.3 + seed) * 0.16 + Math.sin(t * 15.7 + seed * 2) * 0.07) * w;
            float taper = (float) Math.pow(Math.sin(Math.PI * t), 0.45);
            float rough = (float) Math.sin(t * 23.0 + seed * 3) * 0.12f;
            float half = maxHalfWidth * (0.35f + 0.65f * taper) * (1f + rough);
            float cx = centerX + wobble;
            lx[i] = cx - half;
            rx[i] = cx + half;
            y[i] = t * h;
        }
        path.moveTo(lx[0], y[0]);
        for (int i = 1; i <= steps; i++) {
            path.quadTo(lx[i - 1], (y[i - 1] + y[i]) / 2f, (lx[i - 1] + lx[i]) / 2f, (y[i - 1] + y[i]) / 2f);
        }
        path.lineTo(lx[steps], y[steps]);
        path.lineTo(rx[steps], y[steps]);
        for (int i = steps; i >= 1; i--) {
            path.quadTo(rx[i], (y[i - 1] + y[i]) / 2f, (rx[i - 1] + rx[i]) / 2f, (y[i - 1] + y[i]) / 2f);
        }
        path.lineTo(rx[0], y[0]);
        path.close();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawPath(streakPath, streak);
        canvas.drawPath(strokePath, stroke);
    }
}
