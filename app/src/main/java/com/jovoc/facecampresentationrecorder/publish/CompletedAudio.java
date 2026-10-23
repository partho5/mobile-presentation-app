package com.jovoc.facecampresentationrecorder.publish;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Answer of POST /upload/multipart/:id/complete for a transcript_audio upload: just the upload id
 * ({@code { id }}, no path, no poster). {@link CompletedUpload#parse} needs a path, so audio has its
 * own parser.
 */
public final class CompletedAudio {
    public final String id;

    CompletedAudio(String id) {
        this.id = id;
    }

    static CompletedAudio parse(String body) throws JSONException {
        String id = new JSONObject(body).optString("id", "");
        if (id.isEmpty()) throw new JSONException("no audio upload id");
        return new CompletedAudio(id);
    }
}
