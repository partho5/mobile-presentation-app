package com.jovoc.facecampresentationrecorder.aiedit.engine.plan;

import com.jovoc.facecampresentationrecorder.aiedit.engine.EngineJson;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.EngineContract;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.RegistryEntry;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads a plan (spec section 16) with org.json and checks it against the contract. Strict: a plan of an
 * unknown contract version, with an effect the registry does not have, or of a damaged shape is refused whole,
 * never half drawn. Accepts the plan object itself, or the job response {@code { id, status, plan, assets }}
 * (the plan is then taken from {@code plan} and the files from the sibling {@code assets}).
 */
public final class PlanParser {

    private PlanParser() {}

    public static Plan parse(String json, EngineContract contract) throws PlanParseException {
        if (json == null) throw bad("no plan");
        try {
            JSONObject root = new JSONObject(json);
            JSONObject p = root.has("plan") && root.get("plan") instanceof JSONObject
                    ? root.getJSONObject("plan") : root;
            // Version first, so a plan of a newer contract gets the clear answer even if its shape changed.
            int version = p.getInt("contract_version");
            if (version != contract.getVersion()) {
                throw new PlanParseException(PlanParseException.UNSUPPORTED_VERSION, "unknown contract version");
            }
            String level = p.getString("level");
            if (EngineContract.levelIndex(level) < 0) throw bad("unknown level");

            long outputDurationMs = p.getJSONObject("video").getLong("output_duration_ms");
            if (outputDurationMs < 0) throw bad("negative duration");

            JSONObject structure = p.getJSONObject("structure");
            List<Plan.Cut> cuts = new ArrayList<>();
            JSONArray ca = structure.getJSONArray("cuts");
            for (int i = 0; i < ca.length(); i++) {
                JSONObject c = ca.getJSONObject(i);
                long s = c.getLong("src_start_ms");
                long e = c.getLong("src_end_ms");
                if (s < 0 || e <= s) throw bad("bad cut");
                cuts.add(new Plan.Cut(s, e, EngineJson.optString(c, "reason")));
            }
            List<Plan.SpeedSegment> speeds = new ArrayList<>();
            JSONArray sa = structure.getJSONArray("speed_segments");
            for (int i = 0; i < sa.length(); i++) {
                JSONObject sp = sa.getJSONObject(i);
                long s = sp.getLong("src_start_ms");
                long e = sp.getLong("src_end_ms");
                double rate = sp.getDouble("rate");
                if (s < 0 || e <= s || !(rate > 0)) throw bad("bad speed segment");
                speeds.add(new Plan.SpeedSegment(s, e, rate));
            }

            List<Plan.FacecamKey> facecam = new ArrayList<>();
            JSONArray fa = p.getJSONArray("facecam_track");
            for (int i = 0; i < fa.length(); i++) {
                JSONObject f = fa.getJSONObject(i);
                facecam.add(new Plan.FacecamKey(f.getLong("t_ms"), f.getDouble("cx"), f.getDouble("cy"),
                        f.getDouble("radius")));
            }
            Collections.sort(facecam, new Comparator<Plan.FacecamKey>() {
                @Override
                public int compare(Plan.FacecamKey a, Plan.FacecamKey b) {
                    return Long.compare(a.tMs, b.tMs);
                }
            });

            Palette palette = parsePalette(p.optJSONObject("palette"), contract);

            List<PlanItem> items = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            JSONArray ia = p.getJSONArray("items");
            for (int i = 0; i < ia.length(); i++) {
                PlanItem item = parseItem(ia.getJSONObject(i), contract);
                if (!ids.add(item.id)) throw bad("duplicate item id");
                items.add(item);
            }
            Collections.sort(items, new Comparator<PlanItem>() {
                @Override
                public int compare(PlanItem a, PlanItem b) {
                    return Long.compare(a.startMs, b.startMs);
                }
            });

            JSONObject co = p.getJSONObject("captions");
            String captionEffect = co.getString("effect");
            RegistryEntry captionEntry = contract.effect(captionEffect);
            if (captionEntry == null) {
                throw new PlanParseException(PlanParseException.UNKNOWN_EFFECT, "unknown caption effect");
            }
            if (!"caption".equals(captionEntry.layer)) throw bad("not a caption effect");
            List<Integer> emphasis = new ArrayList<>();
            JSONArray ea = co.getJSONArray("emphasis_words");
            for (int i = 0; i < ea.length(); i++) emphasis.add(ea.getInt(i));

            List<String> warnings = EngineJson.optStrings(p, "warnings");

            // The files sit beside the plan in the job response; a bare plan object may carry them itself.
            JSONArray assetArray = root.has("assets") && !root.isNull("assets") ? root.getJSONArray("assets")
                    : (p.has("assets") && !p.isNull("assets") ? p.getJSONArray("assets") : null);
            List<PlanAsset> assets = parseAssets(assetArray);

            return new Plan(version, p.getInt("plan_version"), p.getString("session_id"), level,
                    outputDurationMs, cuts, speeds, facecam, palette, items,
                    new PlanCaptions(captionEffect, emphasis), warnings, assets);
        } catch (JSONException | IllegalArgumentException e) {
            throw new PlanParseException(PlanParseException.BAD_PLAN, "damaged plan", e);
        }
    }

