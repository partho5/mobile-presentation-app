package com.jovoc.facecampresentationrecorder.subtitle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SubtitleTrackTest {

    @Test
    public void testGroupingMaxFiveWords() {
        List<SubtitleWord> words = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            words.add(new SubtitleWord("word" + i, i * 200, (i * 200) + 150, true));
        }

        List<SubtitlePhrase> phrases = SubtitleTrack.groupWordsIntoPhrases(words);
        // 12 words with max 5 per phrase -> 5, 5, 2 -> 3 phrases
        assertEquals(3, phrases.size());
        assertEquals(5, phrases.get(0).words.size());
        assertEquals(5, phrases.get(1).words.size());
        assertEquals(2, phrases.get(2).words.size());
    }

    @Test
    public void testGroupingDurationLimit() {
        List<SubtitleWord> words = Arrays.asList(
                new SubtitleWord("One", 0, 1000, true),
                new SubtitleWord("Two", 1000, 2600, true), // potential duration 2600ms > 2500ms
                new SubtitleWord("Three", 2600, 3000, true)
        );

        List<SubtitlePhrase> phrases = SubtitleTrack.groupWordsIntoPhrases(words);
        assertEquals(2, phrases.size());
        assertEquals(1, phrases.get(0).words.size());
        assertEquals("One", phrases.get(0).fullText);
        assertEquals(2, phrases.get(1).words.size());
        assertEquals("Two Three", phrases.get(1).fullText);
    }

    @Test
    public void testGroupingWordGapLimit() {
        List<SubtitleWord> words = Arrays.asList(
                new SubtitleWord("First", 0, 500, true),
                // gap is 1000 - 500 = 500ms > 400ms max gap
                new SubtitleWord("Second", 1000, 1400, true)
        );

        List<SubtitlePhrase> phrases = SubtitleTrack.groupWordsIntoPhrases(words);
        assertEquals(2, phrases.size());
        assertEquals("First", phrases.get(0).fullText);
        assertEquals("Second", phrases.get(1).fullText);
    }

    @Test
    public void testGroupingSentencePunctuationBreaks() {
        List<SubtitleWord> words = Arrays.asList(
                new SubtitleWord("Hello.", 0, 300, true),
                new SubtitleWord("How", 350, 500, true),
                new SubtitleWord("are", 520, 650, true),
                new SubtitleWord("you?", 660, 850, true),
                new SubtitleWord("Good!", 900, 1100, true)
        );

        List<SubtitlePhrase> phrases = SubtitleTrack.groupWordsIntoPhrases(words);
        assertEquals(3, phrases.size());
        assertEquals("Hello.", phrases.get(0).fullText);
        assertEquals("How are you?", phrases.get(1).fullText);
        assertEquals("Good!", phrases.get(2).fullText);
    }

    @Test
    public void testFindPhraseAtBinarySearch() {
        List<SubtitleWord> words = Arrays.asList(
                new SubtitleWord("Intro.", 100, 500, true),
                new SubtitleWord("Body.", 1000, 1500, true),
                new SubtitleWord("Outro.", 2000, 2500, true)
        );
        SubtitleTrack track = new SubtitleTrack("en", words);
        assertEquals(3, track.phrases.size());

        assertNull(track.findPhraseAt(50));
        assertNotNull(track.findPhraseAt(100));
        assertEquals("Intro.", track.findPhraseAt(300).fullText);
        assertNull(track.findPhraseAt(700)); // gap
        assertEquals("Body.", track.findPhraseAt(1200).fullText);
        assertEquals("Outro.", track.findPhraseAt(2500).fullText);
        assertNull(track.findPhraseAt(2501));
    }

    @Test
    public void testParseFromJsonWithSubtitlesEnvelope() throws Exception {
        JSONObject sub = new JSONObject();
        sub.put("language", "es");
        JSONArray wordsArr = new JSONArray();

        JSONObject w1 = new JSONObject();
        w1.put("text", "Hola");
        w1.put("startMs", 0);
        w1.put("endMs", 400);
        wordsArr.put(w1);

        JSONObject w2 = new JSONObject();
        w2.put("text", "mundo");
        w2.put("startMs", 450);
        w2.put("endMs", 900);
        wordsArr.put(w2);

        sub.put("words", wordsArr);

        JSONObject root = new JSONObject();
        root.put("status", "READY");
        root.put("subtitles", sub);

        SubtitleTrack track = SubtitleTrack.fromJson(root);
        assertEquals("es", track.language);
        assertEquals(2, track.rawWords.size());
        assertEquals(1, track.phrases.size());
        assertEquals("Hola mundo", track.phrases.get(0).fullText);
    }
}
