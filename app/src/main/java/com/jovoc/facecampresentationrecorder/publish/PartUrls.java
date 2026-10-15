package com.jovoc.facecampresentationrecorder.publish;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Answer of POST /upload/multipart/:id/parts: a presigned PUT URL per part number. A URL lives
 * {@link #expiresInSeconds} from signing and is for one part of one upload only; never cache it.
 */
public final class PartUrls {
    /** Part number (1-based) to URL. */
    public final Map<Integer, String> urls;
    public final int expiresInSeconds;

    public PartUrls(Map<Integer, String> urls, int expiresInSeconds) {
        this.urls = Collections.unmodifiableMap(urls);
        this.expiresInSeconds = expiresInSeconds;
    }

    static PartUrls parse(String body) throws JSONException {
        JSONObject o = new JSONObject(body);
        JSONObject u = o.getJSONObject("urls");
        Map<Integer, String> urls = new LinkedHashMap<>();
        for (Iterator<String> it = u.keys(); it.hasNext(); ) {
            String key = it.next();
            String url = u.optString(key, "");
            if (url.isEmpty()) throw new JSONException("empty url");
            try {
                urls.put(Integer.parseInt(key), url);
            } catch (NumberFormatException e) {
                throw new JSONException("bad part number");
            }
        }
        if (urls.isEmpty()) throw new JSONException("no urls");
        return new PartUrls(urls, o.optInt("expiresIn", 0));
    }
}
