package com.jovoc.facecampresentationrecorder.aiedit.engine.contract;

import com.jovoc.facecampresentationrecorder.aiedit.engine.text.TextLookSupport;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
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
     * or by hand, only after the owner ticked the id there. The owner ticked every non-legacy motion of the debug motion sheet
     * (22 ids; with the legacy five that is 27, under the server's limit of 50 per list); a test pins this list to the
     * sheet's grid so the two cannot drift.
     */
    public static final List<String> DEVICE_PASSED_TEXT_MOTIONS = Collections.unmodifiableList(Arrays.asList(
            "fade", "slide_left", "slide_right", "slide_top", "slide_bottom", "typewriter", "wipe", "drop_bounce",
            "blur_in", "flip", "glitch",
            "hold_float", "hold_pulse", "hold_shake", "hold_shimmer", "hold_neon_flicker",
            "exit_fade", "exit_slide_out", "exit_shrink", "exit_pop_out", "exit_typewriter_delete", "exit_wipe_out"));

    /** The server's limit on the length of each {@code text_*} capability list in a request. */
    public static final int MAX_LIST = 50;

    /**
     * The looks the owner ticked under "Passed on a device" in {@code to-build/tasks.md} (gate 1). The six shipped looks
     * are there by definition (installed phones always drew them); a new look is added only after the owner ticked it
     * (card C10), never by hand or by a card before that. The owner ticked all ten new looks on the look sheet.
     */
    public static final List<String> DEVICE_PASSED_TEXT_LOOKS;

    static {
        List<String> looks = new ArrayList<>(TextLookSupport.SHIPPED_LOOKS);
        looks.addAll(Arrays.asList("neon_box", "pill_ring", "gradient_block", "tilt_sticker", "solid_round",
                "pastel_pill", "flat_rect", "yellow_bold", "dark_accent", "coral_pill"));
        DEVICE_PASSED_TEXT_LOOKS = Collections.unmodifiableList(looks);
    }

    /**
     * The {@code text_features} ids the painter implements and the owner ticked (gate 1): the union of what the ticked
     * looks need ({@link TextLookSupport#requiredFeatures}), in the painter's order. The shipped looks need none; a test
     * derives the union from the bundled contract and pins this list to it so the two cannot drift.
     */
    public static final List<String> DEVICE_PASSED_TEXT_FEATURES = Collections.unmodifiableList(Arrays.asList(
            "box_gradient", "box_border", "box_border_ring", "box_glow", "font_family", "font_weight",
            "case_as_written"));

    private final Set<String> effects;
    private final Set<String> textStyles;
    private final Set<String> textMotions;
    private final Set<String> textFeatures;

    public Capabilities(Collection<String> effects, Collection<String> textStyles) {
        this(effects, textStyles, Collections.<String>emptyList());
    }

    /**
     * @param extraTextMotions motion ids beyond {@link #LEGACY_TEXT_MOTIONS} (those are always included)
     */
    public Capabilities(Collection<String> effects, Collection<String> textStyles,
                        Collection<String> extraTextMotions) {
        this(effects, textStyles, extraTextMotions, Collections.<String>emptyList());
    }

    /** @param textFeatures the {@code text_features} ids this build draws and the owner ticked */
    public Capabilities(Collection<String> effects, Collection<String> textStyles,
                        Collection<String> extraTextMotions, Collection<String> textFeatures) {
        this.textFeatures = Collections.unmodifiableSet(new LinkedHashSet<>(textFeatures));
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

    /**
     * What this build renders, by evidence: the text styles are the contract's looks that the owner ticked, that the
     * painter draws completely ({@link TextLookSupport#drawable}) and whose every needed feature is listed too; the
     * features are the ones the painter implements, the owner ticked and the contract names. A look the painter has no
     * handler for is never listed. A contract whose layer order or colour roles differ from the painter's lists the
     * shipped six only.
     */
    public static Capabilities current(EngineContract contract) {
        return current(contract, DEVICE_PASSED_TEXT_LOOKS, DEVICE_PASSED_TEXT_FEATURES, DEVICE_PASSED_TEXT_MOTIONS);
    }

    /** {@link #current(EngineContract)} with the given owner-ticked ids (the seam the tests use). */
    public static Capabilities current(EngineContract contract, Collection<String> passedLooks,
                                       Collection<String> passedFeatures, List<String> passedTextMotions) {
        List<String> features = new ArrayList<>();
        for (String f : TextLookSupport.IMPLEMENTED_FEATURES) {
            if (passedFeatures.contains(f) && contract.getTextFeatures().contains(f)) features.add(f);
        }
        boolean contractOk = TextLookSupport.contractMatches(contract);
        List<String> styles = new ArrayList<>();
        for (TextLook look : contract.getTextLooks()) {
            if (!passedLooks.contains(look.id)) continue;
            if (!contractOk && !TextLookSupport.SHIPPED_LOOKS.contains(look.id)) continue;
            if (!TextLookSupport.drawable(look)) continue;
            if (!features.containsAll(TextLookSupport.requiredFeatures(look))) continue;
            styles.add(look.id);
        }
        Capabilities base = current(passedTextMotions);
        return new Capabilities(base.effects, styles, passedTextMotions, features);
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

    /** Whether the server may plan a look that needs this feature id. */
    public boolean canDraw(String textFeatureId) {
        return textFeatures.contains(textFeatureId);
    }

    public Set<String> getTextFeatures() {
        return textFeatures;
    }

    public Set<String> getTextStyles() {
        return textStyles;
    }

    /** The names the server's request validation accepts, at most {@link #MAX_LIST}, in order. */
    static List<String> sendable(Collection<String> ids) {
        List<String> out = new ArrayList<>();
        for (String id : ids) {
            if (out.size() >= MAX_LIST) break;
            if (id != null && id.matches("^[a-z0-9_]{1,64}$")) out.add(id);
        }
        return out;
    }

    /** Whether every id here exists in {@code contract} (a guard against a typo or an old contract). */
    public boolean knownTo(EngineContract contract) {
        for (String e : effects) if (contract.effect(e) == null) return false;
        for (String s : textStyles) if (contract.textLook(s) == null) return false;
        return true;
    }

    /**
     * The request's {@code capabilities} object. The server accepts at most {@link #MAX_LIST} names in each of
     * {@code text_styles}, {@code text_motions} and {@code text_features}, each matching {@code ^[a-z0-9_]{1,64}$}. A
     * longer list is cut to its first {@link #MAX_LIST}: the priority is the list order (legacy motions first, then the
     * ticked ids in the order of the motion sheet; looks in contract order; features in the painter's order). A name the
     * server would reject is never sent (it would fail the whole request); it is skipped.
     */
    public JSONObject toJson() {
        try {
            return new JSONObject()
                    .put("effects", new JSONArray(effects))
                    .put("text_styles", new JSONArray(sendable(textStyles)))
                    .put("text_motions", new JSONArray(sendable(textMotions)))
                    .put("text_features", new JSONArray(sendable(textFeatures)));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }
}
