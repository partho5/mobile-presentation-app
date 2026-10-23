package com.jovoc.facecampresentationrecorder.publish;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Answer of POST /ai-captions: {@code 202 { id, status: PENDING, cost, minutes }}, or
 * {@code 200 { status: SKIPPED, reason: CREDITS }} when the balance cannot cover the captions
 * (the post then goes out with the user's own text).
 */
public final class AiCaptionCreated {
    /** The caption id; null when {@link #skippedCredits}. */
    public final String id;
    public final boolean skippedCredits;

    AiCaptionCreated(String id, boolean skippedCredits) {
        this.id = id;
        this.skippedCredits = skippedCredits;
    }

    static AiCaptionCreated parse(String body) throws JSONException {
        JSONObject o = new JSONObject(body);
        if ("SKIPPED".equals(o.optString("status", ""))) return new AiCaptionCreated(null, true);
        String id = o.optString("id", "");
        if (id.isEmpty()) throw new JSONException("no caption id");
        return new AiCaptionCreated(id, false);
    }
}
