package com.jovoc.facecampresentationrecorder.ui;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import com.jovoc.facecampresentationrecorder.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Developer-authored "How it helps you" content for the Settings screen.
 *
 * <p><b>Adding help to a section</b> = one {@code HELP.put(...)} line in the static block below.
 * A section with no entry shows no "How it helps you" header at all. Blocks render in the order
 * given and may be any mix of:
 * <pre>
 *   text(R.string.help_x)                      // string resource; web URLs inside become tappable links
 *   image(R.drawable.x, R.string.help_x_desc)  // scaled to width; the string is its contentDescription
 *   youtube("abcdEFGhij_")                     // 11-character video ID, embedded 16:9
 * </pre>
 * Example:
 * <pre>
 *   HELP.put(AUDIO, help(
 *           text(R.string.help_audio),
 *           image(R.drawable.help_audio_mic, R.string.help_audio_mic_desc),
 *           youtube("abcdEFGhij_")));
 * </pre>
 * A new section also needs a {@code <SettingsHelpView app:helpSection="id"/>} in
 * activity_settings.xml and an id constant here.
 */
public final class SettingsHelp {

    // Section ids; the same string goes in app:helpSection in activity_settings.xml.
    public static final String COUNTDOWN = "countdown";
    public static final String AUDIO = "audio";
    public static final String AUTO_CROP = "auto_crop";
    public static final String BACKGROUND = "background";

    private static final Map<String, List<Block>> HELP = new LinkedHashMap<>();

    static {
        HELP.put(COUNTDOWN, help(text(R.string.help_countdown)));
        HELP.put(AUDIO, help(text(R.string.help_audio)));
        HELP.put(AUTO_CROP, help(text(R.string.help_auto_crop)));
        HELP.put(BACKGROUND, help(text(R.string.help_background)));
    }

    // Permissions have no HELP entry: SettingsActivity shows only the blocks of the permissions
    // still missing, through SettingsHelpView#setBlocks.
    public static final Block PERM_CAMERA = text(R.string.help_perm_camera);
    public static final Block PERM_MIC = text(R.string.help_perm_mic);
    public static final Block PERM_NOTIFICATIONS = text(R.string.help_perm_notifications);
    public static final Block PERM_OVERLAY = text(R.string.help_perm_overlay);

    private SettingsHelp() {}

    // ---- model ----

    public enum Type { TEXT, IMAGE, YOUTUBE }

    /** One piece of help content; build with {@link #text}, {@link #image} or {@link #youtube}. */
    public static final class Block {
        public final Type type;
        /** TEXT: the string; IMAGE: the drawable. */
        public final int resId;
        /** IMAGE: the contentDescription string. */
        public final int descResId;
        /** YOUTUBE: the video ID. */
        public final String videoId;

        private Block(Type type, int resId, int descResId, String videoId) {
            this.type = type;
            this.resId = resId;
            this.descResId = descResId;
            this.videoId = videoId;
        }
    }

    // ---- authoring API ----

    public static List<Block> help(Block... blocks) {
        return Collections.unmodifiableList(new ArrayList<>(Arrays.asList(blocks)));
    }

    public static Block text(@StringRes int stringRes) {
        return new Block(Type.TEXT, stringRes, 0, null);
    }

    public static Block image(@DrawableRes int drawableRes, @StringRes int contentDescriptionRes) {
        return new Block(Type.IMAGE, drawableRes, contentDescriptionRes, null);
    }

    /** Image with the generic "Illustration" description; prefer the two-argument overload. */
    public static Block image(@DrawableRes int drawableRes) {
        return image(drawableRes, R.string.set_help_image_desc);
    }

    /** @throws IllegalArgumentException if {@code videoId} is not an 11-character YouTube ID */
    public static Block youtube(@NonNull String videoId) {
        if (!isValidVideoId(videoId)) {
            throw new IllegalArgumentException("Not a YouTube video ID: " + videoId);
        }
        return new Block(Type.YOUTUBE, 0, 0, videoId);
    }

    // ---- lookup ----

    /** The help blocks for a section; empty (never null) when the section has none. */
    @NonNull
    public static List<Block> forSection(String sectionId) {
        List<Block> blocks = sectionId == null ? null : HELP.get(sectionId);
        return blocks == null ? Collections.<Block>emptyList() : blocks;
    }

    /** Ids that have help registered (used by the unit test). */
    static List<String> sectionIds() {
        return new ArrayList<>(HELP.keySet());
    }

    // ---- YouTube URLs ----

    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");

    static boolean isValidVideoId(String videoId) {
        return videoId != null && VIDEO_ID.matcher(videoId).matches();
    }

    /** Privacy-enhanced (no-cookie) embed page for the in-app player. */
    static String embedUrl(String videoId) {
        return "https://www.youtube-nocookie.com/embed/" + videoId + "?playsinline=1&rel=0";
    }

    /** Page that the browser or the YouTube app opens for the fallback link. */
    static String watchUrl(String videoId) {
        return "https://www.youtube.com/watch?v=" + videoId;
    }
}
