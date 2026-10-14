package com.jovoc.facecampresentationrecorder.publish;

import com.jovoc.facecampresentationrecorder.db.PublishJob;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Builds the JSON bodies sent to the backend. Pure: no Android, no network. */
public final class PublishRequestBuilder {

    private PublishRequestBuilder() {}

    /** POST /posts/charge-preview */
    public static String chargePreviewBody(List<String> integrationIds) {
        try {
            return new JSONObject().put("integrationIds", new JSONArray(integrationIds)).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * POST /public/v1/posts for an uploaded video. One entry per channel, each carrying the
     * video as its first post's media. {@code nowMs} is used as the date for a "now" post.
     */
    public static String createPostBody(PublishJob job, long nowMs) {
        try {
            boolean schedule = PublishJob.MODE_SCHEDULE.equals(job.mode);
            JSONObject media = new JSONObject().put("id", job.mediaId).put("path", job.mediaPath);
            String html = captionToHtml(job.caption);
            String title = firstLine(job.caption, job.fileName);

            JSONArray posts = new JSONArray();
            for (Channel c : Channel.fromJson(job.channelsJson)) {
                JSONObject value = new JSONObject()
                        .put("content", html)
                        .put("image", new JSONArray().put(media));
                posts.put(new JSONObject()
                        .put("integration", new JSONObject().put("id", c.id))
                        .put("value", new JSONArray().put(value))
                        .put("settings", ProviderSettings.forProvider(c.identifier, title)));
            }
            return new JSONObject()
                    .put("type", schedule ? "schedule" : "now")
                    .put("shortLink", false)
                    .put("date", isoUtc(schedule ? job.scheduleAt : nowMs))
                    .put("tags", new JSONArray())
                    .put("posts", posts)
                    .toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * POST /public/v1/auto-publish: just the uploaded video and the recording's name. Channels,
     * text and timing come from the preset on the server.
     */
    public static String autoPublishBody(PublishJob job) {
        try {
            JSONObject media = new JSONObject().put("id", job.mediaId).put("path", job.mediaPath);
            return new JSONObject().put("media", media).put("title", autoTitle(job.fileName)).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The recording's name for the preset's {@code {title}}: the file name without ".mp4" and
     * without a crop suffix ("_9x16"), so the crop is titled like the recording it came from.
     * Never empty (the server refuses an empty title) and at most 500 characters.
     */
    static String autoTitle(String fileName) {
        String n = fileName == null ? "" : fileName.trim();
        if (n.toLowerCase(Locale.ROOT).endsWith(".mp4")) n = n.substring(0, n.length() - 4);
        n = n.replaceFirst("_\\d+x\\d+$", "").trim();
        if (n.isEmpty()) n = "Recording";
        return n.length() > 500 ? n.substring(0, 500) : n;
    }

    public static List<String> postIds(String responseJson) throws JSONException {
        JSONArray arr = new JSONArray(responseJson);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            String id = o == null ? "" : o.optString("postId", "");
            if (!id.isEmpty()) ids.add(id);
        }
        return ids;
    }

    /** 2026-10-08T14:30:00.000Z, the form the backend's date validation accepts. */
    static String isoUtc(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }

    /** The editor stores post text as HTML: one paragraph per line, text escaped. */
    static String captionToHtml(String caption) {
        String c = caption == null ? "" : caption.replace("\r\n", "\n").trim();
        if (c.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : c.split("\n", -1)) {
            sb.append("<p>")
                    .append(line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
                    .append("</p>");
        }
        return sb.toString();
    }

    /** A title for providers that need one: the caption's first line, else the file name. */
    static String firstLine(String caption, String fileName) {
        String c = caption == null ? "" : caption.trim();
        if (!c.isEmpty()) return c.split("\n", 2)[0];
        String n = fileName == null ? "" : fileName;
        return n.toLowerCase(Locale.ROOT).endsWith(".mp4") ? n.substring(0, n.length() - 4) : n;
    }
}
