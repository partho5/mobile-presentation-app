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
 * A horizontal divider that looks swiped on with a brush: it wobbles a little, swells in the
 * middle and thins out at both ends, with a faint dry-brush streak beneath it.
 */
public class BrushDividerView extends View {

    private static final int SEED = 4;

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint streak = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path strokePath = new Path();
    private final Path streakPath = new Path();

    public BrushDividerView(Context context) {
        this(context, null);
    }

    public BrushDividerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        int amber = ContextCompat.getColor(context, R.color.onb_highlight);
        stroke.setColor(amber);
        streak.setColor(amber);
        streak.setAlpha(90);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        buildRibbon(strokePath, w, h, h * 0.45f, h * 0.30f, SEED);
        buildRibbon(streakPath, w, h, h * 0.45f + h * 0.28f, h * 0.09f, SEED + 3);
    }

    /** A closed ribbon from left to right: wobbling centre line, thickness swelling and tapering. */
    private static void buildRibbon(Path path, int w, int h, float centerY, float maxHalf, int seed) {
        path.reset();
        if (w <= 0) return;
        final int steps = 20;
        float[] x = new float[steps + 1];
        float[] top = new float[steps + 1];
        float[] bottom = new float[steps + 1];
        for (int i = 0; i <= steps; i++) {
            float t = i / (float) steps;
            float wobble = (float) (Math.sin(t * 6.3 + seed) * 0.16 + Math.sin(t * 15.7 + seed * 2) * 0.07) * h;
            float taper = (float) Math.pow(Math.sin(Math.PI * t), 0.45);
            float rough = (float) Math.sin(t * 23.0 + seed * 3) * 0.12f;
            float half = maxHalf * (0.35f + 0.65f * taper) * (1f + rough);
            x[i] = t * w;
            top[i] = centerY + wobble - half;
            bottom[i] = centerY + wobble + half;
        }
        path.moveTo(x[0], top[0]);
        for (int i = 1; i <= steps; i++) {
            path.quadTo((x[i - 1] + x[i]) / 2f, top[i - 1], (x[i - 1] + x[i]) / 2f, (top[i - 1] + top[i]) / 2f);
        }
        path.lineTo(x[steps], top[steps]);
        path.lineTo(x[steps], bottom[steps]);
        for (int i = steps; i >= 1; i--) {
            path.quadTo((x[i - 1] + x[i]) / 2f, bottom[i], (x[i - 1] + x[i]) / 2f, (bottom[i - 1] + bottom[i]) / 2f);
        }
        path.lineTo(x[0], bottom[0]);
        path.close();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawPath(streakPath, streak);
        canvas.drawPath(strokePath, stroke);
    }
}