    /** The {@code assets} array of a job response; null or missing gives an empty list. */
    public static List<PlanAsset> parseAssets(JSONArray a) throws JSONException {
        List<PlanAsset> out = new ArrayList<>();
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            String kind = o.getString("kind");
            if (!"lottie".equals(kind) && !"icon".equals(kind) && !"sound".equals(kind)) {
                throw new JSONException("unknown asset kind");
            }
            out.add(new PlanAsset(o.getString("id"), kind, o.getString("url"),
                    o.optInt("native_duration_ms", 0), o.optInt("width", 0), o.optInt("height", 0)));
        }
        return out;
    }

    /** The server's resolved colours; without them (an older plan) the contract's multi colours. */
    private static Palette parsePalette(JSONObject o, EngineContract contract) throws JSONException {
        if (o != null && o.has("colors")) {
            List<String> colors = EngineJson.strings(o.getJSONArray("colors"));
            if (!colors.isEmpty()) return new Palette(o.optString("mode", "multi"), colors);
        }
        return new Palette("multi", contract.getPalette().multi);
    }

    private static PlanItem parseItem(JSONObject o, EngineContract contract)
            throws JSONException, PlanParseException {
        String id = o.getString("id");
        String effect = o.getString("effect");
        if (id.isEmpty()) throw bad("empty item id");
        RegistryEntry entry = contract.effect(effect);
        if (entry == null) throw new PlanParseException(PlanParseException.UNKNOWN_EFFECT, "unknown effect");
        long start = o.getLong("start_ms");
        long end = o.getLong("end_ms");
        if (start < 0 || end < start) throw bad("bad item time");

        int wordIndex = -1;
        long wordOffset = 0;
        JSONObject anchor = o.optJSONObject("anchor");
        if (anchor != null) {
            wordIndex = anchor.getInt("word_index");
            wordOffset = anchor.optLong("offset_ms", 0);
        }
        String zone = null;
        JSONObject position = o.optJSONObject("position");
        if (position != null) {
            zone = position.getString("zone");
            if (contract.zone(zone) == null) throw bad("unknown zone");
        }
        PlanComponent component = null;
        JSONObject co = o.optJSONObject("component");
        if (co != null) {
            component = parseComponent(co);
            if (!entry.allowedComponents.contains(component.type)) throw bad("component not allowed");
        }
        String soundId = null;
        long soundOffset = 0;
        JSONObject sound = o.optJSONObject("sound");
        if (sound != null) {
            soundId = sound.getString("id");
            soundOffset = sound.optLong("offset_ms", 0);
        }
        return new PlanItem(id, effect, o.getString("layer"), o.getString("target"), start, end,
                wordIndex, wordOffset, zone, component,
                o.has("params") ? EngineJson.primitives(o.getJSONObject("params"))
                        : Collections.<String, Object>emptyMap(),
                soundId, soundOffset, EngineJson.optString(o, "reason"));
    }

    private static PlanComponent parseComponent(JSONObject c) throws JSONException, PlanParseException {
        String type = c.getString("type");
        if (PlanComponent.STYLED_TEXT.equals(type)) {
            return PlanComponent.styledText(c.getString("kind"), c.getString("text"),
                    c.getString("style_preset"), c.getInt("color_slot"));
        }
        if (PlanComponent.LOTTIE.equals(type) || PlanComponent.ICON_IMAGE.equals(type)) {
            String fitMode = null;
            double factor = 1.0;
            JSONObject fit = c.optJSONObject("fit");
            if (fit != null) {
                fitMode = fit.getString("mode");
                factor = fit.getDouble("factor");
            }
            return PlanComponent.asset(type, c.getString("asset_id"), c.optInt("native_duration_ms", 0),
                    fitMode, factor);
        }
        throw bad("unknown component");
    }

    private static PlanParseException bad(String message) {
        return new PlanParseException(PlanParseException.BAD_PLAN, message);
    }
}
