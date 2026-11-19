package com.jovoc.facecampresentationrecorder.aiedit.engine.contract;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The effect and text-look ids this build can render. Sent with every request so the server plans only what
 * the phone can draw. Each card that adds a renderer adds its ids to {@link #current()}; text motions are listed in
 * {@code text_motions}: the legacy five plus only the ids the owner ticked on a device.
 */
public final class Capabilities {

    /** The text motions every installed phone draws; the server assumes them when no list is sent. */
    public static final List<String> LEGACY_TEXT_MOTIONS =
            Collections.unmodifiableList(Arrays.asList("pop", "stamp", "slide_up", "word_by_word", "sweep"));

    /**
     * The motion ids the owner ticked under "Passed on a device" in {@code to-build/tasks.md} (gate 1), in the server's
     * naming: an entry as its name ({@code fade}, {@code slide_left}, ...), a hold as {@code hold_<id>}
     * ({@code hold_float}, ...) and an exit as {@code exit_<id>} ({@code exit_fade}, ...). Nothing is added by a card
     * or by hand, only after the owner ticked the id there; empty until then.
     */
    public static final List<String> DEVICE_PASSED_TEXT_MOTIONS = Collections.unmodifiableList(Arrays.<String>asList());

    private final Set<String> effects;
    private final Set<String> textStyles;
    private final Set<String> textMotions;

    public Capabilities(Collection<String> effects, Collection<String> textStyles) {
        this(effects, textStyles, Collections.<String>emptyList());
    }

    /**
     * @param extraTextMotions motion ids beyond {@link #LEGACY_TEXT_MOTIONS} (those are always included)
     */
    public Capabilities(Collection<String> effects, Collection<String> textStyles,
                        Collection<String> extraTextMotions) {
        this.effects = Collections.unmodifiableSet(new LinkedHashSet<>(effects));
        this.textStyles = Collections.unmodifiableSet(new LinkedHashSet<>(textStyles));
        Set<String> motions = new LinkedHashSet<>(LEGACY_TEXT_MOTIONS);
        motions.addAll(extraTextMotions);
        this.textMotions = Collections.unmodifiableSet(motions);
    }

    /**
     * What this build renders: captions (highlight, active-word pop, big emphasis words), the jump cut, the camera
     * moves, flash and glitch looks, the facecam layouts, the cut transitions, the text overlays and the icon
     * scale pop.
     */
    public static Capabilities current() {
        return current(DEVICE_PASSED_TEXT_MOTIONS);
    }

    /** {@link #current()} with the given owner-ticked motion ids (the seam the tests use; the app calls {@link #current()}). */
    public static Capabilities current(List<String> passedTextMotions) {
        return new Capabilities(
                Arrays.asList("c_word_highlight", "c_active_pop", "c_emphasis_big", "t_jump_cut",
                        "v_punch_in", "v_push_in", "v_alt_crop", "l_flash", "l_rgb_glitch",
                        "y_face_grow_slide_shrink", "y_slide_grow_face_shrink", "tr_flash_cut", "tr_whip",
                        "x_pop", "x_stamp", "x_sticker", "x_3d", "x_glow", "x_label_tag", "x_marker_sweep",
                        "i_scale_pop"),
                Arrays.asList("sticker", "label_pill", "3d", "glow", "gradient", "highlighter"),
                passedTextMotions);
    }

    public boolean canRender(String effectId) {
        return effects.contains(effectId);
    }

    public boolean canStyle(String textLookId) {
        return textStyles.contains(textLookId);
    }

    /** Whether the server may plan this motion id (legacy five, or ticked by the owner). */
    public boolean canMove(String textMotionId) {
        return textMotions.contains(textMotionId);
    }

    public Set<String> getTextMotions() {
        return textMotions;
    }

    public Set<String> getEffects() {
        return effects;
    }

    public Set<String> getTextStyles() {
        return textStyles;
    }

    /** Whether every id here exists in {@code contract} (a guard against a typo or an old contract). */
    public boolean knownTo(EngineContract contract) {
        for (String e : effects) if (contract.effect(e) == null) return false;
        for (String s : textStyles) if (contract.textLook(s) == null) return false;
        return true;
    }

    /** The request's {@code capabilities} object. */
    public JSONObject toJson() {
        try {
            return new JSONObject()
                    .put("effects", new JSONArray(effects))
                    .put("text_styles", new JSONArray(textStyles))
                    .put("text_motions", new JSONArray(textMotions));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }
}
