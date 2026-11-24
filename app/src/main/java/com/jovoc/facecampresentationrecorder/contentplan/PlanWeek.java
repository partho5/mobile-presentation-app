package com.jovoc.facecampresentationrecorder.contentplan;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The AI content plan of the current week as the server sends it (GET /public/v1/content-plan/week): an immutable
 * model with a lenient parser. A day with no id is skipped, a beat with no hook is dropped, and every missing text
 * becomes "". The texts are shown as they came; nothing here measures, cuts or changes them.
 */
public final class PlanWeek {

    public static final String STATUS_NONE = "NONE";
    public static final String STATUS_SETUP = "SETUP";
    public static final String STATUS_PROPOSED = "PROPOSED";
    public static final String STATUS_ACTIVE = "ACTIVE";

    public static final String WEEK_READY = "READY";

    public static final String KIND_PLANNED = "PLANNED";
    public static final String KIND_INSTANT_IDEA = "INSTANT_IDEA";

    /** One cue: the hint shown on screen while recording and the words spoken while it shows. */
    public static final class Beat {
        public final String hook;
        public final String say;

        Beat(String hook, String say) {
            this.hook = hook;
            this.say = say;
        }
    }

    /** One video of the week: an AI day, or the owner's own idea. */
    public static final class Day {
        public final String id;
        /** {@link #KIND_PLANNED} or {@link #KIND_INSTANT_IDEA}. */
        public final String kind;
        public final String date;
        /** Position in the week, or -1 (instant ideas have none). */
        public final int dayIndex;
        public final String title;
        /** One of the ten angle keys, or "" (instant ideas have none). */
        public final String angle;
        public final String why;
        public final List<Beat> beats;
        public final String closingLine;
        public final String status;
        public final int version;

        Day(String id, String kind, String date, int dayIndex, String title, String angle, String why,
            List<Beat> beats, String closingLine, String status, int version) {
            this.id = id;
            this.kind = kind;
            this.date = date;
            this.dayIndex = dayIndex;
            this.title = title;
            this.angle = angle;
            this.why = why;
            this.beats = Collections.unmodifiableList(beats);
            this.closingLine = closingLine;
            this.status = status;
            this.version = version;
        }

        public boolean isInstantIdea() {
            return KIND_INSTANT_IDEA.equals(kind);
        }

        /** True when the server has the day as recorded. */
        public boolean isRecorded() {
            return "RECORDED".equals(status);
        }
    }

    /** The week that contains {@code today}. */
    public static final class Week {
        public final String id;
        public final String weekStart;
        public final String weekEnd;
        public final String idea;
        public final String status;
        public final List<Day> days;

        Week(String id, String weekStart, String weekEnd, String idea, String status, List<Day> days) {
            this.id = id;
            this.weekStart = weekStart;
            this.weekEnd = weekEnd;
            this.idea = idea;
            this.status = status;
            this.days = Collections.unmodifiableList(days);
        }
    }

    public final String status;
    public final String today;
    public final String timezone;
    /** Null when the server has no week. */
    public final Week week;

    private PlanWeek(String status, String today, String timezone, Week week) {
        this.status = status;
        this.today = today;
        this.timezone = timezone;
        this.week = week;
    }

    public boolean isActive() {
        return STATUS_ACTIVE.equals(status);
    }

    /** True when the week exists and its videos are written. */
    public boolean isReady() {
        return week != null && WEEK_READY.equals(week.status);
    }

    /** Every day of {@code date} in the server's order (an AI day comes before an instant idea); never null. */
    public List<Day> daysFor(String date) {
        List<Day> out = new ArrayList<>();
        if (week == null || date == null) return out;
        for (Day d : week.days) {
            if (date.equals(d.date)) out.add(d);
        }
        return out;
    }

    /** Parses the server's body; throws when it is not a JSON object. */
    public static PlanWeek parse(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        Week week = null;
        JSONObject w = o.isNull("week") ? null : o.optJSONObject("week");
        if (w != null) {
            List<Day> days = new ArrayList<>();
            JSONArray arr = w.optJSONArray("days");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject d = arr.optJSONObject(i);
                    Day day = d == null ? null : parseDay(d);
                    if (day != null) days.add(day);
                }
            }
            week = new Week(text(w, "id"), text(w, "weekStart"), text(w, "weekEnd"), text(w, "idea"),
                    text(w, "status"), days);
        }
        return new PlanWeek(text(o, "status"), text(o, "today"), text(o, "timezone"), week);
    }

    private static Day parseDay(JSONObject d) {
        String id = text(d, "id");
        if (id.isEmpty()) return null;
        List<Beat> beats = new ArrayList<>();
        JSONArray arr = d.optJSONArray("beats");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject b = arr.optJSONObject(i);
                if (b == null) continue;
                String hook = text(b, "hook");
                if (hook.isEmpty()) continue;
                beats.add(new Beat(hook, text(b, "say")));
            }
        }
        String kind = KIND_INSTANT_IDEA.equals(text(d, "kind")) ? KIND_INSTANT_IDEA : KIND_PLANNED;
        int dayIndex = d.isNull("dayIndex") ? -1 : d.optInt("dayIndex", -1);
        return new Day(id, kind, text(d, "date"), dayIndex, text(d, "title"), text(d, "angle"),
                text(d, "why"), beats, text(d, "closingLine"), text(d, "status"), d.optInt("version", 0));
    }

    /** The text of {@code key}, or "" when missing or JSON null (optString would turn null into "null"). */
    private static String text(JSONObject o, String key) {
        if (o.isNull(key)) return "";
        return o.optString(key, "");
    }
}
