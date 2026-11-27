package com.jovoc.facecampresentationrecorder.practice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jovoc.facecampresentationrecorder.contentplan.PlanWeek;
import com.jovoc.facecampresentationrecorder.db.Slide;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class PracticeScriptTest {

    private static Slide slide(int id, String text, String note, boolean disabled) {
        Slide s = new Slide(Slide.TYPE_TEXT, id, text, null);
        s.setId(id);
        s.setSpeakerNote(note);
        s.setDisabled(disabled);
        return s;
    }

    private static PlanWeek.Day day(String closing) throws Exception {
        PlanWeek pw = PlanWeek.parse("{\"week\":{\"days\":[{\"id\":\"d1\",\"closingLine\":\"" + closing + "\","
                + "\"beats\":[{\"hook\":\"Hook one\",\"say\":\"Say one\"},{\"hook\":\"Hook two\",\"say\":\"Say two\"}]}]}}");
        return pw.week.days.get(0);
    }

    @Test
    public void disabledTopicsAreSkippedInOrder() {
        PracticeScript p = PracticeScript.build(Arrays.asList(
                slide(1, "a", null, false), slide(2, "b", null, true), slide(3, "c", null, false)), null, null);
        assertEquals(2, p.steps.size());
        assertEquals(1, p.steps.get(0).slideId);
        assertEquals(3, p.steps.get(1).slideId);
        assertNull(p.steps.get(0).line);
        assertNull(p.closingLine);
    }

    @Test
    public void ownNoteWinsOverThePlanLine() throws Exception {
        PracticeScript p = PracticeScript.build(Collections.singletonList(slide(1, "Hook one", "Mine", false)),
                day("Bye"), null);
        assertEquals("Mine", p.steps.get(0).line);
    }

    @Test
    public void planLineMatchesByHookAndEditedTopicHasNone() throws Exception {
        List<Slide> slides = Arrays.asList(slide(1, " Hook two ", null, false), slide(2, "Hook one, edited", "", false));
        PracticeScript p = PracticeScript.build(slides, day("Bye"), null);
        assertEquals("Say two", p.steps.get(0).line);
        assertNull(p.steps.get(1).line);
        assertEquals("Bye", p.closingLine);
    }

    @Test
    public void closingFallsBackToTheIndexWhenTheDayIsUnknown() throws Exception {
        assertEquals("Kept", PracticeScript.build(Collections.emptyList(), null, "Kept").closingLine);
        assertEquals("Kept", PracticeScript.build(Collections.emptyList(), day(""), "Kept").closingLine);
        assertNull(PracticeScript.build(Collections.emptyList(), null, " ").closingLine);
        assertTrue(PracticeScript.build(null, null, null).steps.isEmpty());
    }
}
