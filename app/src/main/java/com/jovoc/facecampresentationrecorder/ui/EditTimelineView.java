package com.jovoc.facecampresentationrecorder.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import com.jovoc.facecampresentationrecorder.R;

/**
 * The editor's timeline: a strip of video thumbnails over the edited result (what is left after
 * removals), a thin playhead, small markers above it at every join, and the section the user is
 * picking to remove: a sharp Start line and End line with a tint between them.
 * A tap places a marker, dragging scrubs. The view only draws and reports gestures; the editor
 * screen owns the state. Times are milliseconds on the edited timeline.
 */
public class EditTimelineView extends View {

    public interface Listener {
        /** A finger went down: pause playback. */
        void onGestureStart();

        /** The playhead was dragged to {@code ms}. */
        void onScrub(long ms);

        /** The timeline was tapped (not dragged) at {@code ms}; the edges snap to 0 / the end. */
        void onTap(long ms);
    }

    private final Paint stripPaint = new Paint();
    private final Paint thumbPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint tintPaint = new Paint();
    private final Paint startPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint endPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tagTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint seamPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playheadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Rect src = new Rect();
    private final Rect dst = new Rect();
    private final Path path = new Path();

    private final float density;
    private final float pad;
    private final float edgeSnap;
    private final float touchSlop;
    private final String startLabel;
    private final String endLabel;

    private long durationMs;
    private long[] seams = new long[0];
    private Bitmap[] thumbs = new Bitmap[0];
    private long playheadMs;
    private long selStartMs = -1;
    private long selEndMs = -1;
    private Listener listener;

    // Gesture
    private float downX;
    private boolean scrubbing;

    public EditTimelineView(Context context) {
        this(context, null);
    }

    public EditTimelineView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        pad = 16 * density;
        edgeSnap = 12 * density;
        touchSlop = Math.max(ViewConfiguration.get(context).getScaledTouchSlop(), 8 * density);
        startLabel = context.getString(R.string.editor_marker_start);
        endLabel = context.getString(R.string.editor_marker_end);

        stripPaint.setColor(0xFF2C2C2C);
        tintPaint.setColor(0x55FF5252);
        startPaint.setColor(0xFF00E676);
        startPaint.setStrokeWidth(2 * density);
        endPaint.setColor(0xFFFF5252);
        endPaint.setStrokeWidth(2 * density);
        tagTextPaint.setColor(0xFF101010);
        tagTextPaint.setTextSize(11 * context.getResources().getDisplayMetrics().scaledDensity);
        tagTextPaint.setFakeBoldText(true);
        tagTextPaint.setTextAlign(Paint.Align.CENTER);
        seamPaint.setColor(0xFF40C4FF);
        playheadPaint.setColor(0xFFFFFFFF);
        playheadPaint.setStrokeWidth(2 * density);
        setContentDescription(context.getString(R.string.editor_timeline_desc));
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    /** Length of the edited timeline. */
    public void setDuration(long ms) {
        long d = Math.max(0, ms);
        if (d == durationMs) return;
        durationMs = d;
        invalidate();
    }

    /** Thumbnails spread evenly over the edited timeline. */
    public void setThumbnails(Bitmap[] frames) {
        this.thumbs = frames == null ? new Bitmap[0] : frames;
        invalidate();
    }

    public void setPlayhead(long ms) {
        if (ms == playheadMs) return;
        playheadMs = ms;
        invalidate();
    }

    /** The picked section; -1 for a marker that is not set. */
    public void setSelection(long startMs, long endMs) {
        if (startMs == selStartMs && endMs == selEndMs) return;
        selStartMs = startMs;
        selEndMs = endMs;
        invalidate();
    }

    /** Positions of the joins left by earlier removals. */
    public void setSeams(long[] positionsMs) {
        this.seams = positionsMs == null ? new long[0] : positionsMs;
        invalidate();
    }

    // ---------------------------------------------------------------- geometry

    private float trackLeft() {
        return pad;
    }

    private float trackRight() {
        return getWidth() - pad;
    }

    /** Room above the strip for the join markers and the playhead knob. */
    private float trackTop() {
        return 20 * density;
    }

    /** Room below the strip for the Start / End tags. */
    private float trackBottom() {
        return getHeight() - 24 * density;
    }

    private float xOf(long ms) {
        if (durationMs <= 0) return trackLeft();
        return trackLeft() + (trackRight() - trackLeft()) * ms / (float) durationMs;
    }

    private long msOf(float x) {
        float w = trackRight() - trackLeft();
        if (w <= 0 || durationMs <= 0) return 0;
        float f = (x - trackLeft()) / w;
        return (long) (Math.max(0f, Math.min(1f, f)) * durationMs);
    }

