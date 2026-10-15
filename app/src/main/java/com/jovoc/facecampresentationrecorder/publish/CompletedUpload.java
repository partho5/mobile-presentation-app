package com.jovoc.facecampresentationrecorder.publish;

import org.json.JSONException;
import org.json.JSONObject;

/** Answer of POST /upload/multipart/:id/complete: the media, as POST /upload gives it. */
public final class CompletedUpload {
    public final String id;
    public final String path;
    /** Public URL of the stored poster image, or null when none was sent or stored. */
    public final String poster;

    CompletedUpload(String id, String path, String poster) {
        this.id = id;
        this.path = path;
        this.poster = poster;
    }

    /** The media the post refers to. */
    public PublishApi.Media toMedia() {
        return new PublishApi.Media(id, path);
    }

    static CompletedUpload parse(String body) throws JSONException {
        JSONObject o = new JSONObject(body);
        String id = o.optString("id", "");
        String path = o.optString("path", "");
        if (id.isEmpty() || path.isEmpty()) throw new JSONException("no media");
        String poster = o.optString("poster", "");
        return new CompletedUpload(id, path, poster.isEmpty() ? null : poster);
    }
}
