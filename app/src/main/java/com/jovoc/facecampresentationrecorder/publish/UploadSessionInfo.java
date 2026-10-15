package com.jovoc.facecampresentationrecorder.publish;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Answer of POST /upload/multipart: the upload's id and how the file is cut. Never hardcode the
 * part size or count; the server decides them per upload.
 */
public final class UploadSessionInfo {
    public final String id;
    /** Bytes of every part but the last. */
    public final long partSize;
    public final int partCount;

    public UploadSessionInfo(String id, long partSize, int partCount) {
        this.id = id;
        this.partSize = partSize;
        this.partCount = partCount;
    }

    static UploadSessionInfo parse(String body) throws JSONException {
        JSONObject o = new JSONObject(body);
        String id = o.optString("id", "");
        long partSize = o.optLong("partSize", 0);
        int partCount = o.optInt("partCount", 0);
        if (id.isEmpty() || partSize <= 0 || partCount <= 0) throw new JSONException("no upload");
        return new UploadSessionInfo(id, partSize, partCount);
    }
}
