package com.jovoc.facecampresentationrecorder.publish;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The workspace's auto-publish preset as the server reports it (GET /public/v1/auto-publish):
 * which channels a finished recording goes to, what that costs and what the balance is. The
 * numbers are the server's; the app never works them out itself.
 */
public final class AutoPublishInfo {

    /** False when no preset is chosen on the web, or none of its channels is usable any more. */
    public final boolean configured;
    /** Name of the web "Set" used as the preset; null when not configured. */
    public final String setName;
    /** "now" or "next_slot"; null when not configured. */
    public final String timing;
    /** Every channel of the preset, disabled ones included (see {@link #activeChannels()}). */
    public final List<Channel> channels;
    /** Credits one auto-published video costs; 0 means free. */
    public final long cost;
    /** Credits the workspace has available right now. */
    public final long available;
    /**
     * Web page where the preset is chosen; empty when the server did not send one. Parsed but not
     * used: the app's Auto upload screen is where auto-publish is set up.
     */
    public final String setupUrl;
    /**
     * Credits per whole minute of speech for AI captions (aiCaptions.costPerMinute); 0 means free,
     * {@link #COST_UNKNOWN} when an older server did not send it.
     */
    public final long aiCaptionsCostPerMinute;

    public static final long COST_UNKNOWN = -1L;

    public AutoPublishInfo(boolean configured, String setName, String timing, List<Channel> channels,
                           long cost, long available, String setupUrl) {
        this(configured, setName, timing, channels, cost, available, setupUrl, COST_UNKNOWN);
    }

    public AutoPublishInfo(boolean configured, String setName, String timing, List<Channel> channels,
                           long cost, long available, String setupUrl, long aiCaptionsCostPerMinute) {
        this.configured = configured;
        this.setName = setName;
        this.timing = timing;
        this.channels = Collections.unmodifiableList(new ArrayList<>(channels));
        this.cost = cost;
        this.available = available;
        this.setupUrl = setupUrl == null ? "" : setupUrl;
        this.aiCaptionsCostPerMinute = aiCaptionsCostPerMinute < 0 ? COST_UNKNOWN : aiCaptionsCostPerMinute;
    }

    /** Parses the GET answer. {@code configured} must be present; the rest is read leniently. */
    public static AutoPublishInfo parse(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        if (!o.has("configured")) throw new JSONException("no configured");
        boolean configured = o.getBoolean("configured");
        List<Channel> channels = new ArrayList<>();
        JSONArray arr = o.optJSONArray("channels");
        if (arr != null) channels = Channel.parseList(arr.toString());
        JSONObject ai = o.optJSONObject("aiCaptions");
        return new AutoPublishInfo(
                configured,
                o.isNull("setName") ? null : o.optString("setName", null),
                o.isNull("timing") ? null : o.optString("timing", null),
                channels,
                Math.max(0L, o.optLong("cost", 0L)),
                Math.max(0L, o.optLong("available", 0L)),
                o.optString("setupUrl", ""),
                ai == null ? COST_UNKNOWN : ai.optLong("costPerMinute", COST_UNKNOWN));
    }

    /** The channels a recording will actually be posted to. */
    public List<Channel> activeChannels() {
        List<Channel> out = new ArrayList<>();
        for (Channel c : channels) {
            if (!c.disabled) out.add(c);
        }
        return out;
    }

    /** Provider names of {@link #activeChannels()} without repeats, in order: ["YouTube", "TikTok"]. */
    public List<String> providerNames() {
        Set<String> names = new LinkedHashSet<>();
        for (Channel c : activeChannels()) names.add(providerName(c.identifier));
        return new ArrayList<>(names);
    }

    /** "youtube" -> "YouTube", "instagram-standalone" -> "Instagram", "x" -> "X". */
    public static String providerName(String identifier) {
        String base = identifier == null ? "" : identifier.trim().toLowerCase(Locale.ROOT);
        int dash = base.indexOf('-');
        if (dash > 0) base = base.substring(0, dash);
        switch (base) {
            case "":
                return "";
            case "youtube":
                return "YouTube";
            case "tiktok":
                return "TikTok";
            case "linkedin":
                return "LinkedIn";
            case "x":
                return "X";
            default:
                return Character.toUpperCase(base.charAt(0)) + base.substring(1);
        }
    }

    /** True when the balance covers one video; the server still decides at post time. */
    public boolean canAfford() {
        return available >= cost;
    }
}
