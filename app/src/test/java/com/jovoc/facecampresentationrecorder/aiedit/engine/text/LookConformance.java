package com.jovoc.facecampresentationrecorder.aiedit.engine.text;

import com.jovoc.facecampresentationrecorder.aiedit.TestStrings;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.EngineContract;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.TextLook;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.Palette;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The conformance runner (gate 2): the server's {@code look_vectors} against the paint plan. For every look of the contract
 * and every probe of the string matrix, in 3 ratios and 2 colour slots, it builds the plan with a fake measurer and
 * asserts each fact (layers and their order, gradient direction, shadow offset, stroke width, box padding, ...). It also
 * proves that nothing goes unhandled: every {@link TextLook} field has a handler, every field a fact names has a handler,
 * every field a look carries has a vector, and a look the plan refuses is a failure.
 *
 * <p>The handlers are the {@link FactCheck}s in {@link #defaultChecks()}; a check owns the contract fields it declares. A
 * runner given a check set without one handler (or a look carrying a field none declares) returns violations, which is
 * how the runner itself is tested. Pure JVM: no Bitmap, Canvas, Paint or android.icu.
 */
final class LookConformance {

    private static final double EPS = 1e-9;
    /** Fake line height as a share of the size (the real one comes from the font). */
    private static final double LINE_HEIGHT = 1.2;
    private static final Pattern GRAPHEME = Pattern.compile("\\X");
    static final String[] RATIOS = {"9:16", "4:5", "1:1"};
    /** The accents the colour slots resolve to; two slots so a role really follows the item's accent. */
    private static final String[] ACCENTS = {"#FF8040", "#2979FF"};

    /** TextLook's Java fields that say nothing to draw. */
    private static final Set<String> NOT_DRAWN_FIELDS = new HashSet<>(Arrays.asList("id", "hasNewBlocks", "unknownKeys"));

    /** TextLook Java field to the contract key (and the vectors' field name) it carries. */
    private static final Map<String, String> JAVA_TO_CONTRACT = new LinkedHashMap<>();

    static {
        String[][] m = {{"fill", "fill"}, {"stroke", "stroke"}, {"shadow", "shadow"}, {"glow", "glow"},
                {"extrude", "extrude"}, {"background", "background"}, {"bar", "bar"}, {"tiltDeg", "tilt_deg"},
                {"textFill", "text_fill"}, {"textStroke", "text_stroke"}, {"textShadow", "text_shadow"}, {"box", "box"},
                {"opacity", "opacity"}, {"font", "font"}, {"caseMode", "case"}, {"letterSpacing", "letter_spacing"}};
        for (String[] e : m) JAVA_TO_CONTRACT.put(e[0], e[1]);
    }

    /** What a check sees: one look, one probe, one ratio, the plan built for them. */
    static final class Ctx {
        final String look;
        final TextLook textLook;
        final LookPaintPlan plan;
        /** The look's facts, so a check can read a sibling fact. */
        final JSONArray vectorFacts;
        final JSONObject probe;
        final EngineContract contract;
        final PaletteResolver colors;
        final int slot;
        private final Set<String> out;

        Ctx(String look, TextLook textLook, LookPaintPlan plan, JSONArray facts, JSONObject probe, EngineContract contract,
            PaletteResolver colors, int slot, Set<String> out) {
            this.look = look;
            this.textLook = textLook;
            this.plan = plan;
            this.vectorFacts = facts;
            this.probe = probe;
            this.contract = contract;
            this.colors = colors;
            this.slot = slot;
            this.out = out;
        }

        void fail(String what) {
            out.add(look + ": " + what);
        }

        void expect(boolean ok, String what) {
            if (!ok) fail(what);
        }

        void near(double expected, double actual, String what) {
            if (Math.abs(expected - actual) > EPS) fail(what + " expected " + expected + " but plan has " + actual);
        }

        /** The ARGB the plan must hold for a colour role at an alpha. */
        int argb(String role, double alpha) {
            return PaletteResolver.withAlpha(colors.resolve(role, slot), alpha);
        }

        LookPaintPlan.Op op(LookPaintPlan.Kind kind) {
            LookPaintPlan.Op found = null;
            for (LookPaintPlan.Op o : plan.ops) {
                if (o.kind == kind) {
                    if (found != null) fail("two " + kind + " operations");
                    found = o;
                }
            }
            return found;
        }
    }

    /** One handler: the fact kind it checks, the contract fields it owns, and the check. */
    abstract static class FactCheck {
        final String kind;
        final Set<String> fields;

        FactCheck(String kind, String... fields) {
            this.kind = kind;
            this.fields = new HashSet<>(Arrays.asList(fields));
        }

        abstract void check(Ctx c, JSONObject fact) throws JSONException, UnsupportedLookException;
    }

    private final Map<String, FactCheck> checks = new LinkedHashMap<>();

    LookConformance(List<FactCheck> checks) {
        for (FactCheck c : checks) this.checks.put(c.kind, c);
    }

    static LookConformance standard() {
        return new LookConformance(defaultChecks());
    }

    /** This runner without the handler of {@code kind}: a look the vectors exercise through it must then fail. */
    static LookConformance without(String kind) {
        List<FactCheck> all = new ArrayList<>();
        for (FactCheck c : defaultChecks()) if (!c.kind.equals(kind)) all.add(c);
        return new LookConformance(all);
    }

    /** The contract fields some handler owns. */
    Set<String> handledFields() {
        Set<String> s = new HashSet<>();
        for (FactCheck c : checks.values()) s.addAll(c.fields);
        return s;
    }

    /**
     * The {@link TextLook} fields (by Java name) that have no handler: a new field added to the class without a handler
     * shows here. Reflection, so the class cannot grow a field unseen.
     */
    List<String> fieldsWithoutHandler() {
        List<String> missing = new ArrayList<>();
        Set<String> handled = handledFields();
        for (Field f : TextLook.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers()) || NOT_DRAWN_FIELDS.contains(f.getName())) continue;
            String contractKey = JAVA_TO_CONTRACT.get(f.getName());
            if (contractKey == null || !handled.contains(contractKey)) missing.add(f.getName());
        }
        return missing;
    }

    // ---- the run ----

    /**
     * Checks every look of {@code contractJson} against {@code vectors}.
     *
     * @return the violations, empty when the phone conforms
     */
    List<String> run(String contractJson, JSONArray vectors) throws JSONException, java.io.IOException {
        Set<String> out = new LinkedHashSet<>();
        EngineContract contract;
        try {
            contract = EngineContract.fromJson(contractJson);
        } catch (com.jovoc.facecampresentationrecorder.aiedit.engine.contract.ContractException e) {
            return Collections.singletonList("the contract does not parse");
        }
        JSONObject root = new JSONObject(contractJson);
        JSONArray styles = root.getJSONArray("text_styles");

        for (String f : fieldsWithoutHandler()) out.add("TextLook field " + f + " has no handler");
        if (!contract.getRejectedLookIds().isEmpty()) out.add("looks not readable: " + contract.getRejectedLookIds());
        if (!TextLookSupport.contractMatches(contract)) out.add("layer order or colour roles differ from the painter's");

        // One vector per look, in the order of text_styles
        List<String> styleIds = new ArrayList<>();
        for (int i = 0; i < styles.length(); i++) styleIds.add(styles.getJSONObject(i).getString("id"));
        List<String> vectorIds = new ArrayList<>();
        for (int i = 0; i < vectors.length(); i++) vectorIds.add(vectors.getJSONObject(i).getString("look"));
        if (!styleIds.equals(vectorIds)) out.add("vectors " + vectorIds + " are not one per look in " + styleIds);

        Map<String, JSONObject> rawById = new LinkedHashMap<>();
        for (int i = 0; i < styles.length(); i++) rawById.put(styles.getJSONObject(i).getString("id"), styles.getJSONObject(i));

        for (int v = 0; v < vectors.length(); v++) {
            JSONObject vec = vectors.getJSONObject(v);
            String id = vec.getString("look");
            JSONObject raw = rawById.get(id);
            TextLook look = contract.textLook(id);
            if (raw == null || look == null) {
                out.add(id + ": no such look in text_styles");
                continue;
            }
            runLook(contract, id, raw, look, vec, out);
        }
        return new ArrayList<>(out);
    }

    private void runLook(EngineContract contract, String id, JSONObject raw, TextLook look, JSONObject vec,
                         Set<String> out) throws JSONException {
        JSONArray facts = vec.getJSONArray("facts");
        JSONArray probes = vec.getJSONArray("probes");
        Set<String> handled = handledFields();

        // Gate 2: every field the look carries has a handler and a vector; every field a fact names has a handler
        Set<String> factFields = new HashSet<>();
        for (int i = 0; i < facts.length(); i++) {
            JSONObject f = facts.getJSONObject(i);
            String kind = f.getString("kind");
            if (!checks.containsKey(kind)) out.add(id + ": no handler for the " + kind + " fact");
            JSONArray fl = f.getJSONArray("fields");
            for (int j = 0; j < fl.length(); j++) {
                factFields.add(fl.getString(j));
                if (!handled.contains(fl.getString(j))) out.add(id + ": no handler for field " + fl.getString(j));
            }
        }
        Iterator<String> keys = raw.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            if (k.equals("id") || raw.isNull(k)) continue;
            if (!handled.contains(k)) out.add(id + ": no handler for field " + k);
            if (!factFields.contains(k)) out.add(id + ": field " + k + " has no vector");
        }

        // A look the painter refuses is a failure here: the contract lists it, so the phone must draw it
        try {
            LookPaintPlan.room(look);
        } catch (UnsupportedLookException e) {
            out.add(id + ": the painter refuses the look (" + e.field + ")");
            return;
        }
        if (!TextLookSupport.drawable(look)) out.add(id + ": the painter does not list the look as drawable");

        checkProbes(id, probes, out);
        for (int s = 0; s < ACCENTS.length; s++) {
            PaletteResolver colors = new PaletteResolver(new Palette("multi", Arrays.asList(ACCENTS)), contract.getPalette());
            for (String ratio : RATIOS) {
                int outW = contract.output(ratio).width;
                float wanted = 96f * outW / 1080f;
                int maxW = Math.round(0.8f * outW);
                for (int p = 0; p < probes.length(); p++) {
                    JSONObject probe = probes.getJSONObject(p);
                    runCombo(contract, id, look, facts, probe, colors, s, ratio, wanted, maxW, out);
                }
            }
        }
    }

    /** The probes are the string matrix: same ids, texts, languages and reading directions as {@code TestStrings}. */
    private static void checkProbes(String id, JSONArray probes, Set<String> out) throws JSONException {
        if (probes.length() != TestStrings.ALL.size()) {
            out.add(id + ": " + probes.length() + " probes, the matrix has " + TestStrings.ALL.size());
            return;
        }
        for (int i = 0; i < probes.length(); i++) {
            JSONObject p = probes.getJSONObject(i);
            TestStrings.Entry e = TestStrings.ALL.get(i);
            if (!e.id.equals(p.getString("probe")) || !e.text.equals(p.getString("text"))
                    || !e.language.equals(p.getString("language"))) {
                out.add(id + ": probe " + p.getString("probe") + " is not the matrix entry " + e.id);
            }
            boolean rtl = "rtl".equals(p.getString("direction"));
            if (rtl != TextLayoutPolicy.isRtl(e.text)) out.add(id + ": probe " + e.id + " direction " + p.getString("direction")
                    + " differs from the paragraph direction of its text");
        }
    }

    private void runCombo(EngineContract contract, String id, TextLook look, JSONArray facts, JSONObject probe,
                          PaletteResolver colors, int slot, String ratio, float wanted, int maxW,
                          Set<String> out) throws JSONException {
        String shown = probe.getString("text_drawn");
        LookPaintPlan.Room room;
        try {
            room = LookPaintPlan.room(look);
        } catch (UnsupportedLookException e) {
            out.add(id + ": the painter refuses the look (" + e.field + ")");
            return;
        }
        double spacing = LookTypography.letterSpacing(shown, look.letterSpacing);
        Ruler ruler = new Ruler(room, maxW, spacing);
        TextLayoutPolicy.Result fit = TextLayoutPolicy.fit(shown, wanted, ruler);
        boolean overCap = probe.getString("probe").startsWith("over_cap");
        if (fit.skipped && !overCap) out.add(id + ": " + probe.getString("probe") + " is skipped in " + ratio + " (no room)");
        // A skipped text is never drawn; the facts are still checked on a one-line stand-in so no look goes unchecked
        TextLayoutPolicy.Result forPlan = fit.skipped
                ? new TextLayoutPolicy.Result(false, wanted, Collections.singletonList(shown), fit.rtl, false) : fit;
        LookPaintPlan plan;
        try {
            plan = LookPaintPlan.build(look, colors, slot, forPlan);
        } catch (UnsupportedLookException e) {
            out.add(id + ": the painter refuses the look (" + e.field + ")");
            return;
        }
        Ctx c = new Ctx(id, look, plan, facts, probe, contract, colors, slot, out);

        c.expect(find(facts, "layers") != null, "no layers fact");
        checkOpsMatchFacts(c, facts);
        for (int i = 0; i < facts.length(); i++) {
            JSONObject f = facts.getJSONObject(i);
            FactCheck check = checks.get(f.getString("kind"));
            if (check == null) continue;   // reported once per look
            try {
                check.check(c, f);
            } catch (UnsupportedLookException e) {
                c.fail(f.getString("kind") + " fact: the painter refuses " + e.field);
            }
        }
        if (!fit.skipped) checkGeometry(c, facts, fit, ratio, wanted, maxW, ruler);
    }

    // ---- facts that span the plan ----

    /** The layers the plan draws, back to front, equal the vector's {@code draws} and follow the contract's order. */
    private static void checkLayers(Ctx c, JSONObject layers) throws JSONException {
        List<String> order = c.contract.getTextLayerOrder();
        List<String> expected = strings(layers.getJSONArray("draws"));
        int last = -1;
        for (String l : expected) {
            int at = order.indexOf(l);
            if (at < 0) c.fail("layer " + l + " is not in the contract's layer order");
            if (at <= last) c.fail("vector layers " + expected + " are not in the contract's order " + order);
            last = at;
        }
        List<String> drawn = new ArrayList<>();
        boolean glowBehindText = false;
        for (LookPaintPlan.Op o : c.plan.ops) {
            if (o.layer != null) {
                if (drawn.isEmpty() || !drawn.get(drawn.size() - 1).equals(o.layer.id)) drawn.add(o.layer.id);
            } else if (o.kind == LookPaintPlan.Kind.GLOW_BEHIND_TEXT) {
                glowBehindText = true;
            }
        }
        // The one known exception to the contract's order (C5, gate 5): the shipped glow look has no box and keeps the
        // legacy glow behind the text, as installed phones draw it. It still has to be a listed layer.
        List<String> want = new ArrayList<>(expected);
        if (glowBehindText) {
            c.expect(expected.contains("glow"), "the plan draws a glow the vectors do not list");
            want.remove("glow");
        }
        if (!drawn.equals(want)) c.fail("layers drawn " + drawn + " but the vectors say " + expected);
    }

    /** No operation without a fact and no fact without an operation. */
    private static void checkOpsMatchFacts(Ctx c, JSONArray facts) throws JSONException {
        Set<LookPaintPlan.Kind> want = new HashSet<>();
        want.add(LookPaintPlan.Kind.TEXT_FILL);
        boolean box = find(facts, "box") != null;
        for (int i = 0; i < facts.length(); i++) {
            JSONObject f = facts.getJSONObject(i);
            switch (f.getString("kind")) {
                case "text_stroke": want.add(LookPaintPlan.Kind.TEXT_STROKE); break;
                case "text_shadow": want.add(LookPaintPlan.Kind.TEXT_SHADOW); break;
                case "box_shadow": want.add(LookPaintPlan.Kind.BOX_SHADOW); break;
                case "box_fill": want.add(LookPaintPlan.Kind.BOX_FILL); break;
                case "box_border":
                    want.add(LookPaintPlan.Kind.BOX_BORDER);
                    if (!f.isNull("ring")) want.add(LookPaintPlan.Kind.BOX_RING);
                    break;
                case "box_glow": want.add(LookPaintPlan.Kind.BOX_GLOW); break;
                case "glow": want.add(box ? LookPaintPlan.Kind.TEXT_GLOW : LookPaintPlan.Kind.GLOW_BEHIND_TEXT); break;
                case "extrude": want.add(LookPaintPlan.Kind.EXTRUDE); break;
                default: break;
            }
        }
        Set<LookPaintPlan.Kind> have = new HashSet<>();
        for (LookPaintPlan.Op o : c.plan.ops) have.add(o.kind);
        if (!have.equals(want)) c.fail("operations " + have + " but the vectors ask for " + want);
        c.expect((find(facts, "bar") != null) == (c.plan.bar != null), "marker bar present in only one of plan and vectors");
        c.expect((find(facts, "tilt") != null) == (c.textLook.tiltDeg != null), "tilt present in only one of look and vectors");
    }

    /** Box contains the text plus padding on every side, and the whole overlay stays inside the block limit. */
    private static void checkGeometry(Ctx c, JSONArray facts, TextLayoutPolicy.Result fit, String ratio, float wanted,
                                      int maxW, Ruler ruler) throws JSONException {
        double size = fit.size;
        double textW = 0;
        for (String line : fit.lines) textW = Math.max(textW, ruler.width(line, (float) size));
        double textH = fit.lines.size() * LINE_HEIGHT * size;
        c.expect(textW <= ruler.available((float) size) + EPS, ratio + ": a line is wider than the room");
        c.expect(fit.lines.size() <= 2, ratio + ": more than 2 lines");
        JSONObject box = find(facts, "box");
        double inL = 0, inR = 0, inT = 0, inB = 0;
        if (box != null) {
            JSONObject in = box.getJSONObject("contains_text_with_inset_s");
            inL = in.getDouble("left");
            inR = in.getDouble("right");
            inT = in.getDouble("top");
            inB = in.getDouble("bottom");
            // text box (0,0,textW,textH); padding rectangle as the plan grows it
            double left = -c.plan.padX * size, right = textW + c.plan.padX * size;
            double top = -c.plan.padY * size, bottom = textH + c.plan.padY * size;
            c.expect(left <= 0 && top <= 0 && right >= textW && bottom >= textH, ratio + ": box does not contain the text");
            c.near(inL * size, 0 - left, ratio + " left inset (px)");
            c.near(inR * size, right - textW, ratio + " right inset (px)");
            c.near(inT * size, 0 - top, ratio + " top inset (px)");
            c.near(inB * size, bottom - textH, ratio + " bottom inset (px)");
        } else {
            c.expect(c.plan.padX == 0 && c.plan.padY == 0, "padding without a box");
        }
        // Everything the overlay adds around the text (padding, border, ring, effect room) stays inside the block limit
        double margin = Math.ceil(c.plan.effect * size) + 2;
        double total = textW + 2 * (c.plan.padX + c.plan.borderOuter) * size + 2 * margin;
        c.expect(total <= maxW + 1e-6 + 2, ratio + ": overlay " + total + " px wider than the limit " + maxW);
    }

    // ---- the fake ruler (one width per grapheme, wider for wide scripts) ----

    /** 0.6 em a grapheme, 1.0 em for Han, kana, Hangul and emoji, plus the letter spacing; room as the renderer computes it. */
    static final class Ruler implements TextLayoutPolicy.TextMeasurer {
        private final LookPaintPlan.Room room;
        private final int maxW;
        private final double spacing;

        Ruler(LookPaintPlan.Room room, int maxW, double spacing) {
            this.room = room;
            this.maxW = maxW;
            this.spacing = spacing;
        }

        @Override
        public float width(String line, float size) {
            double w = 0;
            Matcher m = GRAPHEME.matcher(line);
            while (m.find()) w += (weight(m.group()) + spacing) * size;
            return (float) w;
        }

        @Override
        public float available(float size) {
            float inset = 2 * (float) room.insetX * size;
            float margin = (float) Math.ceil(room.effect * size) + 2;
            return Math.max(1, (int) (maxW - inset - 2 * margin));
        }

        private static double weight(String g) {
            int cp = g.codePointAt(0);
            Character.UnicodeScript s = Character.UnicodeScript.of(cp);
            boolean wide = s == Character.UnicodeScript.HAN || s == Character.UnicodeScript.HIRAGANA
                    || s == Character.UnicodeScript.KATAKANA || s == Character.UnicodeScript.HANGUL
                    || Character.isSupplementaryCodePoint(cp) && s == Character.UnicodeScript.COMMON;
            return wide ? 1.0 : 0.6;
        }
    }

    // ---- the handlers ----

    static List<FactCheck> defaultChecks() {
        List<FactCheck> l = new ArrayList<>();
        l.add(new FactCheck("layers") {
            @Override
            void check(Ctx c, JSONObject f) throws JSONException {
                checkLayers(c, f);
            }
        });
        l.add(new FactCheck("text_fill", "text_fill", "fill") {
            @Override
            void check(Ctx c, JSONObject f) throws JSONException, UnsupportedLookException {
                LookPaintPlan.TextFillOp op = (LookPaintPlan.TextFillOp) c.op(LookPaintPlan.Kind.TEXT_FILL);
                if (op != null) checkFill(c, "text fill", op.paint, f.getJSONObject("fill"), 1);
            }
        });
        l.add(new FactCheck("text_stroke", "text_stroke", "stroke") {
            @Override
            void check(Ctx c, JSONObject f) throws JSONException {
                LookPaintPlan.TextStrokeOp op = (LookPaintPlan.TextStrokeOp) c.op(LookPaintPlan.Kind.TEXT_STROKE);
                if (op == null) return;
                c.near(f.getDouble("width_s"), op.width, "stroke width (x s)");
                c.expect(f.getString("join").equals(op.join), "stroke join " + op.join);
                c.expect(c.argb(f.getString("color"), 1) == op.color, "stroke colour");
            }
        });
        l.add(new FactCheck("text_shadow", "text_shadow", "shadow") {
            @Override
            void check(Ctx c, JSONObject f) throws JSONException {
                LookPaintPlan.TextShadowOp op = (LookPaintPlan.TextShadowOp) c.op(LookPaintPlan.Kind.TEXT_SHADOW);
                if (op == null) return;
                c.near(f.getDouble("dx_s"), op.dx, "text shadow dx");
                c.near(f.getDouble("dy_s"), op.dy, "text shadow dy");
                c.near(f.getDouble("blur_s"), op.blur, "text shadow blur");
                c.expect(f.getBoolean("hard") == (op.blur == 0), "text shadow hard/soft");
                c.expect(c.argb(f.getString("color"), f.getDouble("alpha")) == op.color, "text shadow colour and alpha");
                // The stroked letters cast the shadow too
                JSONObject stroke = findIn(c, "text_stroke");
                c.near(stroke == null ? 0 : stroke.getDouble("width_s"), op.strokeWidth, "text shadow stroke width");
            }
        });
        l.add(new FactCheck("box", "box", "background") {
            @Override
            void check(Ctx c, JSONObject f) throws JSONException {
                LookPaintPlan.BoxFillOp op = (LookPaintPlan.BoxFillOp) c.op(LookPaintPlan.Kind.BOX_FILL);
                if (op == null) return;
                String shape = f.getString("shape");
                double r = f.getDouble("radius_of_height");
                c.near(r, op.radiusOfHeight, "box corner radius (x height)");
                c.expect(!shape.equals("pill") || r == 0.5, "a pill is fully round");
                c.expect(!shape.equals("sharp") || r == 0, "a sharp box has no radius");
                c.expect(!shape.equals("rounded") || (r > 0 && r < 0.5), "a rounded box is neither sharp nor a pill");
                JSONObject pad = f.getJSONObject("padding_s");
                c.near(pad.getDouble("x"), c.plan.padX, "padding x");
                c.near(pad.getDouble("y"), c.plan.padY, "padding y");
                JSONObject in = f.getJSONObject("contains_text_with_inset_s");
                c.near(in.getDouble("left"), c.plan.padX, "left inset");
                c.near(in.getDouble("right"), c.plan.padX, "right inset");
                c.near(in.getDouble("top"), c.plan.padY, "top inset");
                c.near(in.getDouble("bottom"), c.plan.padY, "bottom inset");
            }
        });
        l.add(new FactCheck("box_fill", "box") {
            @Override
            void check(Ctx c, JSONObject f) throws JSONException, UnsupportedLookException {
                LookPaintPlan.BoxFillOp op = (LookPaintPlan.BoxFillOp) c.op(LookPaintPlan.Kind.BOX_FILL);
                if (op != null) checkFill(c, "box fill", op.paint, f.getJSONObject("fill"), f.getDouble("alpha"));
            }
        });
        l.add(new FactCheck("box_border", "box") {
            @Override
            void check(Ctx c, JSONObject f) throws JSONException {
                LookPaintPlan.BoxStrokeOp b = (LookPaintPlan.BoxStrokeOp) c.op(LookPaintPlan.Kind.BOX_BORDER);
                if (b != null) {
                    c.near(f.getDouble("width_s"), b.width, "border width");
                    c.near(0, b.offset, "border offset (it sits right outside the padding)");
                    c.expect(c.argb(f.getString("color"), 1) == b.color, "border colour");
                }
                LookPaintPlan.BoxStrokeOp ring = (LookPaintPlan.BoxStrokeOp) c.op(LookPaintPlan.Kind.BOX_RING);
                if (f.isNull("ring")) {
                    c.expect(ring == null, "a second ring the vectors do not have");
                } else if (ring != null && b != null) {
                    JSONObject r = f.getJSONObject("ring");
                    c.near(r.getDouble("width_s"), ring.width, "ring width");
                    c.near(f.getDouble("width_s") + r.getDouble("gap_s"), ring.offset, "ring offset (border plus gap)");
                    c.expect(c.argb(r.getString("color"), 1) == ring.color, "ring colour");
                    c.near(f.getDouble("width_s") + r.getDouble("gap_s") + r.getDouble("width_s"), c.plan.borderOuter,
                            "border and ring reach (x s)");
                } else {
                    c.fail("the ring is missing from the plan");
                }
                if (f.isNull("ring") && b != null) c.near(f.getDouble("width_s"), c.plan.borderOuter, "border reach (x s)");
            }
        });
        l.add(new FactCheck("box_shadow", "box", "shadow") {
            @Override
            void check(Ctx c, JSONObject f) throws JSONException {
                LookPaintPlan.BoxShadowOp op = (LookPaintPlan.BoxShadowOp) c.op(LookPaintPlan.Kind.BOX_SHADOW);
                if (op == null) return;
                boolean hard = "hard".equals(f.getString("style"));
                c.near(f.getDouble("dx_s"), op.dx, "box shadow dx");
                c.near(f.getDouble("dy_s"), op.dy, "box shadow dy");
                c.near(f.getDouble("blur_s"), op.blur, "box shadow blur");
                c.expect(hard == (op.blur == 0), "box shadow hard/soft");
                c.expect(c.argb(f.getString("color"), f.getDouble("alpha")) == op.color, "box shadow colour and alpha");
            }
        });
        l.add(new FactCheck("box_glow", "box") {
            @Override
            void check(Ctx c, JSONObject f) throws JSONException {
                LookPaintPlan.BoxGlowOp op = (LookPaintPlan.BoxGlowOp) c.op(LookPaintPlan.Kind.BOX_GLOW);
                if (op == null) return;
                c.near(f.getDouble("blur_s"), op.blur, "box glow blur");
                c.expect(c.argb(f.getString("color"), f.getDouble("alpha")) == op.color, "box glow colour and alpha");
            }
        });
        l.add(new FactCheck("glow", "glow") {
            @Override
            void check(Ctx c, JSONObject f) throws JSONException {
                LookPaintPlan.Op o = c.op(findIn(c, "box") != null
                        ? LookPaintPlan.Kind.TEXT_GLOW : LookPaintPlan.Kind.GLOW_BEHIND_TEXT);
                double blur;
                int color;
                if (o instanceof LookPaintPlan.TextGlowOp) {
                    blur = ((LookPaintPlan.TextGlowOp) o).blur;
                    color = ((LookPaintPlan.TextGlowOp) o).color;
                } else if (o instanceof LookPaintPlan.GlowBehindOp) {
                    blur = ((LookPaintPlan.GlowBehindOp) o).blur;
                    color = ((LookPaintPlan.GlowBehindOp) o).color;
                } else {
                    return;
                }
                c.near(f.getDouble("blur_s"), blur, "glow blur");
                c.expect(c.argb(f.getString("color"), f.getDouble("alpha")) == color, "glow colour and alpha");
            }
        });
        l.add(new FactCheck("extrude", "extrude") {
            @Override
            void check(Ctx c, JSONObject f) throws JSONException {
                LookPaintPlan.ExtrudeOp op = (LookPaintPlan.ExtrudeOp) c.op(LookPaintPlan.Kind.EXTRUDE);
                if (op == null) return;
                c.expect(f.getInt("copies") == op.copies, "extrude copies " + op.copies);
                c.near(f.getDouble("dx_s"), op.dx, "extrude dx");
                c.near(f.getDouble("dy_s"), op.dy, "extrude dy");
                c.expect(c.argb(f.getString("color"), 1) == op.color, "extrude colour");
            }
        });
        l.add(new FactCheck("bar", "bar") {
            @Override
            void check(Ctx c, JSONObject f) throws JSONException {
                LookPaintPlan.Bar bar = c.plan.bar;
                if (bar == null) return;
                c.near(f.getDouble("height_of_line"), bar.heightOfLine, "bar height (x line)");
                c.expect(c.argb(f.getString("color"), f.getDouble("alpha")) == bar.color, "bar colour and alpha");
            }
        });
        l.add(new FactCheck("tilt", "tilt_deg") {
            @Override
            void check(Ctx c, JSONObject f) throws JSONException {
                double[] t = c.textLook.tiltDeg;
                JSONArray r = f.getJSONArray("range_deg");
                c.expect(t != null && t.length == 2 && Math.abs(t[0] - r.getDouble(0)) <= EPS
                        && Math.abs(t[1] - r.getDouble(1)) <= EPS, "tilt range");
            }
        });
        l.add(new FactCheck("opacity", "opacity") {
            @Override
            void check(Ctx c, JSONObject f) throws JSONException {
                c.near(f.getDouble("value"), c.plan.opacity, "opacity");
            }
        });
        l.add(new FactCheck("font", "font") {
            @Override
            void check(Ctx c, JSONObject f) throws JSONException {
                String family = f.getString("family");
                c.expect(family.equals(c.plan.fontFamily), "font family " + c.plan.fontFamily);
                c.expect(f.getInt("weight") == c.plan.fontWeight, "font weight " + c.plan.fontWeight);
                c.expect(LookTypography.isKnownFamily(family), "font family " + family + " is not a family the phone draws");
                c.expect(LookTypography.weight(f.getInt("weight")) == f.getInt("weight"), "font weight outside 100..900");
            }
        });
        l.add(new FactCheck("case", "case") {
            @Override
            void check(Ctx c, JSONObject f) throws JSONException {
                c.expect(f.getString("mode").equals("upper") == c.plan.upper, "case mode");
                String shown = LookTypography.shownText(c.probe.getString("text"), c.plan.upper, c.probe.getString("language"));
                c.expect(shown.equals(c.probe.getString("text_drawn")),
                        "text drawn for " + c.probe.getString("probe") + " is " + shown + ", the vectors say "
                                + c.probe.getString("text_drawn"));
            }
        });
        l.add(new FactCheck("letter_spacing", "letter_spacing") {
            @Override
            void check(Ctx c, JSONObject f) throws JSONException {
                c.near(f.getDouble("spacing_s"), c.plan.letterSpacing, "letter spacing");
                double drawn = LookTypography.letterSpacing(c.probe.getString("text_drawn"), c.plan.letterSpacing);
                c.near(c.probe.getDouble("letter_spacing_s"), drawn,
                        "letter spacing drawn for " + c.probe.getString("probe"));
            }
        });
        return l;
    }

    // ---- shared helpers ----

    /** A solid colour or a gradient: kind, direction, stops, and the two sample points a vector names. */
    private static void checkFill(Ctx c, String what, LookPaintPlan.FillPaint paint, JSONObject fill, double alpha)
            throws JSONException, UnsupportedLookException {
        if ("solid".equals(fill.getString("kind"))) {
            c.expect(!paint.isGradient(), what + " must be solid");
            c.expect(c.argb(fill.getString("color"), alpha) == paint.color, what + " colour");
            return;
        }
        c.expect(paint.isGradient(), what + " must be a gradient");
        if (!paint.isGradient()) return;
        double[] line = LookPaintPlan.gradientLine(fill.getString("direction"));
        LookPaintPlan.Gradient g = paint.gradient;
        c.expect(line[0] == g.x0 && line[1] == g.y0 && line[2] == g.x1 && line[3] == g.y1,
                what + " direction " + fill.getString("direction"));
        c.expect(c.argb(fill.getString("from"), alpha) == g.from, what + " from colour");
        c.expect(c.argb(fill.getString("to"), alpha) == g.to, what + " to colour");
        double lenSq = (g.x1 - g.x0) * (g.x1 - g.x0) + (g.y1 - g.y0) * (g.y1 - g.y0);
        c.expect(lenSq > 0, what + " gradient has no length");
        double[] t = new double[2];
        JSONArray samples = fill.getJSONArray("samples");
        for (int i = 0; i < samples.length(); i++) {
            JSONObject s = samples.getJSONObject(i);
            JSONObject at = s.getJSONObject("at");
            double ti = ((at.getDouble("x") - g.x0) * (g.x1 - g.x0) + (at.getDouble("y") - g.y0) * (g.y1 - g.y0)) / lenSq;
            boolean isFrom = "from".equals(s.getString("is"));
            t[isFrom ? 0 : 1] = ti;
            c.expect(ti >= 0 && ti <= 1, what + " sample outside the gradient line");
            // Nearer the 'from' stop means before the middle of the line, nearer 'to' after it
            c.expect(isFrom ? ti < 0.5 : ti > 0.5, what + " sample '" + s.getString("is") + "' sits on the wrong side");
        }
        c.expect(t[0] < t[1], what + " samples do not differ along the direction");
    }

    private static JSONObject findIn(Ctx c, String kind) {
        return c.vectorFacts == null ? null : find(c.vectorFacts, kind);
    }

    static JSONObject find(JSONArray facts, String kind) {
        for (int i = 0; i < facts.length(); i++) {
            JSONObject f = facts.optJSONObject(i);
            if (f != null && kind.equals(f.optString("kind"))) return f;
        }
        return null;
    }

    private static List<String> strings(JSONArray a) throws JSONException {
        List<String> l = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) l.add(a.getString(i));
        return l;
    }
}
