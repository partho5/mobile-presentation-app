package com.jovoc.facecampresentationrecorder.subtitle;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Encapsulates the entire subtitle stream for a video, containing raw synchronized words
 * and grouped phrases for on-screen karaoke rendering.
 */
public final class SubtitleTrack {
    public static final int MAX_WORDS_PER_PHRASE = 5;
    public static final long MAX_PHRASE_DURATION_MS = 2500L;
    public static final long MAX_WORD_GAP_MS = 400L;

    public final String language;
    public final List<SubtitleWord> rawWords;
    public final List<SubtitlePhrase> phrases;

    public SubtitleTrack(String language, List<SubtitleWord> rawWords, List<SubtitlePhrase> phrases) {
        this.language = language != null && !language.isEmpty() ? language : "en";
        this.rawWords = rawWords != null ? Collections.unmodifiableList(new ArrayList<>(rawWords)) : Collections.emptyList();
        this.phrases = phrases != null ? Collections.unmodifiableList(new ArrayList<>(phrases)) : Collections.emptyList();
    }

    public SubtitleTrack(String language, List<SubtitleWord> rawWords) {
        this(language, rawWords, groupWordsIntoPhrases(rawWords));
    }

    /**
     * Groups a stream of synchronized words into readable, bite-sized spoken phrases.
     * Criteria:
     * - Maximum 5 words per phrase.
     * - Maximum duration of 2500ms.
     * - Maximum pause/gap between consecutive words of 400ms.
     * - Sentence terminal punctuation (. ! ? etc.) ends a phrase immediately.
     */
    public static List<SubtitlePhrase> groupWordsIntoPhrases(List<SubtitleWord> words) {
        if (words == null || words.isEmpty()) {
            return Collections.emptyList();
        }

        List<SubtitlePhrase> phrases = new ArrayList<>();
        List<SubtitleWord> current = new ArrayList<>();

        for (SubtitleWord w : words) {
            if (w == null || !w.isWord || w.text.trim().isEmpty()) {
                continue;
            }

            if (current.isEmpty()) {
                current.add(w);
                continue;
            }

            SubtitleWord lastWord = current.get(current.size() - 1);
            SubtitleWord firstWord = current.get(0);

            long potentialDuration = w.endMs - firstWord.startMs;
            long gap = w.startMs - lastWord.endMs;

            boolean tooManyWords = current.size() >= MAX_WORDS_PER_PHRASE;
            boolean tooLongDuration = potentialDuration > MAX_PHRASE_DURATION_MS;
            boolean gapTooLarge = gap > MAX_WORD_GAP_MS;
            boolean lastHadPunctuation = endsWithSentencePunctuation(lastWord.text);

            if (tooManyWords || tooLongDuration || gapTooLarge || lastHadPunctuation) {
                phrases.add(new SubtitlePhrase(current));
                current = new ArrayList<>();
            }
            current.add(w);
        }

        if (!current.isEmpty()) {
            phrases.add(new SubtitlePhrase(current));
        }

        return Collections.unmodifiableList(phrases);
    }

