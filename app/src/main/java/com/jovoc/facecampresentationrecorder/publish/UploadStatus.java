package com.jovoc.facecampresentationrecorder.publish;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Answer of GET /upload/multipart/:id: where the upload stands and which parts R2 already has. */
public final class UploadStatus {
    public static final String PENDING = "pending";
    public static final String COMPLETING = "completing";
    public static final String COMPLETED = "completed";
    public static final String ABORTED = "aborted";
    public static final String EXPIRED = "expired";

    /** One of the constants above. */
    public final String status;
    public final long size;
    public final long partSize;
    public final int partCount;
    /** Only filled for a {@link #PENDING} upload; may be empty even when everything was sent. */
    public final List<UploadedPart> parts;

    UploadStatus(String status, long size, long partSize, int partCount, List<UploadedPart> parts) {
        this.status = status;
        this.size = size;
        this.partSize = partSize;
        this.partCount = partCount;
        this.parts = Collections.unmodifiableList(parts);
    }

    public boolean isPending() {
        return PENDING.equals(status);
    }

    static UploadStatus parse(String body) throws JSONException {
        JSONObject o = new JSONObject(body);
        String status = o.optString("status", "");
        if (status.isEmpty()) throw new JSONException("no status");
        List<UploadedPart> parts = new ArrayList<>();
        JSONArray a = o.optJSONArray("parts");
        if (a != null) {
            for (int i = 0; i < a.length(); i++) parts.add(UploadedPart.parse(a.getJSONObject(i)));
        }
        return new UploadStatus(status, o.optLong("size", 0), o.optLong("partSize", 0),
                o.optInt("partCount", 0), parts);
    }
}
