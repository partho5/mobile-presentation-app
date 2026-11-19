package com.jovoc.facecampresentationrecorder.aiedit.debugsheet;

import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.jovoc.facecampresentationrecorder.aiedit.engine.RenderNotes;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.ContractException;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.EngineContract;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.OutputSpec;
import com.jovoc.facecampresentationrecorder.aiedit.engine.captions.CaptionPainter;
import com.jovoc.facecampresentationrecorder.aiedit.engine.text.TextOverlayPainter;

import java.util.Map;

/**
 * Debug-only screen (declared in {@code src/debug/AndroidManifest.xml}, so it is not in the release build): plays every
 * text motion with every string of the matrix in the three ratios through the real {@link TextOverlayPainter}, with a
 * scrubber, and a PASS/FAIL tick per motion. The ticks are kept on the phone and printed in server naming, ready to copy
 * into "Passed on a device" in {@code to-build/tasks.md}. It plays plain made-up plans: no recording, no network, no
 * account data.
 */
public class MotionSheetActivity extends AppCompatActivity {

    private static final String PREFS = "motion_sheet_ticks";

    private EngineContract contract;
    private SharedPreferences prefs;
    private final Map<String, MotionSheetGrid.Verdict> verdicts = MotionSheetGrid.emptyVerdicts();

    private int motionIdx;
    private int stringIdx;
    private int ratioIdx;

