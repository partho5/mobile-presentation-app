package com.jovoc.facecampresentationrecorder.publish;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Per-provider settings the backend validates before it accepts a post. The app has no screen
 * for these yet, so a video goes out with sensible public defaults. Providers not listed here
 * need no settings; one that needs more answers 400 with a readable reason (shown on the card).
 */
final class ProviderSettings {

    private ProviderSettings() {}

    static JSONObject forProvider(String identifier, String title) {
        JSONObject s = new JSONObject();
        try {
            switch (identifier == null ? "" : identifier) {
                case "youtube":
                    s.put("title", clamp(title, 2, 100));
                    s.put("type", "public");
                    s.put("selfDeclaredMadeForKids", "no");
                    s.put("tags", new JSONArray());
                    break;
                case "tiktok":
                    s.put("title", clamp(title, 0, 90));
                    s.put("privacy_level", "PUBLIC_TO_EVERYONE");
                    s.put("duet", false);
                    s.put("stitch", false);
                    s.put("comment", true);
                    s.put("autoAddMusic", "no");
                    s.put("brand_content_toggle", false);
                    s.put("brand_organic_toggle", false);
                    s.put("content_posting_method", "DIRECT_POST");
                    break;
                case "instagram":
                case "instagram-standalone":
                    s.put("post_type", "post");
                    s.put("collaborators", new JSONArray());
                    break;
                case "x":
                    s.put("who_can_reply_post", "everyone");
                    break;
                default:
                    break;
            }
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return s;
    }

    /** Trims to {@code max} characters and pads a too-short title so the backend accepts it. */
    static String clamp(String text, int min, int max) {
        String t = text == null ? "" : text.trim();
        if (t.length() > max) t = t.substring(0, max).trim();
        if (t.length() < min) t = min == 0 ? t : "Video";
        return t;
    }
}
