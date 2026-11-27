package com.jovoc.facecampresentationrecorder.contentplan;

import com.jovoc.facecampresentationrecorder.db.Slide;

import java.util.function.Predicate;

/**
 * Pure policy for putting the next video's topics on screen by itself: which video is next, and whether to switch,
 * only remember it, or do nothing (on a fresh launch, or on a resume after a fetched week).
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

    /**
     * Never while recording, with the Topic Manager open, or when the target's set is not saved yet; once per video
     * ({@code lastAutoDayId}). On a fresh launch any new target switches. On a resume ({@code freshLaunch} false) only
     * the owner's own instant video does: the owner just made it for the next recording, while an AI video keeps
     * waiting for the next fresh launch, so a resume never swaps the topics under a person who is mid-session. A
     * resume that does not switch also writes nothing down, so that video can still be picked up on a later launch.
     */
    public static Switch decide(PlanWeek.Day target, String lastAutoDayId, String activeSetId, boolean recording,
                                boolean managerOpen, boolean setExists, boolean freshLaunch) {
        if (target == null || recording || managerOpen || !setExists) return Switch.NONE;
        if (!freshLaunch && !target.isInstantIdea()) return Switch.NONE;
        if (target.id.equals(lastAutoDayId)) return Switch.NONE;
        if (Slide.planSetId(target.id).equals(activeSetId)) return Switch.MARK_ONLY;
        return Switch.SWITCH;
    }
}
