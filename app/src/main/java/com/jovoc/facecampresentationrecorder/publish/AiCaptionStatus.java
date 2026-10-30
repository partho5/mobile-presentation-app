package com.jovoc.facecampresentationrecorder.publish;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The status of GET /ai-captions/:id, including optional word-synced subtitles payload.
 */
public final class AiCaptionStatus {
    public static final String PENDING = "PENDING";
    public static final String TRANSCRIBING = "TRANSCRIBING";
    public static final String GENERATING = "GENERATING";
    public static final String READY = "READY";
    public static final String NO_SPEECH = "NO_SPEECH";
    public static final String FAILED = "FAILED";

    public final String status;
    public final String subtitlesJson;

    public AiCaptionStatus(String status, String subtitlesJson) {
        this.status = status == null ? "" : status;
        this.subtitlesJson = subtitlesJson;
    }

    public AiCaptionStatus(String status) {
        this(status, null);
    }

    /** READY, NO_SPEECH or FAILED: the server will not change it any more. */
    public boolean isFinal() {
        return READY.equals(status) || NO_SPEECH.equals(status) || FAILED.equals(status);
    }

    static AiCaptionStatus parse(String body) throws JSONException {
        JSONObject obj = new JSONObject(body);
        String s = obj.optString("status", "");
        if (s.isEmpty()) throw new JSONException("no status");

        String subJson = null;
        if (obj.has("subtitles")) {
            Object sub = obj.get("subtitles");
            if (sub instanceof JSONObject || sub instanceof org.json.JSONArray) {
                subJson = sub.toString();
            } else if (sub instanceof String) {
                String str = (String) sub;
                if (!str.trim().isEmpty() && !str.equalsIgnoreCase("null")) {
                    subJson = str;
                }
            }
        } else if (obj.has("subtitlesJson")) {
            String str = obj.optString("subtitlesJson", null);
            if (str != null && !str.trim().isEmpty() && !str.equalsIgnoreCase("null")) {
                subJson = str;
            }
        } else if (obj.has("words")) {
            subJson = obj.toString();
        }

        return new AiCaptionStatus(s, subJson);
    }
}
