package com.jovoc.facecampresentationrecorder.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.jovoc.facecampresentationrecorder.R;

/**
 * A horizontal divider that looks drawn by hand with a pen: one thin line that sags a little,
 * drifts upward to the right, trembles slightly, presses in at the start, lifts off towards the
 * end and finishes with a small flick. Drawn as short round-capped segments of varying width, so
 * the pen pressure reads as a smooth change in thickness.
 */
public class BrushDividerView extends View {

    private static final int SEGMENTS = 48;
    /** Thickest point of the line, in dp. */
    private static final float MAX_WIDTH_DP = 2.8f;
    /** Thinnest point (the tips), as a fraction of the thickest. */
    private static final float TIP_WIDTH = 0.35f;
    /** Where the end flick starts, as a fraction of the length. */
    private static final float FLICK_FROM = 0.9f;

    private final Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final float[] xs = new float[SEGMENTS + 1];
    private final float[] ys = new float[SEGMENTS + 1];
    private final float[] widths = new float[SEGMENTS + 1];

    public BrushDividerView(Context context) {
        this(context, null);
    }

    public BrushDividerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        pen.setColor(ContextCompat.getColor(context, R.color.onb_highlight));
        pen.setStyle(Paint.Style.STROKE);
        pen.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float maxWidth = MAX_WIDTH_DP * density;
        // Inset by the pen's radius so the round tips are never clipped square.
        float left = maxWidth;
        float right = w - maxWidth;
        float length = right - left;
        float mid = h * 0.55f;
        for (int i = 0; i <= SEGMENTS; i++) {
            float t = i / (float) SEGMENTS;
            float sag = (float) Math.sin(Math.PI * t) * h * 0.10f;
            float drift = -(t - 0.5f) * h * 0.18f;
            float tremor = (float) (Math.sin(t * 17.0 + 1.3) * 0.035 + Math.sin(t * 41.0 + 0.4) * 0.015) * h;
            float flickT = t > FLICK_FROM ? (t - FLICK_FROM) / (1f - FLICK_FROM) : 0f;
            float flick = -flickT * flickT * h * 0.22f;
            // The hand slows into the flick, so the last stretch covers less ground.
            float along = t > FLICK_FROM ? FLICK_FROM + (t - FLICK_FROM) * 0.6f : t;
            xs[i] = left + along / (FLICK_FROM + (1f - FLICK_FROM) * 0.6f) * length;
            ys[i] = mid + sag + drift + tremor + flick;

            float pressIn = Math.min(1f, t / 0.12f);
            float liftOff = t > 0.6f ? 1f - (t - 0.6f) / 0.4f : 1f;
            float pressure = smooth(pressIn) * (0.25f + 0.75f * smooth(liftOff));
            float wobble = 1f + (float) Math.sin(t * 29.0 + 2.1) * 0.08f;
            widths[i] = maxWidth * (TIP_WIDTH + (1f - TIP_WIDTH) * pressure) * wobble;
        }
    }

    private static float smooth(float t) {
        return t * t * (3f - 2f * t);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (getWidth() <= 0) return;
        for (int i = 1; i <= SEGMENTS; i++) {
            pen.setStrokeWidth((widths[i - 1] + widths[i]) / 2f);
            canvas.drawLine(xs[i - 1], ys[i - 1], xs[i], ys[i], pen);
        }
    }
}
