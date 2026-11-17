package com.jovoc.facecampresentationrecorder.aiedit.capture;

import com.jovoc.facecampresentationrecorder.edit.EditProject;

import java.util.ArrayList;
import java.util.List;

/**
 * Moves an event log through the manual editor's kept ranges, so it describes the edited video
 * ({@code *_edit*.mp4}) the AI edit will actually plan (spec section 9: the server never sees the
 * manual edit). Pure; times are milliseconds.
 */
public final class EventLogRemap {

    private EventLogRemap() {}

    /** The log mapped through {@code project}'s cuts (a project that cuts nothing returns the log unchanged). */
    public static EventLogData apply(EventLogData log, EditProject project) {
        return apply(log, project.renderRanges(log.durationMs));
    }

    /**
     * The log mapped through {@code kept} (sorted, non-overlapping stretches of the master). Strokes are
     * clipped to the kept parts and merged again where a cut brought them closer than 400 ms; markers inside
     * a cut vanish; the facecam keeps the place it had at the start of each kept stretch.
     */
    public static EventLogData apply(EventLogData log, List<EditProject.Range> kept) {
        if (kept.size() == 1 && kept.get(0).startMs <= 0 && kept.get(0).endMs >= log.durationMs) return log;
        List<EventLogData.Activity> activity = new ArrayList<>();
        List<EventLogData.FacecamKey> facecam = new ArrayList<>();
        long before = 0;
        for (EditProject.Range r : kept) {
            for (EventLogData.Activity a : log.activity) {
                EventLogData.Activity m = clip(a, r, before);
                if (m != null) activity.add(m);
            }
            EventLogData.FacecamKey held = null;
            for (EventLogData.FacecamKey k : log.facecam) {
                if (k.tMs <= r.startMs) {
                    held = k;
                } else if (k.tMs < r.endMs) {
                    if (held != null) {
                        addKey(facecam, held.at(before));
                        held = null;
                    }
                    addKey(facecam, k.at(before + k.tMs - r.startMs));
                }
            }
            if (held != null) addKey(facecam, held.at(before));
            before += r.lengthMs();
        }
        return new EventLogData(log.sessionId, log.width, log.height, before,
                EventLogData.mergeAndNumber(activity), facecam);
    }

    /** The part of {@code a} inside {@code r}, placed at {@code before} on the output; null when none of it is kept. */
    private static EventLogData.Activity clip(EventLogData.Activity a, EditProject.Range r, long before) {
        if (a.startMs == a.endMs) {
            if (a.startMs < r.startMs || a.startMs >= r.endMs) return null;
            long t = before + a.startMs - r.startMs;
            return new EventLogData.Activity(a.id, a.type, t, t, a.x, a.y, a.w, a.h, a.strength);
        }
        long s = Math.max(a.startMs, r.startMs);
        long e = Math.min(a.endMs, r.endMs);
        if (e <= s) return null;
        return new EventLogData.Activity(a.id, a.type, before + s - r.startMs, before + e - r.startMs,
                a.x, a.y, a.w, a.h, a.strength);
    }

    /** Adds a key unless the circle has not moved since the last one; a key at the same time replaces it. */
    private static void addKey(List<EventLogData.FacecamKey> out, EventLogData.FacecamKey k) {
        if (!out.isEmpty()) {
            EventLogData.FacecamKey last = out.get(out.size() - 1);
            if (last.tMs == k.tMs) {
                out.set(out.size() - 1, k);
                return;
            }
            if (last.samePlace(k)) return;
        }
        out.add(k);
    }
}
