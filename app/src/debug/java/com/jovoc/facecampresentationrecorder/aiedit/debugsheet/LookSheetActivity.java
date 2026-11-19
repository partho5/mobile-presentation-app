package com.jovoc.facecampresentationrecorder.aiedit.debugsheet;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.ContractException;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.EngineContract;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.TextLook;
import com.jovoc.facecampresentationrecorder.aiedit.engine.text.TextLookSupport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Debug-only screen (declared in {@code src/debug/AndroidManifest.xml}, so it is not in the release build): every text look
 * of the bundled contract (all of them, the ones not yet enabled too) drawn through the real renderer, a page per
 * (string of the matrix, ratio) with a tile per look like the reference sheets, pixel probes on the drawn bitmaps (see
 * {@link LookProbes}), and a PASS/FAIL tick per look. The ticks are kept on the phone and printed with the features each
 * passed look needs, ready to copy into "Passed on a device" in {@code to-build/tasks.md}. The probes are advice; the
 * owner's eyes on the tile decide the tick. It draws plain made-up plans: no recording, no network, no account data.
 */
public class LookSheetActivity extends AppCompatActivity {

    private static final String PREFS = "look_sheet_ticks";
    private static final int TILE_DP = 150;
    private static final int GOOD = Color.rgb(52, 199, 89);
    private static final int BAD = Color.rgb(255, 69, 58);
    private static final int PENDING = Color.rgb(120, 120, 130);

    private EngineContract contract;
    private List<TextLook> looks;
    private List<String> lookIds;
    private SharedPreferences prefs;
    private Map<String, MotionSheetGrid.Verdict> verdicts;
    private final Handler main = new Handler(Looper.getMainLooper());

    private int stringIdx;
    private int ratioIdx;
    private int selected;

    private final List<TileView> tiles = new ArrayList<>();
    private TextView pageTitle;
    private TextView detail;
    private TextView sweepStatus;
    private TextView reportView;
    private Button sweepButton;

    private final ExecutorService pageExec = Executors.newSingleThreadExecutor();
    private final ExecutorService sweepExec = Executors.newSingleThreadExecutor();
    private LookCellEvaluator pageEvaluator;
    private volatile int generation;
    private volatile boolean destroyed;
    private volatile boolean sweepStop;
    private boolean sweeping;
    private final Map<String, Sweep> sweeps = new LinkedHashMap<>();

    /** What the sweep found for one look across every string and ratio. */
    private static final class Sweep {
        int cells;
        int clean;
        final List<String> bad = new ArrayList<>();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            contract = EngineContract.load(this);
        } catch (ContractException e) {
            Toast.makeText(this, "contract unreadable", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        looks = contract.getTextLooks();
        lookIds = new ArrayList<>();
        for (TextLook l : looks) lookIds.add(l.id);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        verdicts = LookSheetGrid.emptyVerdicts(lookIds);
        for (Map.Entry<String, MotionSheetGrid.Verdict> e : verdicts.entrySet()) {
            try {
                e.setValue(MotionSheetGrid.Verdict.valueOf(prefs.getString(e.getKey(), "UNSET")));
            } catch (IllegalArgumentException ignored) {
                // an unreadable stored tick stays unset
            }
        }
        pageEvaluator = new LookCellEvaluator(contract);
        setContentView(buildUi());
        refreshTexts();
        loadPage();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        sweepStop = true;
        generation++;
        final LookCellEvaluator ev = pageEvaluator;
        if (ev != null) pageExec.execute(ev::release);
        pageExec.shutdown();
        sweepExec.shutdown();
        for (TileView t : tiles) t.clear();
        super.onDestroy();
    }

    // ---------------------------------------------------------------------------------------------------------- UI

    private View buildUi() {
        int pad = dp(10);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(24, 24, 28));

        pageTitle = text(14, Color.WHITE);
        root.addView(pageTitle);

        LinearLayout stringRow = row();
        stringRow.addView(button("< string", v -> step(0, -1)));
        stringRow.addView(button("string >", v -> step(0, 1)));
        root.addView(stringRow);
        LinearLayout ratioRow = row();
        for (int i = 0; i < LookSheetGrid.ratioCount(); i++) {
            final int r = i;
            ratioRow.addView(button(LookSheetGrid.RATIOS.get(i), v -> {
                ratioIdx = r;
                refreshTexts();
                loadPage();
            }));
        }
        root.addView(ratioRow);

        for (int r = 0; r < LookSheetGrid.rows(looks.size()); r++) {
            LinearLayout row = row();
            row.setPadding(0, dp(6), 0, 0);
            for (int c = 0; c < LookSheetGrid.COLUMNS; c++) {
                int idx = r * LookSheetGrid.COLUMNS + c;
                if (idx < looks.size()) {
                    TileView t = new TileView(idx);
                    tiles.add(t);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(TILE_DP), 1f);
                    lp.setMargins(dp(3), 0, dp(3), 0);
                    row.addView(t, lp);
                } else {
                    row.addView(new View(this), new LinearLayout.LayoutParams(0, dp(TILE_DP), 1f));
                }
            }
            root.addView(row);
        }

