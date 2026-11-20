package com.jovoc.facecampresentationrecorder.aiedit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.Capabilities;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/** The request's {@code capabilities.text_motions}: the legacy five plus only what the owner ticked on a device. */
public class CapabilitiesTest {

    /** Every motion id the phone's animator can draw, in the server's naming (entry, hold_<id>, exit_<id>). */
    private static final List<String> DRAWABLE = Arrays.asList(
            "fade", "slide_left", "slide_right", "slide_top", "slide_bottom", "typewriter", "wipe", "drop_bounce",
            "blur_in", "flip", "glitch",
            "hold_float", "hold_pulse", "hold_shake", "hold_shimmer", "hold_neon_flicker",
            "exit_fade", "exit_slide_out", "exit_shrink", "exit_pop_out", "exit_typewriter_delete", "exit_wipe_out");

    private static List<String> sent(Capabilities c) throws Exception {
        JSONArray a = c.toJson().getJSONArray("text_motions");
        List<String> r = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) r.add(a.getString(i));
        return r;
    }

    @Test
    public void nothingTickedSendsOnlyTheLegacyFive() throws Exception {
        assertEquals(Arrays.asList("pop", "stamp", "slide_up", "word_by_word", "sweep"), sent(Capabilities.current(
                Arrays.<String>asList())));
    }

    @Test
    public void theBuildSendsTheLegacyFivePlusEveryTickedMotionOnce() throws Exception {
        List<String> expected = new ArrayList<>(Capabilities.LEGACY_TEXT_MOTIONS);
        expected.addAll(Capabilities.DEVICE_PASSED_TEXT_MOTIONS);
        assertEquals(expected, sent(Capabilities.current()));
        assertEquals(27, expected.size());
        assertEquals(new java.util.HashSet<>(expected).size(), expected.size());
        assertEquals(new java.util.HashSet<>(DRAWABLE), new java.util.HashSet<>(Capabilities.DEVICE_PASSED_TEXT_MOTIONS));
    }

    @Test
    public void sentListsMatchWhatTheServerValidationAccepts() throws Exception {
        Pattern name = Pattern.compile("^[a-z0-9_]{1,64}$");
        Capabilities c = Capabilities.current(TestContract.load());
        JSONObject j = c.toJson();
        for (String key : new String[]{"text_motions", "text_features", "text_styles"}) {
            JSONArray a = j.getJSONArray(key);
            assertTrue(key, a.length() <= 50);
            for (int i = 0; i < a.length(); i++) assertTrue(a.getString(i), name.matcher(a.getString(i)).matches());
        }
        assertEquals(27, j.getJSONArray("text_motions").length());
        assertEquals(7, j.getJSONArray("text_features").length());
        assertEquals(16, j.getJSONArray("text_styles").length());
    }

    @Test
    public void aListLongerThanFiftyIsCutToTheFirstFiftyAndABadNameIsNeverSent() throws Exception {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 70; i++) many.add("m" + i);
        List<String> withBad = new ArrayList<>(Arrays.asList("Bad-Name", "", "ok_id"));
        withBad.addAll(many);
        Capabilities c = new Capabilities(Arrays.<String>asList(), Arrays.<String>asList(), withBad,
                Arrays.<String>asList());
        List<String> out = sent(c);
        assertEquals(50, out.size());
        // legacy five first, then the ticked ids in order; the invalid names are skipped
        assertEquals(Capabilities.LEGACY_TEXT_MOTIONS, out.subList(0, 5));
        assertEquals("ok_id", out.get(5));
        assertEquals("m0", out.get(6));
        assertEquals("m43", out.get(49));
    }

    @Test
    public void onlyTickedIdsAreAddedAndLegacyOnesStayFirstWithoutRepeats() throws Exception {
        Capabilities c = Capabilities.current(Arrays.asList("fade", "hold_float", "exit_fade", "pop"));
        assertEquals(Arrays.asList("pop", "stamp", "slide_up", "word_by_word", "sweep", "fade", "hold_float", "exit_fade"),
                sent(c));
        assertTrue(c.canMove("fade"));
        assertTrue(c.canMove("exit_fade"));
        assertFalse(c.canMove("wipe"));
        assertFalse(c.canMove("hold_shimmer"));
    }

    @Test
    public void theOtherCapabilityListsAreUnchanged() throws Exception {
        JSONObject j = Capabilities.current().toJson();
        assertEquals(Capabilities.current().getEffects().size(), j.getJSONArray("effects").length());
        assertEquals(6, j.getJSONArray("text_styles").length());
    }

    @Test
    public void everyTickedIdIsOneThePhoneDrawsAndTheServerAccepts() throws Exception {
        Pattern name = Pattern.compile("^[a-z0-9_]{1,64}$");
        List<String> all = sent(Capabilities.current());
        assertTrue(all.size() <= 50);
        for (String id : all) assertTrue(id, name.matcher(id).matches());
        for (String id : Capabilities.DEVICE_PASSED_TEXT_MOTIONS) assertTrue(id, DRAWABLE.contains(id));
    }
}
