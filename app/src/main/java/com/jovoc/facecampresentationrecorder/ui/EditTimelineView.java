package com.jovoc.facecampresentationrecorder.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ScaleGestureDetector;
import android.view.ViewConfiguration;

import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.edit.EditTimelineMath;
import com.jovoc.facecampresentationrecorder.edit.TimelineZoom;

/**
 * The editor's timeline: a strip of video thumbnails over the edited result (what is left after
 * removals), a thin playhead, small markers above it at every join, and the section the user is
 * picking to remove: a sharp Start line and End line with a tint between them.
 * A tap places a marker, dragging scrubs, and a finger that lands on a marker drags that marker. Pinching (or {@link #zoomBy}) zooms in on part of the
 * timeline for exact points; while zoomed, dragging pans instead of scrubbing, and a time ruler
 * under the strip shows where you are. The view only draws and reports gestures; the editor
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

        /**
         * A Start ({@code isStart}) or End marker was dragged to {@code ms}. The screen applies its
         * own limits and answers through {@link #setSelection}.
         */
        void onMarkerDrag(boolean isStart, long ms);

        /** The marker drag ended. */
        void onMarkerDragEnd();

        /** The zoom level or the scroll position changed (user gesture or playhead follow). */
        void onViewChanged(float zoom, long startMs);
    }

    private final Paint stripPaint = new Paint();
    private final Paint thumbPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint tintPaint = new Paint();
    private final Paint startPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint endPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tagTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint seamPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playheadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Rect src = new Rect();
    private final Rect dst = new Rect();
    private final Path path = new Path();

    private final float density;
    private final float pad;
    private final float edgeSnap;
    private final float touchSlop;
    private final float minTickPx;
    private final ScaleGestureDetector scaleDetector;
    private final String startLabel;
    private final String endLabel;

    private long durationMs;
    private long[] seams = new long[0];
    private Bitmap[] thumbs = new Bitmap[0];
    private long playheadMs;
    private long selStartMs = -1;
    private long selEndMs = -1;
    private Listener listener;

    // Zoom: the visible window is durationMs / zoom long and starts at viewStartMs
    private float zoom = 1f;
    private long viewStartMs;

    // Gesture
    private float downX;
    private float lastX;
    private boolean scrubbing;
    private boolean panning;
    private boolean multiTouch;
    private boolean touching;

    // Marker drag: which marker the finger grabbed, and where on it (so it does not jump)
    private static final int NO_MARKER = 0, START_MARKER = 1, END_MARKER = 2;
    private int grabbed = NO_MARKER;
    private boolean markerDragging;
    private float grabOffsetPx;
    private float edgeZonePx;
    private float grabPx;
    private long lastEdgeFrameNs;
    private final Runnable edgePan = this::edgePanFrame;

    public EditTimelineView(Context context) {
        this(context, null);
    }

    public EditTimelineView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        pad = 16 * density;
        edgeSnap = 12 * density;
        grabPx = 24 * density;
        edgeZonePx = 40 * density;
        minTickPx = 56 * density;
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
        tickPaint.setColor(0xFF9E9E9E);
        tickPaint.setStrokeWidth(density);
        tickTextPaint.setColor(0xFFBDBDBD);
        tickTextPaint.setTextSize(10 * context.getResources().getDisplayMetrics().scaledDensity);
        tickTextPaint.setTextAlign(Paint.Align.CENTER);
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                float w = trackRight() - trackLeft();
                float frac = w <= 0 ? 0.5f : (d.getFocusX() - trackLeft()) / w;
                setZoomAround(zoom * d.getScaleFactor(), frac, true);
                return true;
            }
        });
        scaleDetector.setQuickScaleEnabled(false);
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
        applyView(zoom, viewStartMs, true);
        invalidate();
    }

    /** Restores the zoom level and scroll position (after rotation); does not call the listener. */
    public void setView(float zoomLevel, long startMs) {
        applyView(zoomLevel, startMs, false);
    }

    /** Zooms by {@code factor} (above 1 in, below 1 out), around the playhead when visible, else the middle. */
    public void zoomBy(float factor) {
        if (durationMs <= 0) return;
        long window = TimelineZoom.windowMs(durationMs, zoom);
        float frac = 0.5f;
        if (playheadMs >= viewStartMs && playheadMs <= viewStartMs + window) {
            frac = (playheadMs - viewStartMs) / (float) window;
        }
        setZoomAround(zoom * factor, frac, true);
    }

    /** Shows the whole timeline again. */
    public void resetZoom() {
        applyView(1f, 0, true);
    }

    private void setZoomAround(float newZoom, float focusFrac, boolean notify) {
        if (durationMs <= 0) return;
        float z = TimelineZoom.clampZoom(newZoom, durationMs);
        long start = TimelineZoom.zoomAround(viewStartMs, zoom, z, durationMs, focusFrac);
        applyView(z, start, notify);
    }

    /** Stores a clamped zoom / start; tells the listener only when {@code notify} and it changed. */
    private void applyView(float newZoom, long newStart, boolean notify) {
        float z = durationMs > 0 ? TimelineZoom.clampZoom(newZoom, durationMs) : Math.max(1f, newZoom);
        long start = durationMs > 0
                ? TimelineZoom.clampStart(newStart, TimelineZoom.windowMs(durationMs, z), durationMs)
                : Math.max(0, newStart);
        if (z == zoom && start == viewStartMs) return;
        zoom = z;
        viewStartMs = start;
        invalidate();
        if (notify && listener != null) listener.onViewChanged(zoom, viewStartMs);
    }

    private boolean isZoomed() {
        return zoom > 1.001f;
    }

    /** Thumbnails spread evenly over the edited timeline. */
    public void setThumbnails(Bitmap[] frames) {
        this.thumbs = frames == null ? new Bitmap[0] : frames;
        invalidate();
    }

    public void setPlayhead(long ms) {
        if (ms == playheadMs) return;
        playheadMs = ms;
        if (durationMs > 0 && isZoomed() && !touching) {
            long window = TimelineZoom.windowMs(durationMs, zoom);
            applyView(zoom, TimelineZoom.followStart(viewStartMs, window, ms, durationMs), true);
        }
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

    /** Height of the time ruler right under the strip. */
    private float rulerHeight() {
        return 16 * density;
    }

    /** Room below the strip for the ruler and the Start / End tags. */
    private float trackBottom() {
        return getHeight() - 24 * density - rulerHeight();
    }

    private long windowMs() {
        return TimelineZoom.windowMs(durationMs, zoom);
    }

    private float xOf(long ms) {
        if (durationMs <= 0) return trackLeft();
        return trackLeft() + TimelineZoom.pxAt(ms, trackRight() - trackLeft(), viewStartMs, windowMs());
    }

    private boolean visible(long ms) {
        return ms >= viewStartMs && ms <= viewStartMs + windowMs();
    }

    private long msOf(float x) {
        float w = trackRight() - trackLeft();
        if (w <= 0 || durationMs <= 0) return 0;
        return TimelineZoom.msAt(x - trackLeft(), w, viewStartMs, windowMs());
    }

    /**
     * Like {@link #msOf} but the last stretch at either end of the strip counts as exactly 0 / the
     * end, when that end is on screen.
     */
    private long tapMsOf(float x) {
        long window = windowMs();
        if (viewStartMs == 0 && x <= trackLeft() + edgeSnap) return 0;
        if (viewStartMs + window >= durationMs && x >= trackRight() - edgeSnap) return durationMs;
        return msOf(x);
    }

    // ---------------------------------------------------------------- drawing

    @Override
    protected void onDraw(Canvas c) {
        if (durationMs <= 0) return;
        float l = trackLeft(), r = trackRight(), t = trackTop(), b = trackBottom();
        c.drawRect(l, t, r, b, stripPaint);

        // Thumbnails: tiles of the frame's own shape laid along the whole (zoomed) strip, each
        // showing the thumbnail nearest to its time. Only the visible tiles are drawn.
        int n = thumbs.length;
        float h = Math.max(1f, b - t);
        if (n > 0) {
            float stripW = r - l;
            double totalW = (double) stripW * zoom;
            double scrollPx = totalW * viewStartMs / durationMs;
            Bitmap first = null;
            for (Bitmap bmp : thumbs) {
                if (bmp != null && !bmp.isRecycled()) { first = bmp; break; }
            }
            float tileW = first == null ? 0 : Math.max(8 * density, h * first.getWidth() / first.getHeight());
            if (tileW > 0) {
                c.save();
                c.clipRect(l, t, r, b);
                for (long k = (long) (scrollPx / tileW); l + k * (double) tileW - scrollPx < r; k++) {
                    float x0 = (float) (l + k * (double) tileW - scrollPx);
                    double centreMs = (k + 0.5) * tileW / totalW * durationMs;
                    int idx = (int) Math.max(0, Math.min(n - 1, (long) (centreMs * n / durationMs)));
                    Bitmap bmp = thumbs[idx];
                    if (bmp == null || bmp.isRecycled()) continue;
                    // centre-crop the frame into its tile
                    float slotAspect = tileW / h;
                    float bmpAspect = bmp.getWidth() / (float) bmp.getHeight();
                    if (bmpAspect > slotAspect) {
                        int w = Math.round(bmp.getHeight() * slotAspect);
                        src.set((bmp.getWidth() - w) / 2, 0, (bmp.getWidth() + w) / 2, bmp.getHeight());
                    } else {
                        int hh = Math.round(bmp.getWidth() / slotAspect);
                        src.set(0, (bmp.getHeight() - hh) / 2, bmp.getWidth(), (bmp.getHeight() + hh) / 2);
                    }
                    dst.set(Math.round(x0), Math.round(t), Math.round(x0 + tileW), Math.round(b));
                    c.drawBitmap(bmp, src, dst, thumbPaint);
                }
                c.restore();
            }
        }

        // Join markers: a small triangle above the strip, not a gap in it
        for (long s : seams) {
            if (s <= 0 || s >= durationMs || !visible(s)) continue;
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
        if (hasEnd) {
            float tx0 = Math.max(l, xOf(selStartMs)), tx1 = Math.min(r, xOf(selEndMs));
            if (tx1 > tx0) c.drawRect(tx0, t, tx1, b, tintPaint);
        }

        // Playhead: thin line with a small knob
        long ph = Math.max(0, Math.min(durationMs, playheadMs));
        if (visible(ph)) {
            float px = xOf(ph);
            c.drawLine(px, t - 6 * density, px, b, playheadPaint);
            c.drawCircle(px, t - 10 * density, 4 * density, playheadPaint);
        }

        drawRuler(c, l, r, b);

        // Markers on top: sharp thin lines with a small tag under the strip
        if (hasStart && visible(selStartMs)) drawMarker(c, xOf(selStartMs), startPaint, startLabel, true);
        if (hasEnd && visible(selEndMs)) drawMarker(c, xOf(selEndMs), endPaint, endLabel, false);

        // The marker being dragged: a thicker line and its exact time
        if (markerDragging) {
            boolean isStart = grabbed == START_MARKER;
            long ms = isStart ? selStartMs : selEndMs;
            if (ms >= 0 && visible(ms)) drawDragBubble(c, xOf(ms), isStart ? startPaint : endPaint, ms);
        }
    }

    private void drawDragBubble(Canvas c, float x, Paint paint, long ms) {
        float t = trackTop(), b = trackBottom();
        float old = paint.getStrokeWidth();
        paint.setStrokeWidth(4 * density);
        c.drawLine(x, t - 2 * density, x, b, paint);
        paint.setStrokeWidth(old);
        String text = EditTimelineMath.formatPrecise(ms);
        float w = tagTextPaint.measureText(text) + 14 * density;
        float h = 18 * density;
        float left = Math.max(0, Math.min(getWidth() - w, x - w / 2));
        rect.set(left, t + 4 * density, left + w, t + 4 * density + h);
        c.drawRoundRect(rect, 4 * density, 4 * density, paint);
        Paint.FontMetrics fm = tagTextPaint.getFontMetrics();
        c.drawText(text, rect.centerX(), rect.centerY() - (fm.ascent + fm.descent) / 2, tagTextPaint);
    }

    /** Time ruler under the strip: ticks at a spacing that suits the zoom, labelled m:ss(.s). */
    private void drawRuler(Canvas c, float l, float r, float b) {
        long window = windowMs();
        long step = TimelineZoom.tickStepMs(window, r - l, minTickPx);
        long end = viewStartMs + window;
        Paint.FontMetrics fm = tickTextPaint.getFontMetrics();
        float baseline = b + rulerHeight() - fm.descent - density;
        for (long tick = (viewStartMs + step - 1) / step * step; tick <= end; tick += step) {
            float x = xOf(tick);
            c.drawLine(x, b, x, b + 4 * density, tickPaint);
            c.drawText(TimelineZoom.formatTick(tick, step), x, baseline, tickTextPaint);
        }
    }

    /** A thin line from the top of the strip to its tag; the tag hangs right of Start, left of End. */
    private void drawMarker(Canvas c, float x, Paint paint, String label, boolean tagRight) {
        float t = trackTop(), b = trackBottom();
        float tagTop = b + rulerHeight() + 2 * density;
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
        scaleDetector.onTouchEvent(e);
        float x = e.getX();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                downX = x;
                lastX = x;
                scrubbing = false;
                panning = false;
                multiTouch = false;
                touching = true;
                markerDragging = false;
                grabbed = markerAt(x);
                if (grabbed != NO_MARKER) {
                    grabOffsetPx = x - xOf(grabbed == START_MARKER ? selStartMs : selEndMs);
                }
                if (listener != null) listener.onGestureStart();
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                // A second finger: this gesture is a pinch, never a tap, pan or scrub
                multiTouch = true;
                scrubbing = false;
                panning = false;
                endMarkerDrag(false);
                grabbed = NO_MARKER;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (multiTouch) return true;
                if (grabbed != NO_MARKER) {
                    lastX = x;
                    if (!markerDragging && Math.abs(x - downX) > touchSlop) {
                        markerDragging = true;
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    }
                    if (markerDragging) {
                        reportMarker();
                        updateEdgePan();
                        invalidate();
                    }
                    return true;
                }
                if (!scrubbing && !panning && Math.abs(x - downX) > touchSlop) {
                    if (isZoomed()) panning = true; else scrubbing = true;
                    lastX = x;
                }
                if (panning) {
                    // the content follows the finger
                    long window = windowMs();
                    long delta = Math.round((x - lastX) * window / (double) Math.max(1f, trackRight() - trackLeft()));
                    lastX = x;
                    applyView(zoom, viewStartMs - delta, true);
                } else if (scrubbing && listener != null) {
                    listener.onScrub(msOf(x));
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (markerDragging) {
                    endMarkerDrag(true);
                } else if (!multiTouch && !scrubbing && !panning && listener != null) {
                    listener.onTap(tapMsOf(x));
                }
                grabbed = NO_MARKER;
                scrubbing = false;
                panning = false;
                multiTouch = false;
                touching = false;
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                endMarkerDrag(false);
                grabbed = NO_MARKER;
                scrubbing = false;
                panning = false;
                multiTouch = false;
                touching = false;
                return true;
            default:
                return true;
        }
    }

    /** The marker within reach of a finger at {@code x} (the nearer one; the End on a tie). */
    private int markerAt(float x) {
        float dStart = selStartMs >= 0 && visible(selStartMs)
                ? Math.abs(x - xOf(selStartMs)) : Float.MAX_VALUE;
        float dEnd = selEndMs >= 0 && visible(selEndMs)
                ? Math.abs(x - xOf(selEndMs)) : Float.MAX_VALUE;
        if (dStart > grabPx && dEnd > grabPx) return NO_MARKER;
        return dStart < dEnd ? START_MARKER : END_MARKER;
    }

    /** Tells the screen where the grabbed marker now is (the finger, minus where it grabbed). */
    private void reportMarker() {
        if (listener != null) {
            listener.onMarkerDrag(grabbed == START_MARKER, tapMsOf(lastX - grabOffsetPx));
        }
    }

    private void endMarkerDrag(boolean notify) {
        removeCallbacks(edgePan);
        lastEdgeFrameNs = 0;
        if (!markerDragging) return;
        markerDragging = false;
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        if (notify && listener != null) listener.onMarkerDragEnd();
        invalidate();
    }

    /** While a zoomed-in drag sits near an edge of the strip, the timeline scrolls that way. */
    private void updateEdgePan() {
        if (!isZoomed() || edgePanSpeed() == 0f) {
            removeCallbacks(edgePan);
            lastEdgeFrameNs = 0;
        } else if (lastEdgeFrameNs == 0) {
            lastEdgeFrameNs = System.nanoTime();
            postOnAnimation(edgePan);
        }
    }

    /** -1..1: how hard the finger presses on the left (negative) or right (positive) edge zone. */
    private float edgePanSpeed() {
        float l = trackLeft(), r = trackRight();
        if (lastX < l + edgeZonePx) return -Math.min(1f, (l + edgeZonePx - lastX) / edgeZonePx);
        if (lastX > r - edgeZonePx) return Math.min(1f, (lastX - (r - edgeZonePx)) / edgeZonePx);
        return 0f;
    }

    private void edgePanFrame() {
        if (!markerDragging) {
            lastEdgeFrameNs = 0;
            return;
        }
        float speed = edgePanSpeed();
        long now = System.nanoTime();
        if (speed == 0f || !isZoomed()) {
            lastEdgeFrameNs = 0;
            return;
        }
        float seconds = Math.min(0.05f, (now - lastEdgeFrameNs) / 1e9f);
        lastEdgeFrameNs = now;
        // Up to one window width per 1.5 s at full pressure
        long delta = Math.round(speed * windowMs() * seconds / 1.5f);
        applyView(zoom, viewStartMs + delta, true);
        reportMarker();
        invalidate();
        postOnAnimation(edgePan);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(edgePan);
        super.onDetachedFromWindow();
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