        detail = text(12, Color.WHITE);
        detail.setPadding(0, dp(10), 0, 0);
        root.addView(detail);

        root.addView(label("Tick the selected look after checking it on every string in the 3 ratios (the probes only advise)"));
        LinearLayout tick = row();
        tick.addView(button("PASS", v -> tick(MotionSheetGrid.Verdict.PASS)));
        tick.addView(button("FAIL", v -> tick(MotionSheetGrid.Verdict.FAIL)));
        tick.addView(button("Clear", v -> tick(MotionSheetGrid.Verdict.UNSET)));
        root.addView(tick);

        root.addView(label("Probes for every cell (strings x 3 ratios x looks), in the background"));
        sweepButton = button("Run all probes", v -> toggleSweep());
        root.addView(sweepButton);
        sweepStatus = text(12, Color.rgb(255, 214, 10));
        root.addView(sweepStatus);

        LinearLayout out = row();
        out.addView(button("Copy list", v -> copyReport()));
        out.addView(button("Reset all", v -> {
            for (Map.Entry<String, MotionSheetGrid.Verdict> e : verdicts.entrySet()) e.setValue(MotionSheetGrid.Verdict.UNSET);
            prefs.edit().clear().apply();
            refreshTexts();
        }));
        out.addView(button("Motion sheet", v -> startActivity(new Intent(this, MotionSheetActivity.class))));
        root.addView(out);