    /** Like {@link #msOf} but the last stretch at either end of the strip counts as exactly 0 / the end. */
    private long tapMsOf(float x) {
        if (x <= trackLeft() + edgeSnap) return 0;
        if (x >= trackRight() - edgeSnap) return durationMs;
        return msOf(x);
    }

    // ---------------------------------------------------------------- drawing

    @Override
    protected void onDraw(Canvas c) {
        if (durationMs <= 0) return;
        float l = trackLeft(), r = trackRight(), t = trackTop(), b = trackBottom();
        c.drawRect(l, t, r, b, stripPaint);

        // Thumbnails, evenly spread over the edited timeline
        int n = thumbs.length;
        for (int i = 0; i < n; i++) {
            Bitmap bmp = thumbs[i];
            if (bmp == null || bmp.isRecycled()) continue;
            int x0 = Math.round(l + (r - l) * i / n);
            int x1 = Math.round(l + (r - l) * (i + 1) / n);
            // centre-crop the frame into its slot
            float slotAspect = (x1 - x0) / Math.max(1f, b - t);
            float bmpAspect = bmp.getWidth() / (float) bmp.getHeight();
            if (bmpAspect > slotAspect) {
                int w = Math.round(bmp.getHeight() * slotAspect);
                src.set((bmp.getWidth() - w) / 2, 0, (bmp.getWidth() + w) / 2, bmp.getHeight());
            } else {
                int h = Math.round(bmp.getWidth() / slotAspect);
                src.set(0, (bmp.getHeight() - h) / 2, bmp.getWidth(), (bmp.getHeight() + h) / 2);
            }
            dst.set(x0, Math.round(t), x1, Math.round(b));
            c.drawBitmap(bmp, src, dst, thumbPaint);
        }

        // Join markers: a small triangle above the strip, not a gap in it
        for (long s : seams) {
            if (s <= 0 || s >= durationMs) continue;
            float x = xOf(s);
            path.reset();
            path.moveTo(x - 5 * density, 3 * density);
            path.lineTo(x + 5 * density, 3 * density);
            path.lineTo(x, t - 3 * density);
            path.close();
            c.drawPath(path, seamPaint);
        }

        // Section to remove: tint between the markers
        boolean hasStart = selStartMs >= 0;
        boolean hasEnd = hasStart && selEndMs >= 0;
        if (hasEnd) c.drawRect(xOf(selStartMs), t, xOf(selEndMs), b, tintPaint);

        // Playhead: thin line with a small knob
        float px = xOf(Math.max(0, Math.min(durationMs, playheadMs)));
        c.drawLine(px, t - 6 * density, px, b, playheadPaint);
        c.drawCircle(px, t - 10 * density, 4 * density, playheadPaint);

        // Markers on top: sharp thin lines with a small tag under the strip
        if (hasStart) drawMarker(c, xOf(selStartMs), startPaint, startLabel, true);
        if (hasEnd) drawMarker(c, xOf(selEndMs), endPaint, endLabel, false);
    }

    /** A thin line from the top of the strip to its tag; the tag hangs right of Start, left of End. */
    private void drawMarker(Canvas c, float x, Paint paint, String label, boolean tagRight) {
        float t = trackTop(), b = trackBottom();
        float tagTop = b + 2 * density;
        float tagH = 16 * density;
        float tagW = tagTextPaint.measureText(label) + 12 * density;
        c.drawLine(x, t - 2 * density, x, tagTop + 2 * density, paint);
        float left = tagRight ? x : x - tagW;
        left = Math.max(0, Math.min(getWidth() - tagW, left));
        rect.set(left, tagTop, left + tagW, tagTop + tagH);
        c.drawRoundRect(rect, 3 * density, 3 * density, paint);
        Paint.FontMetrics fm = tagTextPaint.getFontMetrics();
        c.drawText(label, rect.centerX(), rect.centerY() - (fm.ascent + fm.descent) / 2, tagTextPaint);
    }

    // ---------------------------------------------------------------- touch

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (durationMs <= 0) return false;
        float x = e.getX();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                downX = x;
                scrubbing = false;
                if (listener != null) listener.onGestureStart();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!scrubbing && Math.abs(x - downX) > touchSlop) scrubbing = true;
                if (scrubbing && listener != null) listener.onScrub(msOf(x));
                return true;
            case MotionEvent.ACTION_UP:
                if (!scrubbing && listener != null) listener.onTap(tapMsOf(x));
                scrubbing = false;
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                scrubbing = false;
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
