package com.jovoc.facecampresentationrecorder.publish;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * One part R2 holds: its number, the ETag exactly as R2 sent it (quotes included) and its size.
 * {@code size} is -1 when not known (a part the app just PUT and has not listed).
 */
public final class UploadedPart {
    public final int n;
    public final String etag;
    public final long size;

    public UploadedPart(int n, String etag, long size) {
        this.n = n;
        this.etag = etag;
        this.size = size;
    }

    static UploadedPart parse(JSONObject o) throws JSONException {
        int n = o.optInt("n", 0);
        String etag = o.optString("etag", "");
        if (n <= 0 || etag.isEmpty()) throw new JSONException("bad part");
        return new UploadedPart(n, etag, o.optLong("size", -1));
    }
}
