package com.jovoc.facecampresentationrecorder.subtitle;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Objects;

/**
 * A single spoken token or word within a subtitle track, with microsecond-level timing boundaries.
 */
public final class SubtitleWord {
    public final String text;
    public final long startMs;
    public final long endMs;
    public final boolean isWord;

    public SubtitleWord(String text, long startMs, long endMs, boolean isWord) {
        this.text = text != null ? text : "";
        this.startMs = Math.max(0, startMs);
        this.endMs = Math.max(this.startMs, endMs);
        this.isWord = isWord;
    }

    public SubtitleWord(String text, long startMs, long endMs) {
        this(text, startMs, endMs, true);
    }

    /**
     * Duration of this word in milliseconds.
     */
    public long durationMs() {
        return Math.max(0, endMs - startMs);
    }

    /**
     * Serializes this word into JSON.
     */
    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("text", text);
        json.put("startMs", startMs);
        json.put("endMs", endMs);
        json.put("isWord", isWord);
        return json;
    }

    /**
     * Parses a word from a JSON object.
     * Supports both millisecond properties ("startMs", "endMs") and float second properties ("start", "end"),
     * as well as "isWord" boolean and "type" string ("word", "spacing", "audio_event").
     */
    public static SubtitleWord fromJson(JSONObject json) {
        if (json == null) return null;
        String text = json.optString("text", "");

        long startMs = json.optLong("startMs", -1);
        if (startMs < 0 && json.has("start")) {
            startMs = Math.round(json.optDouble("start", 0) * 1000.0);
        }

        long endMs = json.optLong("endMs", -1);
        if (endMs < 0 && json.has("end")) {
            endMs = Math.round(json.optDouble("end", 0) * 1000.0);
        }

        if (startMs < 0) startMs = 0;
        if (endMs < startMs) endMs = startMs;

        boolean isWord = true;
        if (json.has("isWord")) {
            isWord = json.optBoolean("isWord", true);
        } else if (json.has("type")) {
            String type = json.optString("type", "");
            isWord = !"spacing".equalsIgnoreCase(type) && !"audio_event".equalsIgnoreCase(type);
        }

        return new SubtitleWord(text, startMs, endMs, isWord);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SubtitleWord that = (SubtitleWord) o;
        return startMs == that.startMs &&
                endMs == that.endMs &&
                isWord == that.isWord &&
                Objects.equals(text, that.text);
    }

    @Override
    public int hashCode() {
        return Objects.hash(text, startMs, endMs, isWord);
    }

    @Override
    public String toString() {
        return "SubtitleWord{" +
                "text='" + text + '\'' +
                ", startMs=" + startMs +
                ", endMs=" + endMs +
                ", isWord=" + isWord +
                '}';
    }
}
