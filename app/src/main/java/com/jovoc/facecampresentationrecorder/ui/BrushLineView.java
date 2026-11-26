package com.jovoc.facecampresentationrecorder.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.jovoc.facecampresentationrecorder.R;

/**
 * A vertical line that looks drawn by hand with a pen, top to bottom: one thin line that bows
 * a little, leans slightly, trembles, presses in at the top, lifts off towards the bottom and
 * finishes with a small flick. Fills the height it is given; sideways movement scales with the
 * view's width so a tall line stays as steady as a short one. Drawn as short round-capped
 * segments of varying width, the same way as {@link BrushDividerView}.
 */
public class BrushLineView extends View {

    /** Thickest point of the line, in dp. */
    private static final float MAX_WIDTH_DP = 2.2f;
    /** Thinnest point (the tips), as a fraction of the thickest. */
    private static final float TIP_WIDTH = 0.35f;
    /** One segment per this many dp of height. */
    private static final float SEGMENT_DP = 4f;
    /** Length of the end flick, in dp. */
    private static final float FLICK_DP = 10f;

    private final Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private float[] xs = new float[0];
    private float[] ys = new float[0];
    private float[] widths = new float[0];

    public BrushLineView(Context context) {
        this(context, null);
    }

    public BrushLineView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        pen.setColor(ContextCompat.getColor(context, R.color.onb_accent));
        pen.setStyle(Paint.Style.STROKE);
        pen.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) {
            xs = ys = widths = new float[0];
            return;
        }
        float maxWidth = MAX_WIDTH_DP * density;
        // Inset by the pen's radius so the round tips are never clipped square.
        float top = maxWidth;
        float bottom = h - maxWidth;
        float length = bottom - top;
        int segments = Math.max(24, Math.round(length / (SEGMENT_DP * density)));
        xs = new float[segments + 1];
        ys = new float[segments + 1];
        widths = new float[segments + 1];
        // The flick is a fixed length, so it looks the same however tall the line is.
        float flickFrom = Math.max(0.75f, 1f - FLICK_DP * density / length);
        float slow = 0.6f;
        float span = flickFrom + (1f - flickFrom) * slow;
        float mid = w * 0.45f;
        for (int i = 0; i <= segments; i++) {
            float t = i / (float) segments;
            float bow = (float) Math.sin(Math.PI * t) * w * 0.12f;
            float lean = (t - 0.5f) * w * 0.18f;
            float tremor = (float) (Math.sin(t * 17.0 + 1.3) * 0.05 + Math.sin(t * 41.0 + 0.4) * 0.02) * w;
            float flickT = t > flickFrom ? (t - flickFrom) / (1f - flickFrom) : 0f;
            float flick = flickT * flickT * w * 0.3f;
            // The hand slows into the flick, so the last stretch covers less ground.
            float along = t > flickFrom ? flickFrom + (t - flickFrom) * slow : t;
            ys[i] = top + along / span * length;
            xs[i] = mid - bow + lean + tremor + flick;

            float pressIn = Math.min(1f, t / 0.08f);
            float liftOff = t > 0.7f ? 1f - (t - 0.7f) / 0.3f : 1f;
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
        for (int i = 1; i < xs.length; i++) {
            pen.setStrokeWidth((widths[i - 1] + widths[i]) / 2f);
            canvas.drawLine(xs[i - 1], ys[i - 1], xs[i], ys[i], pen);
        }
    }
}
