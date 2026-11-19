package com.jovoc.facecampresentationrecorder.aiedit.engine.contract;

import android.content.Context;

import com.jovoc.facecampresentationrecorder.aiedit.engine.EngineJson;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The engine contract the server and the phone share (spec sections 6, 12, 13, 14): level rules, speed rules,
 * protection, outputs, zones, the effect registry, text looks, palette, phrases and sounds. Read from
 * {@code assets/aiedit/contract.json}, or from a newer downloaded copy in {@code filesDir/aiedit/}. Immutable;
 * a contract with a version this build does not know is refused, never half-read.
 */
public final class EngineContract {

    /** The contract version this build understands. */
    public static final int SUPPORTED_VERSION = 2;
    /** Levels, cheapest first. */
    public static final String[] LEVEL_ORDER = {"basic", "standard", "heavy"};
    /** Output ratios, 9:16 first everywhere they are listed. */
    public static final String[] OUTPUT_ORDER = {"9:16", "4:5", "1:1"};

    private static final String ASSET_PATH = "aiedit/contract.json";

    /** What one level may use. */
    public static final class LevelRule {
        public final String level;
        public final int maxFeatureEffects;
        public final int maxIcons;
        public final List<String> textKinds;
        public final boolean transitions;
        public final boolean sounds;
        public final boolean perception;
        public final boolean writer;

        LevelRule(String level, JSONObject o) throws JSONException {
            this.level = level;
            maxFeatureEffects = o.getInt("max_feature_effects");
            maxIcons = o.getInt("max_icons");
            textKinds = EngineJson.strings(o.getJSONArray("text_kinds"));
            transitions = o.getBoolean("transitions");
            sounds = o.getBoolean("sounds");
            perception = o.getBoolean("perception");
            writer = o.getBoolean("writer");
        }
    }

    /** The speed limits every effect obeys, in milliseconds unless a name says otherwise. */
    public static final class SpeedRules {
        public final int[] entryMs;
        public final int[] exitMs;
        public final int[] transitionMs;
        public final int[] beatMs;
        public final int[] flashMs;
        public final int pushInMaxMs;
        public final int slowMotionMaxMs;
        public final int textHoldBaseMs;
        public final int textHoldPerWordMs;
        public final int textHoldMaxMs;
        public final int[] iconHoldMs;
        public final double clipMaxFactor;

        SpeedRules(JSONObject o) throws JSONException {
            entryMs = pair(o, "entry_ms");
            exitMs = pair(o, "exit_ms");
            transitionMs = pair(o, "transition_ms");
            beatMs = pair(o, "beat_ms");
            flashMs = pair(o, "flash_ms");
            pushInMaxMs = o.getInt("push_in_max_ms");
            slowMotionMaxMs = o.getInt("slow_motion_max_ms");
            JSONObject h = o.getJSONObject("text_hold");
            textHoldBaseMs = h.getInt("base_ms");
            textHoldPerWordMs = h.getInt("per_word_ms");
            textHoldMaxMs = h.getInt("max_ms");
            iconHoldMs = pair(o, "icon_hold_ms");
            clipMaxFactor = o.getDouble("clip_max_factor");
        }

        private static int[] pair(JSONObject o, String key) throws JSONException {
            JSONArray a = o.getJSONArray(key);
            if (a.length() != 2) throw new JSONException("range needs two numbers: " + key);
            return new int[]{a.getInt(0), a.getInt(1)};
        }

        /** How long a text overlay of {@code words} words is held: base plus a time per word, capped. */
        public int textHoldMs(int words) {
            return Math.min(textHoldMaxMs, textHoldBaseMs + textHoldPerWordMs * Math.max(0, words));
        }
    }

    /** How protected spans are built from busy spans (spec section 10). */
    public static final class Protection {
        public final int paddingMs;
        public final int minWindowMs;
        public final int mergeGapMs;

        Protection(JSONObject o) throws JSONException {
            paddingMs = o.getInt("padding_ms");
            minWindowMs = o.getInt("min_window_ms");
            mergeGapMs = o.getInt("merge_gap_ms");
        }
    }

    /** The multi colour accents and the ink and paper neutrals the text looks draw with. */
    public static final class PaletteSpec {
        public final List<String> multi;
        public final String ink;
        public final String paper;

        PaletteSpec(JSONObject o) throws JSONException {
            multi = EngineJson.strings(o.getJSONArray("multi"));
            ink = o.getString("ink");
            paper = o.getString("paper");
            if (multi.isEmpty()) throw new JSONException("empty palette");
        }
    }

    /** One closed commentary phrase: mood tags and its text per language. */
    public static final class Phrase {
        public final String id;
        public final List<String> mood;
        public final Map<String, String> text;

