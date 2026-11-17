package com.jovoc.facecampresentationrecorder.aiedit.engine.contract;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The effect and text-look ids this build can render. Sent with every request so the server plans only what
 * the phone can draw. Each card that adds a renderer adds its ids to {@link #current()}.
 */
public final class Capabilities {

    private final Set<String> effects;
    private final Set<String> textStyles;

    public Capabilities(Collection<String> effects, Collection<String> textStyles) {
        this.effects = Collections.unmodifiableSet(new LinkedHashSet<>(effects));
        this.textStyles = Collections.unmodifiableSet(new LinkedHashSet<>(textStyles));
    }

    /** What this build renders: the caption highlight and the jump cut, nothing else yet. */
    public static Capabilities current() {
        return new Capabilities(Arrays.asList("c_word_highlight", "t_jump_cut"),
                Collections.<String>emptyList());
    }

    public boolean canRender(String effectId) {
        return effects.contains(effectId);
    }

    public boolean canStyle(String textLookId) {
        return textStyles.contains(textLookId);
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
                    .put("text_styles", new JSONArray(textStyles));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }
}
