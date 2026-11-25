package com.jovoc.facecampresentationrecorder.contentplan;

import com.jovoc.facecampresentationrecorder.db.Slide;

import java.util.function.Predicate;

/**
 * Pure policy for putting the next video's topics on screen by itself: which video is next, and whether to switch,
 * only remember it, or do nothing.
 */
public final class PlanAutoTarget {

    public enum Switch { NONE, MARK_ONLY, SWITCH }

    private PlanAutoTarget() {
    }

    /**
     * The first day (date order, server order within a date) with {@code date >= today}, not recorded and with beats;
     * null when none or when the plan is not active and ready.
     */
    public static PlanWeek.Day target(PlanWeek pw, String today, Predicate<String> isRecorded) {
        if (pw == null || today == null || !pw.isActive() || !pw.isReady()) return null;
        PlanWeek.Day best = null;
        for (PlanWeek.Day d : pw.week.days) {
            if (d.date == null || d.date.compareTo(today) < 0) continue;
            if (d.beats.isEmpty()) continue;
            if (isRecorded != null && isRecorded.test(d.id)) continue;
            // strictly earlier only: the first of equal dates (server order) wins
            if (best == null || d.date.compareTo(best.date) < 0) best = d;
        }
        return best;
    }

    public static Switch decide(PlanWeek.Day target, String lastAutoDayId, String activeSetId, boolean recording,
                                boolean managerOpen, boolean setExists) {
        if (target == null || recording || managerOpen || !setExists) return Switch.NONE;
        if (target.id.equals(lastAutoDayId)) return Switch.NONE;
        if (Slide.planSetId(target.id).equals(activeSetId)) return Switch.MARK_ONLY;
        return Switch.SWITCH;
    }
}
