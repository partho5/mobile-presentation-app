package com.jovoc.facecampresentationrecorder.publish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

public class AiCaptionStatusTest {

    @Test
    public void testIsFinal() {
        assertTrue(new AiCaptionStatus(AiCaptionStatus.READY).isFinal());
        assertTrue(new AiCaptionStatus(AiCaptionStatus.NO_SPEECH).isFinal());
        assertTrue(new AiCaptionStatus(AiCaptionStatus.FAILED).isFinal());

        assertFalse(new AiCaptionStatus(AiCaptionStatus.PENDING).isFinal());
        assertFalse(new AiCaptionStatus(AiCaptionStatus.TRANSCRIBING).isFinal());
        assertFalse(new AiCaptionStatus(AiCaptionStatus.GENERATING).isFinal());
    }

    @Test
    public void testParseWithSubtitlesObject() throws Exception {
        JSONObject json = new JSONObject();
        json.put("status", "READY");

        JSONObject sub = new JSONObject();
        sub.put("language", "en");
        JSONArray words = new JSONArray();
        JSONObject w = new JSONObject();
        w.put("text", "Caption");
        w.put("startMs", 0);
        w.put("endMs", 300);
        words.put(w);
        sub.put("words", words);

        json.put("subtitles", sub);

        AiCaptionStatus status = AiCaptionStatus.parse(json.toString());
        assertEquals(AiCaptionStatus.READY, status.status);
        assertNotNull(status.subtitlesJson);
        assertTrue(status.subtitlesJson.contains("Caption"));
    }

    @Test
    public void testParseWithDirectWordsArray() throws Exception {
        JSONObject json = new JSONObject();
        json.put("status", "READY");
        JSONArray words = new JSONArray();
        JSONObject w = new JSONObject();
        w.put("text", "Direct");
        w.put("startMs", 100);
        w.put("endMs", 400);
        words.put(w);
        json.put("words", words);

        AiCaptionStatus status = AiCaptionStatus.parse(json.toString());
        assertEquals(AiCaptionStatus.READY, status.status);
        assertNotNull(status.subtitlesJson);
        assertTrue(status.subtitlesJson.contains("Direct"));
    }

    @Test
    public void testParseWithoutSubtitles() throws Exception {
        JSONObject json = new JSONObject();
        json.put("status", "TRANSCRIBING");

        AiCaptionStatus status = AiCaptionStatus.parse(json.toString());
        assertEquals(AiCaptionStatus.TRANSCRIBING, status.status);
        assertNull(status.subtitlesJson);
    }

    @Test(expected = JSONException.class)
    public void testParseMissingStatusThrows() throws Exception {
        AiCaptionStatus.parse("{}");
    }
}