        reportView = text(13, Color.WHITE);
        reportView.setTextIsSelectable(true);
        reportView.setPadding(0, dp(8), 0, 0);
        root.addView(reportView);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private TextView text(int sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private TextView label(String s) {
        TextView t = text(12, Color.LTGRAY);
        t.setPadding(0, dp(10), 0, 0);
        t.setText(s);
        return t;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    // -------------------------------------------------------------------------------------------------- behaviour

    private void step(int axis, int delta) {
        if (axis == 0) stringIdx = Math.floorMod(stringIdx + delta, LookSheetGrid.stringCount());
        refreshTexts();
        loadPage();
    }

    private void select(int lookIdx) {
        selected = lookIdx;
        refreshTexts();
        for (TileView t : tiles) t.invalidate();
    }

    private void tick(MotionSheetGrid.Verdict v) {
        String id = lookIds.get(selected);
        verdicts.put(id, v);
        prefs.edit().putString(id, v.name()).apply();
        refreshTexts();
        for (TileView t : tiles) t.invalidate();
    }

    private void refreshTexts() {
        MotionSheetStrings.Entry s = MotionSheetStrings.ALL.get(stringIdx);
        pageTitle.setText("String " + (stringIdx + 1) + "/" + LookSheetGrid.stringCount() + ": " + s.id
                + (s.overCap ? " (over the cap: expect every look to skip it)" : "") + "\nRatio "
                + LookSheetGrid.RATIOS.get(ratioIdx) + "   " + looks.size() + " looks, accents rotate red, yellow, green");
        detail.setText(detailText());
        reportView.setText(LookSheetGrid.report(looks, verdicts));
    }

    private String detailText() {
        TextLook look = looks.get(selected);
        StringBuilder sb = new StringBuilder();
        List<String> need = TextLookSupport.requiredFeatures(look);
        sb.append("Look ").append(selected + 1).append('/').append(looks.size()).append(": ").append(look.id)
                .append(LookSheetGrid.shipped(look.id) ? " (already shipped)" : " (not enabled yet)").append("  [")
                .append(verdicts.get(look.id)).append("]\nfont ").append(look.font.family).append(' ').append(look.font.weight)
                .append(", case ").append(look.caseMode).append(", needs ").append(need.isEmpty() ? "no feature" : join(need));
        if ("sans_condensed".equals(look.font.family)) {
            sb.append("\nsans_condensed is a guess for this look: compare it with reference sheet 3 (Charcoal Dark, Coral Pop)"
                    + " and tick PASS only if the letters look right");
        }
        TileView t = tiles.get(selected);
        if (t.result != null) {
            sb.append("\n\nThis page: ").append(t.result.summary());
            if (!t.result.notes.isEmpty()) sb.append("  notes: ").append(join(t.result.notes));
            for (LookProbes.Result r : t.result.probes) {
                sb.append('\n').append(r.pass ? "PASS " : "FAIL ").append(r.probe.name()).append(" (").append(r.probe.note)
                        .append(") ").append(r.detail);
            }
        } else {
            sb.append("\n\nThis page: drawing...");
        }
        Sweep sw = sweeps.get(look.id);
        if (sw != null) {
            sb.append("\n\nAll cells: ").append(sw.clean).append('/').append(sw.cells).append(" clean");
            for (String b : sw.bad) sb.append("\n  ").append(b);
        }
        return sb.toString();
    }

    private static String join(List<String> ids) {
        StringBuilder sb = new StringBuilder();
        for (String s : ids) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(s);
        }
        return sb.toString();
    }

    private void copyReport() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("looks", LookSheetGrid.report(looks, verdicts)));
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
    }

    /** Draws the 16 tiles of the current (string, ratio) page in the background, filling them one by one. */
    private void loadPage() {
        final int gen = ++generation;
        final int si = stringIdx;
        final int ri = ratioIdx;
        for (TileView t : tiles) t.setPending();
        pageExec.execute(() -> {
            for (int i = 0; i < looks.size(); i++) {
                if (destroyed || gen != generation) return;
                final int idx = i;
                final LookCellEvaluator.Evaluated ev = pageEvaluator.evaluate(LookSheetGrid.cell(lookIds, idx, si, ri),
                        looks.get(idx), true);
                main.post(() -> {
                    if (destroyed || gen != generation) {
                        if (ev.sprite != null) ev.sprite.recycle();
                        return;
                    }
                    tiles.get(idx).set(ev);
                    if (idx == selected) detail.setText(detailText());
                });
            }
        });
    }

    private void toggleSweep() {
        if (sweeping) {
            sweepStop = true;
            sweepStatus.setText("stopping...");
            return;
        }
        sweeping = true;
        sweepStop = false;
        sweeps.clear();
        sweepButton.setText("Stop");
        final List<TextLook> all = looks;
        sweepExec.execute(() -> {
            LookCellEvaluator ev = new LookCellEvaluator(contract);
            int total = LookSheetGrid.size(all.size());
            int done = 0;
            final Map<String, Sweep> found = new LinkedHashMap<>();
            for (String id : lookIds) found.put(id, new Sweep());
            try {
                // Ratio outermost: the evaluator keeps one frame bitmap per ratio
                for (int r = 0; r < LookSheetGrid.ratioCount() && !sweepStop && !destroyed; r++) {
                    for (int l = 0; l < all.size() && !sweepStop && !destroyed; l++) {
                        for (int s = 0; s < LookSheetGrid.stringCount() && !sweepStop && !destroyed; s++) {
                            LookCellEvaluator.Evaluated e = ev.evaluate(LookSheetGrid.cell(lookIds, l, s, r), all.get(l), false);
                            Sweep sw = found.get(lookIds.get(l));
                            sw.cells++;
                            if (e.result.clean()) sw.clean++;
                            else if (sw.bad.size() < 24) sw.bad.add(describe(e.result));
                            done++;
                        }
                    }
                    final int pct = 100 * done / total;
                    main.post(() -> sweepStatus.setText(pct + "%"));
                }
            } finally {
                ev.release();
            }
            final boolean complete = done == total;
            main.post(() -> {
                if (destroyed) return;
                sweeping = false;
                sweepButton.setText("Run all probes");
                if (complete) sweeps.putAll(found);
                int bad = 0;
                for (Sweep sw : found.values()) bad += sw.cells - sw.clean;
                sweepStatus.setText(complete ? "done: " + bad + " cell(s) need a look, see each look's detail"
                        : "stopped, results not kept");
                refreshTexts();
            });
        });
    }

    /** A failing cell in one line: where, and what failed (ids and probe names only). */
    private static String describe(LookCellResult r) {
        StringBuilder sb = new StringBuilder(r.where()).append(": ");
        if (r.status != LookCellResult.Status.DRAWN) return sb.append(r.summary()).toString();
        List<String> names = new ArrayList<>();
        for (LookProbes.Result f : r.failed()) names.add(f.probe.name());
        if (r.fellBack()) names.add("fell back to plain");
        return sb.append(join(names)).toString();
    }

    // ---------------------------------------------------------------------------------------------------- tiles

    /** One look on the page: its picture on a grey ground, its number and id, and the probes' verdict. */
    private final class TileView extends View {
        private final int lookIdx;
        private final Paint bg = new Paint();
        private final Paint pic = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint cap = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF dst = new RectF();
        private Bitmap sprite;
        LookCellResult result;
        private boolean pending = true;

        TileView(int lookIdx) {
            super(LookSheetActivity.this);
            this.lookIdx = lookIdx;
            cap.setTextSize(dp(11));
            frame.setStyle(Paint.Style.STROKE);
            frame.setStrokeWidth(dp(2));
            setOnClickListener(v -> select(lookIdx));
        }

        void setPending() {
            pending = true;
            result = null;
            Bitmap old = sprite;
            sprite = null;
            if (old != null) old.recycle();
            invalidate();
        }

        void set(LookCellEvaluator.Evaluated ev) {
            Bitmap old = sprite;
            sprite = ev.sprite;
            result = ev.result;
            pending = false;
            invalidate();
            if (old != null) old.recycle();
        }

        void clear() {
            Bitmap old = sprite;
            sprite = null;
            if (old != null) old.recycle();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w == 0 || h == 0) return;
            bg.setShader(new LinearGradient(0, 0, 0, h, Color.rgb(70, 78, 96), Color.rgb(140, 150, 170), Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, bg);
            TextLook look = looks.get(lookIdx);
            int top = dp(16);
            int bottom = dp(15);
            if (sprite != null && !sprite.isRecycled()) {
                float availW = w - dp(8);
                float availH = h - top - bottom;
                float scale = Math.min(Math.min(availW / sprite.getWidth(), availH / sprite.getHeight()), 2f);
                float dw = sprite.getWidth() * scale;
                float dh = sprite.getHeight() * scale;
                float cx = w / 2f;
                float cy = top + availH / 2f;
                dst.set(cx - dw / 2, cy - dh / 2, cx + dw / 2, cy + dh / 2);
                canvas.drawBitmap(sprite, null, dst, pic);
            }
            cap.setColor(Color.WHITE);
            MotionSheetGrid.Verdict v = verdicts.get(look.id);
            canvas.drawText((lookIdx + 1) + ". " + look.id + (v == MotionSheetGrid.Verdict.UNSET ? "" : "  [" + v + "]"),
                    dp(4), dp(12), cap);
            int color = pending || result == null ? PENDING : result.clean() ? GOOD : BAD;
            cap.setColor(color == PENDING ? Color.LTGRAY : Color.WHITE);
            canvas.drawText(pending || result == null ? "drawing..." : result.summary(), dp(4), h - dp(4), cap);
            frame.setColor(lookIdx == selected ? Color.WHITE : color);
            frame.setStrokeWidth(lookIdx == selected ? dp(3) : dp(2));
            canvas.drawRect(dp(1), dp(1), w - dp(1), h - dp(1), frame);
        }
    }
}
