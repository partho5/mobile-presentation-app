package com.jovoc.facecampresentationrecorder.bgreplace;

import android.os.SystemClock;
import android.view.View;

import com.jovoc.facecampresentationrecorder.aiedit.capture.EventLog;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the face cam circle was on screen during a recording, over time.
 *
 * The screen recording already contains the face cam; after recording, the background is
 * replaced inside the circle only, so the processor needs the circle's position for every
 * frame. Whoever is currently drawing the circle (the in-app camera card, or the floating
 * overlay while the user is in another app) reports it here; only changes are stored.
 */
public final class BubbleTrack {

    /** Circle bounds in screen pixels from {@code timeMs} (since recording start) onwards. */
    public static final class Entry {
        public final long timeMs;
        public final boolean visible;
        public final int left;
        public final int top;
        public final int size;
        /** Width of the white ring drawn over the circle edge; never replaced. */
        public final int ringPx;

        Entry(long timeMs, boolean visible, int left, int top, int size, int ringPx) {
            this.timeMs = timeMs;
            this.visible = visible;
            this.left = left;
            this.top = top;
            this.size = size;
            this.ringPx = ringPx;
        }

        boolean sameGeometry(Entry o) {
            return o != null && visible == o.visible && left == o.left && top == o.top
                    && size == o.size && ringPx == o.ringPx;
        }
    }

    private static final Object LOCK = new Object();
    private static final List<Entry> entries = new ArrayList<>();
    private static long startElapsedMs = -1;

    private BubbleTrack() {}

    /** Called when the screen recorder starts producing frames. */
    public static void start() {
        synchronized (LOCK) {
            entries.clear();
            startElapsedMs = SystemClock.elapsedRealtime();
        }
    }

    public static boolean isActive() {
        synchronized (LOCK) {
            return startElapsedMs >= 0;
        }
    }

    /** Stops tracking and returns everything recorded. */
    public static List<Entry> stop() {
        synchronized (LOCK) {
            startElapsedMs = -1;
            return new ArrayList<>(entries);
        }
    }

    /** Reports the circle drawn by {@code circle}, or hidden if it is not on screen. */
    public static void report(View circle, int ringPx) {
        if (circle == null || !circle.isShown() || circle.getWidth() <= 0) {
            reportHidden();
            return;
        }
        int[] loc = new int[2];
        circle.getLocationOnScreen(loc);
        add(true, loc[0], loc[1], Math.min(circle.getWidth(), circle.getHeight()), ringPx);
    }

    public static void reportHidden() {
        add(false, 0, 0, 0, 0);
    }

    private static void add(boolean visible, int left, int top, int size, int ringPx) {
        synchronized (LOCK) {
            if (startElapsedMs < 0) return;
            Entry e = new Entry(SystemClock.elapsedRealtime() - startElapsedMs,
                    visible, left, top, size, ringPx);
            Entry last = entries.isEmpty() ? null : entries.get(entries.size() - 1);
            if (!e.sameGeometry(last)) entries.add(e);
            EventLog.facecamBox(visible, left, top, size);
        }
    }

    /** The entry in effect at {@code timeMs}, or null if nothing was reported yet. */
    public static Entry at(List<Entry> track, long timeMs) {
        Entry found = null;
        for (Entry e : track) {
            if (e.timeMs > timeMs) break;
            found = e;
        }
        // Frames before the first report use the first known position
        if (found == null && !track.isEmpty()) found = track.get(0);
        return found;
    }

    public static boolean hasVisible(List<Entry> track) {
        for (Entry e : track) if (e.visible) return true;
        return false;
    }
}