    private PreviewView preview;
    private SeekBar scrubber;
    private TextView title;
    private TextView notesView;
    private TextView reportView;
    private Button playButton;
    private ValueAnimator player;
    private boolean userScrubbing;

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
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        for (Map.Entry<String, MotionSheetGrid.Verdict> e : verdicts.entrySet()) {
            try {
                e.setValue(MotionSheetGrid.Verdict.valueOf(prefs.getString(e.getKey(), "UNSET")));
            } catch (IllegalArgumentException ignored) {
                // an unreadable stored tick stays unset
            }
        }
        setContentView(buildUi());
        selectCell();
        startPlayer();
    }

    @Override
    protected void onDestroy() {
        if (player != null) player.cancel();
        if (preview != null) preview.release();
        super.onDestroy();
    }

    // ---------------------------------------------------------------------------------------------------------- UI

    private View buildUi() {
        int pad = dp(10);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(24, 24, 28));

        title = text(14, Color.WHITE);
        root.addView(title);

        preview = new PreviewView();
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(dp(220), dp(390));
        pl.gravity = Gravity.CENTER_HORIZONTAL;
        pl.topMargin = pad;
        root.addView(preview, pl);

        scrubber = new SeekBar(this);
        scrubber.setMax((int) MotionSheetGrid.ITEM_MS - 1);
        scrubber.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    userScrubbing = true;
                    pause();
                    preview.show(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {}

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                userScrubbing = false;
            }
        });
        root.addView(scrubber);

        notesView = text(12, Color.rgb(255, 214, 10));
        root.addView(notesView);

        LinearLayout play = row();
        playButton = button("Pause", v -> togglePlay());
        play.addView(playButton);
        play.addView(button("Restart", v -> {
            preview.show(0);
            startPlayer();
        }));
        root.addView(play);

        root.addView(label("Motion"));
        LinearLayout motionRow = row();
        motionRow.addView(button("< motion", v -> step(0, -1)));
        motionRow.addView(button("motion >", v -> step(0, 1)));
        root.addView(motionRow);

        root.addView(label("String"));
        LinearLayout stringRow = row();
        stringRow.addView(button("< string", v -> step(1, -1)));
        stringRow.addView(button("string >", v -> step(1, 1)));
        root.addView(stringRow);

        root.addView(label("Ratio"));
        LinearLayout ratioRow = row();
        for (int i = 0; i < MotionSheetGrid.ratioCount(); i++) {
            final int r = i;
            ratioRow.addView(button(MotionSheetGrid.RATIOS.get(i), v -> {
                ratioIdx = r;
                selectCell();
            }));
        }
        root.addView(ratioRow);

        root.addView(label("Tick this motion after checking every string in the 3 ratios"));
        LinearLayout tick = row();
        tick.addView(button("PASS", v -> tick(MotionSheetGrid.Verdict.PASS)));
        tick.addView(button("FAIL", v -> tick(MotionSheetGrid.Verdict.FAIL)));
        tick.addView(button("Clear", v -> tick(MotionSheetGrid.Verdict.UNSET)));
        root.addView(tick);

        LinearLayout out = row();
        out.addView(button("Copy list", v -> copyReport()));
        out.addView(button("Reset all", v -> {
            for (Map.Entry<String, MotionSheetGrid.Verdict> e : verdicts.entrySet()) e.setValue(MotionSheetGrid.Verdict.UNSET);
            prefs.edit().clear().apply();
            refreshText();
        }));
        root.addView(out);

        reportView = text(13, Color.WHITE);
        reportView.setTextIsSelectable(true);
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

    /** Moves along one axis (0 motion, 1 string) with wrap-around, and shows the new cell. */
    private void step(int axis, int delta) {
        if (axis == 0) motionIdx = Math.floorMod(motionIdx + delta, MotionSheetGrid.motionCount());
        else stringIdx = Math.floorMod(stringIdx + delta, MotionSheetGrid.stringCount());
        selectCell();
    }

    private void selectCell() {
        MotionSheetGrid.Cell cell = MotionSheetGrid.cell(motionIdx, stringIdx, ratioIdx);
        OutputSpec spec = contract.output(cell.ratio);
        preview.setCell(cell, spec);
        refreshText();
        preview.show(0);
        startPlayer();
    }

    private void tick(MotionSheetGrid.Verdict v) {
        MotionSheetGrid.Motion m = MotionSheetGrid.MOTIONS.get(motionIdx);
        verdicts.put(m.serverId(), v);
        prefs.edit().putString(m.serverId(), v.name()).apply();
        refreshText();
    }

    private void refreshText() {
        MotionSheetGrid.Cell c = MotionSheetGrid.cell(motionIdx, stringIdx, ratioIdx);
        MotionSheetGrid.Verdict v = verdicts.get(c.motion.serverId());
        title.setText("Motion " + (motionIdx + 1) + "/" + MotionSheetGrid.motionCount() + ": " + c.motion.serverId()
                + (c.motion.legacy ? " (already shipped)" : "") + "  [" + v + "]\nString " + (stringIdx + 1) + "/"
                + MotionSheetGrid.stringCount() + ": " + c.string.id + (c.string.overCap ? " (over the cap: expect it skipped)" : "")
                + "\nRatio " + c.ratio + "   cell " + (c.index + 1) + "/" + MotionSheetGrid.size());
        reportView.setText(MotionSheetGrid.report(verdicts));
    }

    private void copyReport() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("motions", MotionSheetGrid.report(verdicts)));
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
    }

    private void startPlayer() {
        if (player != null) player.cancel();
        if (scrubber == null) return;
        player = ValueAnimator.ofInt(0, (int) MotionSheetGrid.ITEM_MS - 1);
        player.setDuration(MotionSheetGrid.ITEM_MS);
        player.setInterpolator(new LinearInterpolator());
        player.setRepeatCount(ValueAnimator.INFINITE);
        player.addUpdateListener(a -> {
            int ms = (Integer) a.getAnimatedValue();
            scrubber.setProgress(ms);
            preview.show(ms);
        });
        player.start();
        if (playButton != null) playButton.setText("Pause");
    }

    private void pause() {
        if (player != null) player.cancel();
        if (playButton != null) playButton.setText("Play");
    }

    private void togglePlay() {
        if (player != null && player.isRunning()) pause();
        else startPlayer();
    }

    /** The picture: a mid-grey background, the safe rectangle, and the real painter's output on top. */
    private final class PreviewView extends View {
        private final Paint bg = new Paint();
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private MotionSheetGrid.Cell cell;
        private OutputSpec spec;
        private CaptionPainter painter;
        private RenderNotes notes;
        private long lastMs = -1;
        private long wantMs;

        PreviewView() {
            super(MotionSheetActivity.this);
            line.setStyle(Paint.Style.STROKE);
            line.setColor(Color.argb(120, 255, 255, 255));
            line.setStrokeWidth(2f);
        }

        void setCell(MotionSheetGrid.Cell c, OutputSpec s) {
            cell = c;
            spec = s;
            release();
            lastMs = -1;
            float aspect = (float) s.width / s.height;
            int availW = getResources().getDisplayMetrics().widthPixels - dp(20);
            int h = Math.min(dp(390), Math.round(availW / aspect));
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) getLayoutParams();
            lp.height = h;
            lp.width = Math.round(h * aspect);
            setLayoutParams(lp);
        }

        /** Frees the painter's sprite and blurred copy now (a painter frees them once its item has ended). */
        void release() {
            if (painter != null) painter.draw(new Canvas(), MotionSheetGrid.ITEM_MS, 1, 1);
            painter = null;
        }

        void show(long ms) {
            wantMs = Math.max(0, Math.min(MotionSheetGrid.ITEM_MS - 1, ms));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (cell == null || w == 0 || h == 0) return;
            bg.setShader(new LinearGradient(0, 0, 0, h, Color.rgb(70, 78, 96), Color.rgb(140, 150, 170), Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, bg);
            canvas.drawRect((float) spec.safeX0 * w, (float) spec.safeY0 * h, (float) spec.safeX1 * w,
                    (float) spec.safeY1 * h, line);
            // A painter frees its sprite once the item ends and cannot go back, so going back starts a new one
            if (painter == null || wantMs < lastMs) {
                release();
                notes = new RenderNotes();
                painter = new TextOverlayPainter(MotionSheetGrid.plan(cell), contract, spec, w, h,
                        w * CaptionPainter.DEFAULT_TEXT_SIZE_SHARE, null, notes);
            }
            lastMs = wantMs;
            painter.draw(canvas, wantMs, w, h);
            StringBuilder sb = new StringBuilder("t = " + wantMs + " ms");
            for (RenderNotes.Note n : notes.notes()) sb.append("   ").append(n.code);
            notesView.setText(sb.toString());
        }
    }
}
