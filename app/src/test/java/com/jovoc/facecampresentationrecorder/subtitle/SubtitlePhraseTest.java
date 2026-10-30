package com.jovoc.facecampresentationrecorder.subtitle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class SubtitlePhraseTest {

    @Test
    public void testPhrasePropertiesAndFullText() {
        SubtitleWord w1 = new SubtitleWord("Hello", 100, 300, true);
        SubtitleWord w2 = new SubtitleWord("world", 350, 600, true);
        SubtitlePhrase phrase = new SubtitlePhrase(Arrays.asList(w1, w2));

        assertEquals(100L, phrase.startMs);
        assertEquals(600L, phrase.endMs);
        assertEquals(500L, phrase.durationMs());
        assertEquals("Hello world", phrase.fullText);
        assertEquals(2, phrase.words.size());
    }

    @Test
    public void testContains() {
        SubtitleWord w1 = new SubtitleWord("Hello", 100, 300, true);
        SubtitleWord w2 = new SubtitleWord("world", 350, 600, true);
        SubtitlePhrase phrase = new SubtitlePhrase(Arrays.asList(w1, w2));

        assertFalse(phrase.contains(50));
        assertTrue(phrase.contains(100));
        assertTrue(phrase.contains(200));
        assertTrue(phrase.contains(325));
        assertTrue(phrase.contains(600));
        assertFalse(phrase.contains(601));
    }

    @Test
    public void testFindActiveWordIndex() {
        SubtitleWord w1 = new SubtitleWord("First", 100, 300, true);
        SubtitleWord w2 = new SubtitleWord("Second", 400, 700, true);
        SubtitleWord w3 = new SubtitleWord("Third", 800, 1000, true);
        SubtitlePhrase phrase = new SubtitlePhrase(Arrays.asList(w1, w2, w3));

        // Outside phrase boundary -> -1
        assertEquals(-1, phrase.findActiveWordIndex(50));
        assertEquals(-1, phrase.findActiveWordIndex(1050));

        // Direct word hits
        assertEquals(0, phrase.findActiveWordIndex(100));
        assertEquals(0, phrase.findActiveWordIndex(200));
        assertEquals(0, phrase.findActiveWordIndex(300));

        assertEquals(1, phrase.findActiveWordIndex(400));
        assertEquals(1, phrase.findActiveWordIndex(550));
        assertEquals(1, phrase.findActiveWordIndex(700));

        assertEquals(2, phrase.findActiveWordIndex(800));
        assertEquals(2, phrase.findActiveWordIndex(900));
        assertEquals(2, phrase.findActiveWordIndex(1000));

        // Inter-word gap: [301..399]
        // at 320 -> closer to w1 end (300) than w2 start (400) -> 0
        assertEquals(0, phrase.findActiveWordIndex(320));
        // at 380 -> closer to w2 start (400) than w1 end (300) -> 1
        assertEquals(1, phrase.findActiveWordIndex(380));
    }

    @Test
    public void testJsonRoundtrip() throws Exception {
        SubtitleWord w1 = new SubtitleWord("Alpha", 10, 50, true);
        SubtitleWord w2 = new SubtitleWord("Beta", 60, 120, true);
        SubtitlePhrase phrase = new SubtitlePhrase(Arrays.asList(w1, w2));

        JSONObject json = phrase.toJson();
        SubtitlePhrase parsed = SubtitlePhrase.fromJson(json);

        assertNotNull(parsed);
        assertEquals(phrase, parsed);
        assertEquals(phrase.fullText, parsed.fullText);
        assertEquals(phrase.words.size(), parsed.words.size());
    }
}
