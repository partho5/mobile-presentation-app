package com.jovoc.facecampresentationrecorder.subtitle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class SubtitleWordTest {

    @Test
    public void testBasicPropertiesAndDuration() {
        SubtitleWord word = new SubtitleWord("Hello", 500, 1200, true);
        assertEquals("Hello", word.text);
        assertEquals(500L, word.startMs);
        assertEquals(1200L, word.endMs);
        assertTrue(word.isWord);
        assertEquals(700L, word.durationMs());
    }

    @Test
    public void testNegativeOrInvertedTimestampsClamped() {
        SubtitleWord word = new SubtitleWord("World", -100, 50, true);
        assertEquals(0L, word.startMs);
        assertEquals(50L, word.endMs);

        SubtitleWord inverted = new SubtitleWord("Test", 500, 200, true);
        assertEquals(500L, inverted.startMs);
        assertEquals(500L, inverted.endMs);
        assertEquals(0L, inverted.durationMs());
    }

    @Test
    public void testJsonRoundtrip() throws Exception {
        SubtitleWord word = new SubtitleWord("Antigravity", 1000, 1600, true);
        JSONObject json = word.toJson();
        assertEquals("Antigravity", json.getString("text"));
        assertEquals(1000L, json.getLong("startMs"));
        assertEquals(1600L, json.getLong("endMs"));
        assertTrue(json.getBoolean("isWord"));

        SubtitleWord parsed = SubtitleWord.fromJson(json);
        assertNotNull(parsed);
        assertEquals(word, parsed);
    }

    @Test
    public void testParseFromJsonWithSecondFloats() throws Exception {
        JSONObject json = new JSONObject();
        json.put("text", "SecondFloat");
        json.put("start", 1.25);
        json.put("end", 1.85);
        json.put("type", "word");

        SubtitleWord parsed = SubtitleWord.fromJson(json);
        assertNotNull(parsed);
        assertEquals("SecondFloat", parsed.text);
        assertEquals(1250L, parsed.startMs);
        assertEquals(1850L, parsed.endMs);
        assertTrue(parsed.isWord);
    }

    @Test
    public void testSpacingAndAudioEventsMarkedNotWord() throws Exception {
        JSONObject spacing = new JSONObject();
        spacing.put("text", " ");
        spacing.put("startMs", 100);
        spacing.put("endMs", 200);
        spacing.put("type", "spacing");
        assertFalse(SubtitleWord.fromJson(spacing).isWord);

        JSONObject audioEvent = new JSONObject();
        audioEvent.put("text", "(applause)");
        audioEvent.put("startMs", 200);
        audioEvent.put("endMs", 800);
        audioEvent.put("type", "audio_event");
        assertFalse(SubtitleWord.fromJson(audioEvent).isWord);
    }
}
