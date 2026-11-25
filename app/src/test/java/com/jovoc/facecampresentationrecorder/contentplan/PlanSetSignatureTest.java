package com.jovoc.facecampresentationrecorder.contentplan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.jovoc.facecampresentationrecorder.db.Slide;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class PlanSetSignatureTest {

    private static Slide slide(String type, String text, String image, boolean disabled, int id, int order) {
        Slide s = new Slide(type, order, text, image);
        s.setId(id);
        s.setDisabled(disabled);
        return s;
    }

    private static List<Slide> base() {
        return new ArrayList<>(Arrays.asList(
                slide("TEXT", "one", null, false, 1, 0),
                slide("TEXT", "two", null, false, 2, 1)));
    }

    @Test
    public void sameSlidesSameHash() {
        String h = PlanSetSignature.of(base());
        assertEquals(h, PlanSetSignature.of(base()));
        assertEquals(64, h.length());
        assertTrue(h.matches("[0-9a-f]{64}"));
    }

    @Test
    public void changesOnTextTypeImageDisabledOrder() {
        String h = PlanSetSignature.of(base());

        List<Slide> t = base();
        t.get(1).setTextContent("two!");
        assertNotEquals(h, PlanSetSignature.of(t));

        List<Slide> ty = base();
        ty.get(0).setType("IMAGE");
        assertNotEquals(h, PlanSetSignature.of(ty));

        List<Slide> im = base();
        im.get(0).setImagePath("/x.png");
        assertNotEquals(h, PlanSetSignature.of(im));

        List<Slide> di = base();
        di.get(0).setDisabled(true);
        assertNotEquals(h, PlanSetSignature.of(di));

        List<Slide> or = base();
        java.util.Collections.reverse(or);
        assertNotEquals(h, PlanSetSignature.of(or));

        List<Slide> fewer = base();
        fewer.remove(1);
        assertNotEquals(h, PlanSetSignature.of(fewer));
    }

    @Test
    public void nullTextEqualsEmptyText() {
        List<Slide> a = new ArrayList<>(Arrays.asList(slide("TEXT", null, null, false, 1, 0)));
        List<Slide> b = new ArrayList<>(Arrays.asList(slide("TEXT", "", "", false, 1, 0)));
        assertEquals(PlanSetSignature.of(a), PlanSetSignature.of(b));
    }

    @Test
    public void idsAndOrderIndexesAreIgnored() {
        List<Slide> a = new ArrayList<>(Arrays.asList(
                slide("TEXT", "one", null, false, 1, 0), slide("TEXT", "two", null, false, 2, 1)));
        List<Slide> b = new ArrayList<>(Arrays.asList(
                slide("TEXT", "one", null, false, 77, 5), slide("TEXT", "two", null, false, 99, 9)));
        assertEquals(PlanSetSignature.of(a), PlanSetSignature.of(b));
    }
}
