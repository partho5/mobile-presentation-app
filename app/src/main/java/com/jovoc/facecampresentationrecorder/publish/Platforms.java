package com.jovoc.facecampresentationrecorder.publish;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Every platform the workspace can post to, with its connection state and connected channels
 * (GET /public/v1/platforms). The order is the server's: video platforms first.
 */
public final class Platforms {

    public static final String MODE_AUTOMATIC = "automatic";
    public static final String MODE_PRESET = "preset";

    /** False for a member who cannot add app keys; any workspace admin (ADMIN/SUPERADMIN) can. */
    public final boolean canManageCredentials;
    /** {@link #MODE_AUTOMATIC} (the switches decide) or {@link #MODE_PRESET} (a web Set decides). */
    public final String mode;
    /** Name of the web preset when {@link #mode} is preset; "" otherwise. */
    public final String presetName;
    public final List<Platform> platforms;

    public Platforms(boolean canManageCredentials, String mode, String presetName,
                     List<Platform> platforms) {
        this.canManageCredentials = canManageCredentials;
        this.mode = mode == null ? MODE_AUTOMATIC : mode;
        this.presetName = presetName == null ? "" : presetName;
        this.platforms = Collections.unmodifiableList(new ArrayList<>(platforms));
    }

    /** One supported platform (YouTube, TikTok, ...). */
    public static final class Platform {

        public static final String STATUS_CONNECTED = "connected";
        public static final String STATUS_NEEDS_CREDENTIALS = "needs_credentials";
        public static final String STATUS_READY_TO_CONNECT = "ready_to_connect";
        public static final String GROUP_VIDEO = "video";
        public static final String GROUP_OTHER = "other";

        /** Provider identifier, e.g. "youtube"; what web-link expects. */
        public final String identifier;
        public final String name;
        /** Absolute icon URL; "" when the server sent none. */
        public final String icon;
        /** {@link #GROUP_VIDEO} or {@link #GROUP_OTHER}. */
        public final String group;
        public final String status;
        public final boolean autoPublishSupported;
        /** True when the server can post a text version of a video here (platforms.textPost). */
        public final boolean textPost;
        public final List<ChannelState> channels;

        public Platform(String identifier, String name, String icon, String group, String status,
                        boolean autoPublishSupported, List<ChannelState> channels) {
            this(identifier, name, icon, group, status, autoPublishSupported, false, channels);
        }

        public Platform(String identifier, String name, String icon, String group, String status,
                        boolean autoPublishSupported, boolean textPost, List<ChannelState> channels) {
            this.identifier = identifier;
            this.name = name;
            this.icon = icon == null ? "" : icon;
            this.group = GROUP_VIDEO.equals(group) ? GROUP_VIDEO : GROUP_OTHER;
            this.status = status == null ? STATUS_READY_TO_CONNECT : status;
            this.autoPublishSupported = autoPublishSupported;
            this.textPost = textPost;
            this.channels = Collections.unmodifiableList(new ArrayList<>(channels));
        }

        public boolean isConnected() {
            return STATUS_CONNECTED.equals(status);
        }

        public boolean needsCredentials() {
            return STATUS_NEEDS_CREDENTIALS.equals(status);
        }

        public boolean isVideo() {
            return GROUP_VIDEO.equals(group);
        }

        /** What a connected channel row of this platform offers. */
        public ChannelRow channelRow() {
            if (autoPublishSupported) return ChannelRow.AUTO_PUBLISH;
            return textPost ? ChannelRow.TEXT_POST : ChannelRow.MANUAL_ONLY;
        }
    }

    /** The control on a channel row: the auto-publish switch, the text-post switch, or a note. */
    public enum ChannelRow { AUTO_PUBLISH, TEXT_POST, MANUAL_ONLY }

    /** A connected channel of a platform and whether recordings auto-publish to it. */
    public static final class ChannelState {

        /** The integration id (what PUT /auto-publish/channels/:id takes). */
        public final String id;
        public final String name;
        public final String picture;
        public final boolean disabled;
        public final boolean refreshNeeded;
        /** Not final: the screen flips it optimistically (main thread only) while the PUT runs. */
        public boolean autoPublish;
        /** As {@link #autoPublish}, for the "Text post from your video" switch (channels.textPostOn). */
        public boolean textPostOn;

        public ChannelState(String id, String name, String picture, boolean disabled,
                            boolean refreshNeeded, boolean autoPublish) {
            this(id, name, picture, disabled, refreshNeeded, autoPublish, false);
        }

        public ChannelState(String id, String name, String picture, boolean disabled,
                            boolean refreshNeeded, boolean autoPublish, boolean textPostOn) {
            this.textPostOn = textPostOn;
            this.id = id;
            this.name = name;
            this.picture = picture;
            this.disabled = disabled;
            this.refreshNeeded = refreshNeeded;
            this.autoPublish = autoPublish;
        }
    }

    /** True when the web preset, not the per-channel switches, decides where recordings go. */
    public boolean usesPreset() {
        return MODE_PRESET.equals(mode);
    }

    /** True when at least one platform has a connection. */
    public boolean anyConnected() {
        for (Platform p : platforms) if (p.isConnected()) return true;
        return false;
    }

    /** The platform with this identifier, or null. */
    public Platform find(String identifier) {
        for (Platform p : platforms) if (p.identifier.equals(identifier)) return p;
        return null;
    }

    /** Parses the GET answer; platforms without an identifier and channels without an id are skipped. */
    public static Platforms parse(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        JSONArray arr = o.optJSONArray("platforms");
        if (arr == null) throw new JSONException("no platforms");
        List<Platform> list = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject p = arr.optJSONObject(i);
            if (p == null) continue;
            String identifier = p.optString("identifier", "").trim();
            if (identifier.isEmpty()) continue;
            String name = optText(p, "name").replace('\n', ' ').trim();
            if (name.isEmpty()) name = AutoPublishInfo.providerName(identifier);
            list.add(new Platform(identifier, name, optText(p, "icon"), p.optString("group", ""),
                    p.optString("status", ""), p.optBoolean("autoPublishSupported", false),
                    p.optBoolean("textPost", false), parseChannels(p.optJSONArray("channels"))));
        }
        JSONObject auto = o.optJSONObject("autoPublish");
        String mode = auto == null ? MODE_AUTOMATIC : auto.optString("mode", MODE_AUTOMATIC);
        String presetName = auto == null ? "" : optText(auto, "presetName").trim();
        return new Platforms(o.optBoolean("canManageCredentials", false), mode, presetName, list);
    }

    private static List<ChannelState> parseChannels(JSONArray arr) {
        List<ChannelState> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.optJSONObject(i);
            if (c == null) continue;
            String id = c.optString("id", "");
            if (id.isEmpty()) continue;
            String picture = optText(c, "picture");
            out.add(new ChannelState(id, optText(c, "name").replace('\n', ' ').trim(),
                    picture.isEmpty() ? null : picture,
                    c.optBoolean("disabled", false), c.optBoolean("refreshNeeded", false),
                    c.optBoolean("autoPublish", false), c.optBoolean("textPostOn", false)));
        }
        return out;
    }

    /** The string value, or "" for a missing / null key (optString would give "null"). */
    private static String optText(JSONObject o, String key) {
        return o.isNull(key) ? "" : o.optString(key, "");
    }
}
