package com.jovoc.facecampresentationrecorder.aiedit.capture;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Collects the events of one recording and turns them into {@link EventLogData}. Pure (no Android
 * classes, the clock is passed in), not thread-safe: {@link EventLog} serialises the calls.
 * {@code nowMs} is {@code SystemClock.elapsedRealtime()}; positions are screen pixels of the master frame.
 */
final class EventLogBuilder {

    /** How long undo, redo, clear and typing count as activity. */
    static final long PULSE_MS = 300;
    /** Ink is wider than the line the finger drew; the stroke's box grows by this much per side (px). */
    static final float PEN_MARGIN_PX = 24f;
    /** Facecam keys closer than this share one entry (the newest place wins), so a drag stays small. */
    static final long FACECAM_MIN_GAP_MS = 100;

    private static final double PULSE_STRENGTH = 0.6;

    private final long startElapsedMs;
    private final int screenW;
    private final int screenH;
    private final String sessionId = UUID.randomUUID().toString();
    private final List<EventLogData.Activity> activity = new ArrayList<>();
    private final List<EventLogData.FacecamKey> facecam = new ArrayList<>();

    private boolean strokeOpen;
    private long strokeStartMs;
    private float sx0, sy0, sx1, sy1;
    private boolean boardShown;

    EventLogBuilder(long startElapsedMs, int screenW, int screenH) {
        this.startElapsedMs = startElapsedMs;
        this.screenW = Math.max(1, screenW);
        this.screenH = Math.max(1, screenH);
    }

    String sessionId() {
        return sessionId;
    }

    /** A finger went down on the board or the slide ink; closes a stroke that was never ended. */
    void strokeStart(long nowMs, float x, float y) {
        if (strokeOpen) strokeEnd(nowMs);
        strokeOpen = true;
        strokeStartMs = rel(nowMs);
        sx0 = sx1 = x;
        sy0 = sy1 = y;
    }

    /** The finger moved; grows the box of the open stroke. */
    void strokeMove(float x, float y) {
        if (!strokeOpen) return;
        sx0 = Math.min(sx0, x);
        sy0 = Math.min(sy0, y);
        sx1 = Math.max(sx1, x);
        sy1 = Math.max(sy1, y);
    }

    /** The finger came up (or the stroke was cancelled). */
    void strokeEnd(long nowMs) {
        if (!strokeOpen) return;
        strokeOpen = false;
        addBrush(strokeStartMs, Math.max(strokeStartMs, rel(nowMs)), sx0 - PEN_MARGIN_PX, sy0 - PEN_MARGIN_PX,
                sx1 + PEN_MARGIN_PX, sy1 + PEN_MARGIN_PX, 1.0);
    }

    /** A short activity over a screen rectangle (undo, redo, clear, board text). */
    void pulse(long nowMs, float left, float top, float right, float bottom) {
        long t = rel(nowMs);
        addBrush(t, t + PULSE_MS, left, top, right, bottom, PULSE_STRENGTH);
    }

    /** The board slid in or out; repeats of the same state are ignored. */
    void boardShown(long nowMs, boolean shown) {
        if (shown == boardShown) return;
        boardShown = shown;
        long t = rel(nowMs);
        activity.add(marker(shown ? EventLogData.BOARD_SHOW : EventLogData.BOARD_HIDE, t));
    }

    /** The shown slide changed. */
    void slideChanged(long nowMs) {
        activity.add(marker(EventLogData.SLIDE_CHANGE, rel(nowMs)));
    }

    /** The facecam circle, as fractions of the master frame (radius of the width). */
    void facecam(long nowMs, double cx, double cy, double r) {
        long t = Math.max(0, rel(nowMs));
        EventLogData.FacecamKey key = new EventLogData.FacecamKey(t, clamp01(cx), clamp01(cy), clamp01(r));
        if (!facecam.isEmpty()) {
            int last = facecam.size() - 1;
            EventLogData.FacecamKey prev = facecam.get(last);
            if (prev.samePlace(key)) return;
            if (t - prev.tMs < FACECAM_MIN_GAP_MS && last > 0) {
                facecam.set(last, key.at(prev.tMs));
                return;
            }
        }
        facecam.add(key);
    }

    /** The facecam circle as its screen box (left, top, side in px); a hidden circle adds nothing. */
    void facecamBox(long nowMs, boolean visible, int left, int top, int size) {
        if (!visible || size <= 0) return;
        facecam(nowMs, (left + size / 2.0) / screenW, (top + size / 2.0) / screenH, size / 2.0 / screenW);
    }

    /** Ends the recording: merges strokes, clamps to the duration, numbers the entries. */
    EventLogData finish(long nowMs) {
        strokeEnd(nowMs);
        long duration = Math.max(0, rel(nowMs));
        List<EventLogData.Activity> clamped = new ArrayList<>();
        for (EventLogData.Activity a : activity) {
            if (a.startMs > duration) continue;
            clamped.add(a.endMs > duration ? a.with(a.id, a.startMs, duration) : a);
        }
        List<EventLogData.FacecamKey> keys = new ArrayList<>();
        for (EventLogData.FacecamKey k : facecam) {
            if (k.tMs > duration) break;
            keys.add(keys.isEmpty() ? k.at(0) : k);
        }
        return new EventLogData(sessionId, screenW, screenH, duration,
                EventLogData.mergeAndNumber(clamped), keys);
    }

    // ---------------------------------------------------------------- helpers

    private long rel(long nowMs) {
        return Math.max(0, nowMs - startElapsedMs);
    }

    private EventLogData.Activity marker(String type, long t) {
        return new EventLogData.Activity("", type, t, t, 0, 0, 1, 1, 1.0);
    }

    private void addBrush(long startMs, long endMs, float left, float top, float right, float bottom,
                          double strength) {
        double x0 = clamp01(left / (double) screenW);
        double y0 = clamp01(top / (double) screenH);
        double x1 = clamp01(right / (double) screenW);
        double y1 = clamp01(bottom / (double) screenH);
        activity.add(new EventLogData.Activity("", EventLogData.BRUSH, startMs, endMs,
                x0, y0, x1 - x0, y1 - y0, strength));
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
