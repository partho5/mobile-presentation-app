package com.jovoc.facecampresentationrecorder.publish;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The status of GET /ai-captions/:id. The app only needs where the caption stands; it never reads
 * (or logs) the generated text.
 */
public final class AiCaptionStatus {
    public static final String PENDING = "PENDING";
    public static final String TRANSCRIBING = "TRANSCRIBING";
    public static final String GENERATING = "GENERATING";
    public static final String READY = "READY";
    public static final String NO_SPEECH = "NO_SPEECH";
    public static final String FAILED = "FAILED";

    public final String status;

    public AiCaptionStatus(String status) {
        this.status = status == null ? "" : status;
    }

    /** READY, NO_SPEECH or FAILED: the server will not change it any more. */
    public boolean isFinal() {
        return READY.equals(status) || NO_SPEECH.equals(status) || FAILED.equals(status);
    }

    static AiCaptionStatus parse(String body) throws JSONException {
        String s = new JSONObject(body).optString("status", "");
        if (s.isEmpty()) throw new JSONException("no status");
        return new AiCaptionStatus(s);
    }
}
