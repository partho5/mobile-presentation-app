package com.jovoc.facecampresentationrecorder.subtitle;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A bite-sized spoken phrase composed of words displayed together on screen.
 */
public final class SubtitlePhrase {
    public final long startMs;
    public final long endMs;
    public final List<SubtitleWord> words;
    public final String fullText;

    public SubtitlePhrase(long startMs, long endMs, List<SubtitleWord> words, String fullText) {
        this.startMs = Math.max(0, startMs);
        this.endMs = Math.max(this.startMs, endMs);
        this.words = words != null ? Collections.unmodifiableList(new ArrayList<>(words)) : Collections.emptyList();
        if (fullText != null) {
            this.fullText = fullText;
        } else {
            this.fullText = buildFullText(this.words);
        }
    }

    public SubtitlePhrase(List<SubtitleWord> words) {
        if (words == null || words.isEmpty()) {
            this.startMs = 0;
            this.endMs = 0;
            this.words = Collections.emptyList();
            this.fullText = "";
        } else {
            this.words = Collections.unmodifiableList(new ArrayList<>(words));
            this.startMs = this.words.get(0).startMs;
            this.endMs = this.words.get(this.words.size() - 1).endMs;
            this.fullText = buildFullText(this.words);
        }
    }

    private static String buildFullText(List<SubtitleWord> words) {
        if (words == null || words.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(words.get(i).text);
        }
        return sb.toString();
    }

    /**
     * Duration of this phrase in milliseconds.
     */
    public long durationMs() {
        return Math.max(0, endMs - startMs);
    }

    /**
     * Returns true if the given playhead time in milliseconds falls within this phrase's duration.
     */
    public boolean contains(long playheadMs) {
        return playheadMs >= startMs && playheadMs <= endMs;
    }

    /**
     * Finds the index of the active word in {@link #words} for the given playhead position.
     * Returns the index where {@code playheadMs >= word.startMs && playheadMs <= word.endMs}.
     * If within the phrase boundary but between words (in an inter-word gap), returns the nearest word index.
     * If outside the phrase boundary (before startMs or after endMs), returns -1.
     */
    public int findActiveWordIndex(long playheadMs) {
        if (words.isEmpty() || !contains(playheadMs)) {
            return -1;
        }

        // 1. Direct match: playhead is within [word.startMs, word.endMs]
        for (int i = 0; i < words.size(); i++) {
            SubtitleWord w = words.get(i);
            if (playheadMs >= w.startMs && playheadMs <= w.endMs) {
                return i;
            }
        }

        // 2. Inter-word gap within phrase boundary: find nearest word index
        int nearestIdx = 0;
        long minDistance = Long.MAX_VALUE;
        for (int i = 0; i < words.size(); i++) {
            SubtitleWord w = words.get(i);
            long dist;
            if (playheadMs < w.startMs) {
                dist = w.startMs - playheadMs;
            } else if (playheadMs > w.endMs) {
                dist = playheadMs - w.endMs;
            } else {
                return i;
            }
            if (dist < minDistance) {
                minDistance = dist;
                nearestIdx = i;
            }
        }
        return nearestIdx;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("startMs", startMs);
        json.put("endMs", endMs);
        json.put("fullText", fullText);
        JSONArray arr = new JSONArray();
        for (SubtitleWord w : words) {
            arr.put(w.toJson());
        }
        json.put("words", arr);
        return json;
    }

    public static SubtitlePhrase fromJson(JSONObject json) {
        if (json == null) return null;
        long startMs = json.optLong("startMs", 0);
        long endMs = json.optLong("endMs", 0);
        String fullText = json.optString("fullText", "");
        JSONArray arr = json.optJSONArray("words");
        List<SubtitleWord> wordList = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj != null) {
                    SubtitleWord w = SubtitleWord.fromJson(obj);
                    if (w != null) wordList.add(w);
                }
            }
        }
        return new SubtitlePhrase(startMs, endMs, wordList, fullText);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SubtitlePhrase that = (SubtitlePhrase) o;
        return startMs == that.startMs &&
                endMs == that.endMs &&
                Objects.equals(words, that.words) &&
                Objects.equals(fullText, that.fullText);
    }

    @Override
    public int hashCode() {
        return Objects.hash(startMs, endMs, words, fullText);
    }

    @Override
    public String toString() {
        return "SubtitlePhrase{" +
                "startMs=" + startMs +
                ", endMs=" + endMs +
                ", words=" + words.size() +
                ", fullText='" + fullText + '\'' +
                '}';
    }
}
