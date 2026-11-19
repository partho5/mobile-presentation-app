package com.jovoc.facecampresentationrecorder.aiedit.engine.text;

import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.EngineContract;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.TextLook;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What this painter can draw completely, decided from the data of a look and never from its id (gate 2 and 3). Pure
 * Java. {@code Capabilities} uses it to list only looks the phone draws; the overlay painter uses it at draw time to
 * fall back to {@link TextLook#plain()} instead of drawing half a look.
 *
 * <p>The feature ids and the rule that says which features a look needs mirror the server's {@code requiredFeatures}
 * ({@code contract/edit.styles.ts}): the six shipped looks need none, so a phone that lists no feature still draws them.
 */
public final class TextLookSupport {

    /** The feature ids {@link LookPaintPlan} and {@link TextLookRenderer} have a handler for, in the server's order. */
    public static final List<String> IMPLEMENTED_FEATURES = Collections.unmodifiableList(Arrays.asList(
            "text_gradient_ltr", "text_gradient_diag", "text_stroke_join", "box_gradient", "box_border",
            "box_border_ring", "box_glow", "opacity", "font_family", "font_weight", "case_as_written",
            "letter_spacing"));

    /** The six looks installed phones always drew: no feature needed, drawn as before. */
    public static final List<String> SHIPPED_LOOKS = Collections.unmodifiableList(Arrays.asList(
            "sticker", "label_pill", "3d", "glow", "gradient", "highlighter"));

    private TextLookSupport() {}

    /** The features {@code look} needs the phone to draw, in {@link #IMPLEMENTED_FEATURES} order. */
    public static List<String> requiredFeatures(TextLook look) {
        Set<String> n = new LinkedHashSet<>();
        TextLook.FillSpec tf = look.textFill;
        if ("gradient".equals(tf.kind) && "ltr".equals(tf.direction)) n.add("text_gradient_ltr");
        if ("gradient".equals(tf.kind) && "diag".equals(tf.direction)) n.add("text_gradient_diag");
        if (look.textStroke != null && !"round".equals(look.textStroke.join)) n.add("text_stroke_join");
        TextLook.Box box = look.box;
        if (box != null && "gradient".equals(box.fill.kind)) n.add("box_gradient");
        if (box != null && box.border != null) n.add("box_border");
        if (box != null && box.border != null && box.border.ring != null) n.add("box_border_ring");
        if (box != null && box.glow != null) n.add("box_glow");
        if (look.opacity != 1) n.add("opacity");
        if (!"sans".equals(look.font.family)) n.add("font_family");
        if (look.font.weight != 700) n.add("font_weight");
        if ("as_written".equals(look.caseMode)) n.add("case_as_written");
        if (look.letterSpacing != 0) n.add("letter_spacing");
        List<String> ordered = new ArrayList<>();
        for (String f : IMPLEMENTED_FEATURES) if (n.remove(f)) ordered.add(f);
        ordered.addAll(n);
        return ordered;
    }

    /**
     * Refuses a look the painter cannot draw completely.
     *
     * @throws UnsupportedLookException for a field without a handler (the field's name), or a needed feature that
     *                                  {@code implemented} lacks (the feature id)
     */
    public static void require(TextLook look, Collection<String> implemented) throws UnsupportedLookException {
        LookPaintPlan.room(look);
        for (String f : requiredFeatures(look)) {
            if (!implemented.contains(f)) throw new UnsupportedLookException(f);
        }
    }

    /** {@link #require(TextLook, Collection)} against what this build implements. */
    public static void require(TextLook look) throws UnsupportedLookException {
        require(look, IMPLEMENTED_FEATURES);
    }

    /** Whether the painter draws {@code look} completely. */
    public static boolean drawable(TextLook look) {
        try {
            require(look);
            return true;
        } catch (UnsupportedLookException e) {
            return false;
        }
    }

    /**
     * Whether the contract's layer order and colour-role numbers are the ones this painter draws. When they differ the
     * painter cannot honour the new looks (it draws the shipped six by their legacy rule), so none is listed. A key the
     * contract does not carry (an older server) counts as matching.
     */
    public static boolean contractMatches(EngineContract contract) {
        List<String> order = contract.getTextLayerOrder();
        if (!order.isEmpty()) {
            List<String> mine = new ArrayList<>();
            for (LookPaintPlan.Layer l : LookPaintPlan.Layer.values()) mine.add(l.id);
            if (!order.equals(mine)) return false;
        }
        Map<String, Double> roles = contract.getColorRoles();
        Double dark = roles.get("accent_dark.factor");
        Double light = roles.get("accent_light.paper_share");
        if (dark != null && dark != PaletteResolver.DARK_FACTOR) return false;
        return light == null || light == PaletteResolver.LIGHT_PAPER_SHARE;
    }
}
