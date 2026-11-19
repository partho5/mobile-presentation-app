package com.jovoc.facecampresentationrecorder.aiedit.engine.text;

import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.EngineContract;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.Palette;

/**
 * Turns the colour roles of a text look ({@code accent}, {@code accent_dark}, {@code accent_light}, {@code on_accent}, {@code ink},
 * {@code paper}) into ARGB ints. The accent is the plan palette colour of the item's {@code color_slot}; ink and
 * paper are the contract's neutrals. Pure Java, no Android classes.
 */
public final class PaletteResolver {

    public static final String ACCENT = "accent";
    public static final String ACCENT_DARK = "accent_dark";
    public static final String ACCENT_LIGHT = "accent_light";
    public static final String ON_ACCENT = "on_accent";
    public static final String INK = "ink";
    public static final String PAPER = "paper";

    /** The accent's brightness factor for {@code accent_dark}. */
    static final double DARK_FACTOR = 0.55;
    /** The share of paper in {@code accent_light} (the accent keeps the rest); the server serves it as {@code color_roles}. */
    static final double LIGHT_PAPER_SHARE = 0.65;

    private final Palette palette;
    private final int ink;
    private final int paper;

    public PaletteResolver(Palette palette, EngineContract.PaletteSpec spec) {
        this.palette = palette;
        this.ink = parse(spec.ink);
        this.paper = parse(spec.paper);
    }

    /** The colour of {@code role} for an item with {@code colorSlot}; an unknown role is paper. */
    public int resolve(String role, int colorSlot) {
        if (INK.equals(role)) return ink;
        if (PAPER.equals(role)) return paper;
        int accent = parse(palette.colorFor(colorSlot));
        if (ACCENT.equals(role)) return accent;
        if (ACCENT_DARK.equals(role)) return darken(accent, DARK_FACTOR);
        if (ACCENT_LIGHT.equals(role)) return mix(accent, paper, LIGHT_PAPER_SHARE);
        if (ON_ACCENT.equals(role)) return onAccent(accent);
        return paper;
    }

    /** Ink or paper, whichever reads better on {@code accent} (the higher contrast ratio). */
    public int onAccent(int accent) {
        double l = luminance(accent);
        double withPaper = (luminance(paper) + 0.05) / (l + 0.05);
        double withInk = (l + 0.05) / (luminance(ink) + 0.05);
        return withInk >= withPaper ? ink : paper;
    }

    /** {@code #RRGGBB} or {@code #AARRGGBB} as an ARGB int (opaque for the short form). */
    public static int parse(String hex) {
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        long v = Long.parseLong(h, 16);
        if (h.length() <= 6) v |= 0xFF000000L;
        return (int) v;
    }

    /** Every colour channel times {@code factor}, alpha kept. */
    public static int darken(int argb, double factor) {
        int r = (int) Math.round(((argb >> 16) & 0xFF) * factor);
        int g = (int) Math.round(((argb >> 8) & 0xFF) * factor);
        int b = (int) Math.round((argb & 0xFF) * factor);
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    /** Each colour channel of {@code a} times {@code 1 - share} plus the same channel of {@code b} times {@code share}; opaque. */
    public static int mix(int a, int b, double share) {
        int r = (int) Math.round(((a >> 16) & 0xFF) * (1 - share) + ((b >> 16) & 0xFF) * share);
        int g = (int) Math.round(((a >> 8) & 0xFF) * (1 - share) + ((b >> 8) & 0xFF) * share);
        int bl = (int) Math.round((a & 0xFF) * (1 - share) + (b & 0xFF) * share);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** {@code argb} with its alpha multiplied by {@code alpha} (0..1). */
    public static int withAlpha(int argb, double alpha) {
        int a = (int) Math.round(((argb >>> 24) & 0xFF) * Math.max(0, Math.min(1, alpha)));
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    /** WCAG relative luminance, 0 (black) to 1 (white). */
    static double luminance(int argb) {
        return 0.2126 * channel((argb >> 16) & 0xFF) + 0.7152 * channel((argb >> 8) & 0xFF)
                + 0.0722 * channel(argb & 0xFF);
    }

    private static double channel(int v) {
        double c = v / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