        Phrase(JSONObject o) throws JSONException {
            id = o.getString("id");
            mood = EngineJson.strings(o.getJSONArray("mood"));
            Map<String, String> t = new LinkedHashMap<>();
            JSONObject to = o.getJSONObject("text");
            Iterator<String> keys = to.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                t.put(k, to.getString(k));
            }
            text = Collections.unmodifiableMap(t);
        }
    }

    private final int version;
    private final Map<String, LevelRule> levels;
    private final SpeedRules speed;
    private final Protection protection;
    private final Map<String, OutputSpec> outputs;
    private final Map<String, double[]> zones;
    private final List<RegistryEntry> effects;
    private final Map<String, RegistryEntry> effectsById;
    private final List<TextLook> textLooks;
    private final Map<String, TextLook> textLooksById;
    private final List<String> rejectedLookIds;
    private final List<String> textFeatures;
    private final List<String> textLayerOrder;
    private final Map<String, Double> colorRoles;
    private final String lookVectorsJson;
    private final PaletteSpec palette;
    private final List<Phrase> phrases;
    private final List<String> sounds;

    private EngineContract(JSONObject o) throws JSONException {
        version = o.getInt("contract_version");

        Map<String, LevelRule> lv = new LinkedHashMap<>();
        JSONObject lo = o.getJSONObject("levels");
        for (String name : LEVEL_ORDER) lv.put(name, new LevelRule(name, lo.getJSONObject(name)));
        levels = Collections.unmodifiableMap(lv);

        speed = new SpeedRules(o.getJSONObject("speed"));
        protection = new Protection(o.getJSONObject("protection"));

        // Fixed order, not the JSON object's: some org.json builds do not keep key order.
        Map<String, OutputSpec> out = new LinkedHashMap<>();
        JSONObject oo = o.getJSONObject("outputs");
        for (String ratio : OUTPUT_ORDER) {
            if (oo.has(ratio)) out.put(ratio, OutputSpec.fromJson(ratio, oo.getJSONObject(ratio)));
        }
        Iterator<String> extra = oo.keys();
        while (extra.hasNext()) {
            String ratio = extra.next();
            if (!out.containsKey(ratio)) out.put(ratio, OutputSpec.fromJson(ratio, oo.getJSONObject(ratio)));
        }
        if (out.isEmpty()) throw new JSONException("no outputs");
        outputs = Collections.unmodifiableMap(out);

        Map<String, double[]> zn = new LinkedHashMap<>();
        JSONObject zo = o.getJSONObject("zones");
        Iterator<String> zk = zo.keys();
        while (zk.hasNext()) {
            String id = zk.next();
            double[] z = EngineJson.doubles(zo.getJSONArray(id));
            if (z.length != 2) throw new JSONException("zone needs two numbers: " + id);
            zn.put(id, z);
        }
        zones = Collections.unmodifiableMap(zn);

        List<RegistryEntry> ef = new ArrayList<>();
        Map<String, RegistryEntry> efIds = new LinkedHashMap<>();
        JSONArray ea = o.getJSONArray("effects");
        for (int i = 0; i < ea.length(); i++) {
            RegistryEntry e = RegistryEntry.fromJson(ea.getJSONObject(i));
            if (efIds.put(e.id, e) != null) throw new JSONException("duplicate effect id");
            ef.add(e);
        }
        effects = Collections.unmodifiableList(ef);
        effectsById = Collections.unmodifiableMap(efIds);

        List<TextLook> tl = new ArrayList<>();
        Map<String, TextLook> tlIds = new LinkedHashMap<>();
        List<String> rejected = new ArrayList<>();
        JSONArray ta = o.getJSONArray("text_styles");
        for (int i = 0; i < ta.length(); i++) {
            // One look that cannot be read is dropped (and so never listed to the server); the rest of the contract stays
            JSONObject lo1 = ta.optJSONObject(i);
            try {
                if (lo1 == null) throw new JSONException("look is not an object");
                TextLook t = TextLook.fromJson(lo1);
                if (tlIds.containsKey(t.id)) throw new JSONException("duplicate look id");
                tlIds.put(t.id, t);
                tl.add(t);
            } catch (JSONException | IllegalArgumentException e) {
                String id = lo1 == null ? null : lo1.optString("id", null);
                rejected.add(id != null && id.matches("^[A-Za-z0-9._:-]{1,64}$") ? id : "#" + i);
            }
        }
        if (tl.isEmpty()) throw new JSONException("no readable text look");
        textLooks = Collections.unmodifiableList(tl);
        textLooksById = Collections.unmodifiableMap(tlIds);
        rejectedLookIds = Collections.unmodifiableList(rejected);

        // The text-styles keys the server added; absent on an older contract, read strictly when present
        textFeatures = o.has("text_features") ? EngineJson.strings(o.getJSONArray("text_features"))
                : Collections.<String>emptyList();
        textLayerOrder = o.has("text_layer_order") ? EngineJson.strings(o.getJSONArray("text_layer_order"))
                : Collections.<String>emptyList();
        Map<String, Double> roles = new LinkedHashMap<>();
        if (o.has("color_roles")) {
            JSONObject ro = o.getJSONObject("color_roles");
            Iterator<String> rk = ro.keys();
            while (rk.hasNext()) {
                String role = rk.next();
                JSONObject r = ro.getJSONObject(role);
                Iterator<String> fk = r.keys();
                if (!fk.hasNext()) throw new JSONException("empty colour role");
                String key = fk.next();
                roles.put(role + "." + key, r.getDouble(key));
            }
        }
        colorRoles = Collections.unmodifiableMap(roles);
        lookVectorsJson = o.has("look_vectors") ? o.getJSONArray("look_vectors").toString() : "[]";

        palette = new PaletteSpec(o.getJSONObject("palette"));

        List<Phrase> ph = new ArrayList<>();
        JSONArray pa = o.getJSONArray("phrases");
        for (int i = 0; i < pa.length(); i++) ph.add(new Phrase(pa.getJSONObject(i)));
        phrases = Collections.unmodifiableList(ph);

        sounds = EngineJson.strings(o.getJSONArray("sounds"));
    }

    /**
     * Reads a contract from its JSON text.
     *
     * @throws ContractException when the text is damaged or its {@code contract_version} is not
     *                           {@link #SUPPORTED_VERSION}
     */
    public static EngineContract fromJson(String json) throws ContractException {
        if (json == null) throw new ContractException("no contract");
        try {
            JSONObject o = new JSONObject(json);
            if (o.getInt("contract_version") != SUPPORTED_VERSION) {
                throw new ContractException("unknown contract version");
            }
            return new EngineContract(o);
        } catch (JSONException | IllegalArgumentException e) {
            throw new ContractException("damaged contract", e);
        }
    }

    /**
     * The contract to use: the downloaded copy when it is readable and of a known version, else the one
     * bundled in the app's assets.
     */
    public static EngineContract load(Context context) throws ContractException {
        File downloaded = downloadedFile(context);
        if (downloaded.isFile()) {
            try (InputStream in = new FileInputStream(downloaded)) {
                return fromJson(readAll(in));
            } catch (IOException | ContractException e) {
                // Fall through to the bundled copy.
            }
        }
        try (InputStream in = context.getAssets().open(ASSET_PATH)) {
            return fromJson(readAll(in));
        } catch (IOException e) {
            throw new ContractException("bundled contract unreadable", e);
        }
    }

    /** Where a newer contract downloaded from the server is kept. */
    public static File downloadedFile(Context context) {
        return new File(new File(context.getFilesDir(), "aiedit"), "contract.json");
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Position of a level in {@link #LEVEL_ORDER}, or -1 for an unknown name. */
    public static int levelIndex(String level) {
        for (int i = 0; i < LEVEL_ORDER.length; i++) if (LEVEL_ORDER[i].equals(level)) return i;
        return -1;
    }

    public int getVersion() {
        return version;
    }

    /** The rule of a level, or null for an unknown name. */
    public LevelRule level(String level) {
        return levels.get(level);
    }

    public SpeedRules getSpeed() {
        return speed;
    }

    public Protection getProtection() {
        return protection;
    }

    /** The output ratios in listing order, 9:16 first. */
    public List<String> outputRatios() {
        return Collections.unmodifiableList(new ArrayList<>(outputs.keySet()));
    }

    /** The spec of an output ratio, or null for an unknown one. */
    public OutputSpec output(String ratio) {
        return outputs.get(ratio);
    }

    /** A zone's centre as fractions of the output's safe rectangle ({x, y}), or null for an unknown zone. */
    public double[] zone(String zoneId) {
        double[] z = zones.get(zoneId);
        return z == null ? null : z.clone();
    }

    /** Every effect of the registry. */
    public List<RegistryEntry> getEffects() {
        return effects;
    }

    /** One effect by id, or null. */
    public RegistryEntry effect(String id) {
        return effectsById.get(id);
    }

    /** The effects a job may use: at its level or below and, when given, ones the build can render. */
    public List<RegistryEntry> effectsForLevel(String level, Capabilities capabilities) {
        List<RegistryEntry> out = new ArrayList<>();
        for (RegistryEntry e : effects) {
            if (e.allowedAt(level) && (capabilities == null || capabilities.canRender(e.id))) out.add(e);
        }
        return out;
    }

    public List<TextLook> getTextLooks() {
        return textLooks;
    }

    /** One text look by id, or null. */
    public TextLook textLook(String id) {
        return textLooksById.get(id);
    }

    /** Ids of the looks the contract carried but this build could not read (dropped, never listed); empty when none. */
    public List<String> getRejectedLookIds() {
        return rejectedLookIds;
    }

    /** The feature ids the server lists in {@code text_features}; empty for a contract without the key. */
    public List<String> getTextFeatures() {
        return textFeatures;
    }

    /** The server's {@code text_layer_order}, back to front; empty for a contract without the key. */
    public List<String> getTextLayerOrder() {
        return textLayerOrder;
    }

    /** The served colour-role numbers as {@code role.key} to value (for example {@code accent_light.paper_share}). */
    public Map<String, Double> getColorRoles() {
        return colorRoles;
    }

    /** The served {@code look_vectors} (the conformance runner's input), an empty array when absent. */
    public JSONArray lookVectors() {
        try {
            return new JSONArray(lookVectorsJson);
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    public PaletteSpec getPalette() {
        return palette;
    }

    public List<Phrase> getPhrases() {
        return phrases;
    }

    public List<String> getSounds() {
        return sounds;
    }
}
