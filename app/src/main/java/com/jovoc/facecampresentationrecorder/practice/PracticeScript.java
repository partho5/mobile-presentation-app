package com.jovoc.facecampresentationrecorder.practice;

import com.jovoc.facecampresentationrecorder.contentplan.PlanWeek;
import com.jovoc.facecampresentationrecorder.db.Slide;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What Practice walks through for one topic set: every enabled topic in recording order with the line to say under it,
 * then an optional closing line. Pure Java; plan texts and notes are never logged.
 */
public final class PracticeScript {

    /** One topic of the set as it will be recorded, with the line to say (null when nothing is written yet). */
    public static final class Step {
        public final int slideId;
        public final String type;
        public final String text;
        public final String imagePath;
        /** The note saved on the topic, else the plan's spoken line for the same hook, else null. */
        public final String line;

        Step(int slideId, String type, String text, String imagePath, String line) {
            this.slideId = slideId;
            this.type = type;
            this.text = text;
            this.imagePath = imagePath;
            this.line = line;
        }
    }

    public final List<Step> steps;
    /** The plan video's closing line, or null. */
    public final String closingLine;

    private PracticeScript(List<Step> steps, String closingLine) {
        this.steps = Collections.unmodifiableList(steps);
        this.closingLine = closingLine;
    }

    /**
     * @param slides  the set's topics in order (disabled ones are skipped: they are not recorded)
     * @param dayOrNull the plan video when this is a plan set and its week is still cached; its beats fill lines of
     *                  topics saved before notes existed (matched by hook text, so an edited topic gets none)
     * @param closingOrNull the closing line kept by the set index, used when the day is not known
     */
    public static PracticeScript build(List<Slide> slides, PlanWeek.Day dayOrNull, String closingOrNull) {
        List<Step> out = new ArrayList<>();
        if (slides != null) {
            for (Slide s : slides) {
                if (s == null || s.isDisabled()) continue;
                String line = nonEmpty(s.getSpeakerNote());
                if (line == null && dayOrNull != null) line = planLine(dayOrNull, s.getTextContent());
                out.add(new Step(s.getId(), s.getType(), s.getTextContent(), s.getImagePath(), line));
            }
        }
        String closing = dayOrNull != null ? nonEmpty(dayOrNull.closingLine) : null;
        if (closing == null) closing = nonEmpty(closingOrNull);
        return new PracticeScript(out, closing);
    }

    private static String planLine(PlanWeek.Day day, String text) {
        if (text == null) return null;
        String t = text.trim();
        for (PlanWeek.Beat b : day.beats) {
            if (b.hook != null && b.hook.trim().equals(t)) return nonEmpty(b.say);
        }
        return null;
    }

    private static String nonEmpty(String s) {
        return s == null || s.trim().isEmpty() ? null : s;
    }
}