    /**
     * Checks if a word ends with terminal sentence punctuation across major world languages.
     */
    static boolean endsWithSentencePunctuation(String text) {
        if (text == null) return false;
        String trimmed = text.trim();
        while (trimmed.endsWith("\"") || trimmed.endsWith("'") || trimmed.endsWith("”")
                || trimmed.endsWith("’") || trimmed.endsWith(")") || trimmed.endsWith("»")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        return trimmed.endsWith(".") || trimmed.endsWith("!") || trimmed.endsWith("?")
                || trimmed.endsWith("\u0964") // Bengali / Hindi Dari (।)
                || trimmed.endsWith("\u061F") // Arabic question mark (؟)
                || trimmed.endsWith("\u3002") // CJK full stop (。)
                || trimmed.endsWith("\uFF01") // CJK fullwidth exclamation (！)
                || trimmed.endsWith("\uFF1F"); // CJK fullwidth question mark (？)
    }

    /**
     * Performs a binary search across ordered phrases to find the phrase containing the given playhead time.
     * Returns null if no phrase covers the given playhead.
     */
    public SubtitlePhrase findPhraseAt(long playheadMs) {
        if (phrases.isEmpty() || playheadMs < 0) {
            return null;
        }

        int low = 0;
        int high = phrases.size() - 1;

        while (low <= high) {
            int mid = (low + high) >>> 1;
            SubtitlePhrase phrase = phrases.get(mid);
            if (phrase.contains(playheadMs)) {
                return phrase;
            } else if (playheadMs < phrase.startMs) {
                high = mid - 1;
            } else {
                low = mid + 1;
            }
        }

        return null;
    }

    /**
     * Finds the index of the active word in the specified phrase for the given playhead position.
     * Returns -1 if phrase is null or playhead is out of phrase boundaries.
     */
    public int getActiveWordIndex(SubtitlePhrase phrase, long playheadMs) {
        if (phrase == null) return -1;
        return phrase.findActiveWordIndex(playheadMs);
    }

    public boolean isEmpty() {
        return phrases.isEmpty();
    }

    /**
     * Parses a subtitle track from a JSON string.
     */
    public static SubtitleTrack parse(String jsonString) throws JSONException {
        if (jsonString == null || jsonString.trim().isEmpty()) {
            return new SubtitleTrack("en", Collections.emptyList());
        }
        return fromJson(new JSONObject(jsonString));
    }

    /**
     * Parses a subtitle track from a JSON object.
     * Handles:
     * - Top-level `{ subtitles: { language, words, segments } }`
     * - Direct `{ language, words, segments }`
     * - Re-serialized `{ language, words, phrases }`
     * - Fallback to segments if words array is absent or empty.
     * - Filters out standalone 'spacing' and 'audio_event' tokens.
     */
    public static SubtitleTrack fromJson(JSONObject root) {
        if (root == null) {
            return new SubtitleTrack("en", Collections.emptyList());
        }

        JSONObject subObj = root.optJSONObject("subtitles");
        JSONObject target = subObj != null ? subObj : root;

        String language = target.optString("language", "");
        if (language.isEmpty()) {
            language = root.optString("language", "en");
        }

        List<SubtitleWord> words = new ArrayList<>();
        JSONArray wordsArr = target.optJSONArray("words");
        if (wordsArr != null) {
            for (int i = 0; i < wordsArr.length(); i++) {
                JSONObject obj = wordsArr.optJSONObject(i);
                if (obj != null) {
                    SubtitleWord word = SubtitleWord.fromJson(obj);
                    if (word != null && word.isWord && !word.text.trim().isEmpty()) {
                        words.add(word);
                    }
                }
            }
        }

        // Fallback / supplement: check segments if words array was empty
        if (words.isEmpty()) {
            JSONArray segsArr = target.optJSONArray("segments");
            if (segsArr == null && subObj != null) {
                segsArr = root.optJSONArray("segments");
            }
            if (segsArr != null) {
                for (int i = 0; i < segsArr.length(); i++) {
                    JSONObject seg = segsArr.optJSONObject(i);
                    if (seg != null) {
                        JSONArray segWords = seg.optJSONArray("words");
                        if (segWords != null && segWords.length() > 0) {
                            for (int j = 0; j < segWords.length(); j++) {
                                JSONObject wObj = segWords.optJSONObject(j);
                                if (wObj != null) {
                                    SubtitleWord sw = SubtitleWord.fromJson(wObj);
                                    if (sw != null && sw.isWord && !sw.text.trim().isEmpty()) {
                                        words.add(sw);
                                    }
                                }
                            }
                        } else {
                            // Extract words from segment text
                            String segText = seg.optString("text", "").trim();
                            long sMs = seg.optLong("startMs", 0);
                            long eMs = seg.optLong("endMs", sMs);
                            if (!segText.isEmpty()) {
                                String[] split = segText.split("\\s+");
                                if (split.length == 1) {
                                    words.add(new SubtitleWord(split[0], sMs, eMs, true));
                                } else if (split.length > 1) {
                                    long step = Math.max(1, (eMs - sMs) / split.length);
                                    for (int k = 0; k < split.length; k++) {
                                        long ws = sMs + k * step;
                                        long we = (k == split.length - 1) ? eMs : ws + step;
                                        words.add(new SubtitleWord(split[k], ws, we, true));
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Parse pre-grouped phrases if present
        JSONArray phrasesArr = target.optJSONArray("phrases");
        List<SubtitlePhrase> phrases = new ArrayList<>();
        if (phrasesArr != null) {
            for (int i = 0; i < phrasesArr.length(); i++) {
                JSONObject obj = phrasesArr.optJSONObject(i);
                if (obj != null) {
                    SubtitlePhrase p = SubtitlePhrase.fromJson(obj);
                    if (p != null) phrases.add(p);
                }
            }
        }

        if (phrases.isEmpty() && !words.isEmpty()) {
            phrases = groupWordsIntoPhrases(words);
        } else if (!phrases.isEmpty() && words.isEmpty()) {
            for (SubtitlePhrase p : phrases) {
                words.addAll(p.words);
            }
        }

        return new SubtitleTrack(language, words, phrases);
    }

    /**
     * Serializes this subtitle track to a JSONObject.
     */
    public JSONObject toJson() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("language", language);

        JSONArray wordsArr = new JSONArray();
        for (SubtitleWord w : rawWords) {
            wordsArr.put(w.toJson());
        }
        root.put("words", wordsArr);

        JSONArray phrasesArr = new JSONArray();
        for (SubtitlePhrase p : phrases) {
            phrasesArr.put(p.toJson());
        }
        root.put("phrases", phrasesArr);

        return root;
    }

    /**
     * Returns the serialized JSON string.
     */
    public String toJsonString() {
        try {
            return toJson().toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SubtitleTrack that = (SubtitleTrack) o;
        return Objects.equals(language, that.language) &&
                Objects.equals(rawWords, that.rawWords) &&
                Objects.equals(phrases, that.phrases);
    }

    @Override
    public int hashCode() {
        return Objects.hash(language, rawWords, phrases);
    }

    @Override
    public String toString() {
        return "SubtitleTrack{" +
                "language='" + language + '\'' +
                ", words=" + rawWords.size() +
                ", phrases=" + phrases.size() +
                '}';
    }
}
