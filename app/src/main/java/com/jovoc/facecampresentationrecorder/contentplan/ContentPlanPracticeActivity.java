package com.jovoc.facecampresentationrecorder.contentplan;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.ui.TextSlideSize;

import org.json.JSONException;

/**
 * Practice for one content-plan day: each hook as it will look in the recording, with the words to say under it, one
 * beat at a time, then the closing line. A separate screen on purpose: it never starts the camera or the screen
 * capture, so nothing can be recorded here. "Use as topics" hands the day back to the recorder, which loads it.
 * Plan texts are shown only, never logged.
 */
public class ContentPlanPracticeActivity extends AppCompatActivity {

    public static final String EXTRA_BRAND_ID = "brand_id";
    public static final String EXTRA_DAY_ID = "day_id";

    private PlanWeek.Day day;
    /** 0..beats-1 = a beat; beats = the closing line. */
    private int step;

    private TextView stage;
    private TextView sayLabel;
    private TextView say;
    private TextView count;
    private Button prev;
    private Button next;
    private Button toggleScript;
    private LinearLayout script;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        day = loadDay(getIntent().getStringExtra(EXTRA_BRAND_ID), getIntent().getStringExtra(EXTRA_DAY_ID));
        if (day == null) {
            finish();
            return;
        }
        setContentView(R.layout.activity_content_plan_practice);
        final View root = findViewById(R.id.practice_root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        ((TextView) findViewById(R.id.practice_title)).setText(day.title);
        TextView why = findViewById(R.id.practice_why);
        why.setText(day.why);
        why.setVisibility(day.why.isEmpty() ? View.GONE : View.VISIBLE);
        TextView chip = findViewById(R.id.practice_chip);
        String chipText = day.isInstantIdea() ? getString(R.string.cplan_your_idea) : angleLabel(day.angle);
        if (chipText != null) {
            chip.setText(chipText);
            chip.setVisibility(View.VISIBLE);
        }

        stage = findViewById(R.id.practice_stage);
        sayLabel = findViewById(R.id.practice_say_label);
        say = findViewById(R.id.practice_say);
        count = findViewById(R.id.practice_count);
        prev = findViewById(R.id.practice_prev);
        next = findViewById(R.id.practice_next);
        toggleScript = findViewById(R.id.practice_toggle_script);
        script = findViewById(R.id.practice_script);

        prev.setOnClickListener(v -> go(step - 1));
        next.setOnClickListener(v -> go(step + 1));
        toggleScript.setOnClickListener(v -> {
            boolean show = script.getVisibility() != View.VISIBLE;
            script.setVisibility(show ? View.VISIBLE : View.GONE);
            toggleScript.setText(show ? R.string.cplan_hide_script : R.string.cplan_show_script);
        });
        findViewById(R.id.practice_done).setOnClickListener(v -> finish());
        findViewById(R.id.practice_use).setOnClickListener(v -> {
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_DAY_ID, day.id));
            finish();
        });
        fillScript();
        render();
    }

    private PlanWeek.Day loadDay(String brandId, String dayId) {
        if (dayId == null) return null;
        String json = ContentPlanStore.get(this).weekJson(brandId);
        if (json == null) return null;
        try {
            PlanWeek pw = PlanWeek.parse(json);
            if (pw.week == null) return null;
            for (PlanWeek.Day d : pw.week.days) {
                if (d.id.equals(dayId)) return d.beats.isEmpty() ? null : d;
            }
        } catch (JSONException e) {
            // an unreadable cache: nothing to practise
        }
        return null;
    }

    private void go(int to) {
        int beats = day.beats.size();
        boolean hasClosing = !day.closingLine.isEmpty();
        int last = hasClosing ? beats : beats - 1;
        step = Math.max(0, Math.min(last, to));
        render();
    }

    private void render() {
        int beats = day.beats.size();
        boolean closing = step >= beats;
        PlanWeek.Beat beat = day.beats.get(closing ? beats - 1 : step);
        stage.setText(beat.hook);
        stage.setTextSize(TypedValue.COMPLEX_UNIT_SP, TextSlideSize.sizeSp(beat.hook.length()));
        sayLabel.setText(closing ? R.string.cplan_end_with : R.string.cplan_you_say);
        say.setText(closing ? day.closingLine : beat.say);
        count.setText(getString(R.string.cplan_step_count, Math.min(step + 1, beats), beats));
        int last = day.closingLine.isEmpty() ? beats - 1 : beats;
        prev.setEnabled(step > 0);
        next.setEnabled(step < last);
        prev.setAlpha(prev.isEnabled() ? 1f : 0.3f);
        next.setAlpha(next.isEnabled() ? 1f : 0.3f);
    }

    /** The whole script in one list: every hook in bold with its words under it, then the closing line. */
    private void fillScript() {
        for (PlanWeek.Beat b : day.beats) {
            script.addView(scriptText(b.hook, 15, true, 0xFFFFFFFF, 12));
            if (!b.say.isEmpty()) script.addView(scriptText(b.say, 15, false, 0xFFFFFFFF, 2));
        }
        if (!day.closingLine.isEmpty()) {
            script.addView(scriptText(getString(R.string.cplan_end_with), 12, false, 0xB3FFFFFF, 16));
            script.addView(scriptText(day.closingLine, 15, false, 0xFFFFFFFF, 2));
        }
    }

    private TextView scriptText(String text, int sp, boolean bold, int color, int topDp) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        t.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        t.setTextIsSelectable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(topDp * getResources().getDisplayMetrics().density);
        t.setLayoutParams(lp);
        return t;
    }

    private String angleLabel(String key) {
        switch (key) {
            case "problem": return getString(R.string.cplan_angle_problem);
            case "mistake": return getString(R.string.cplan_angle_mistake);
            case "story": return getString(R.string.cplan_angle_story);
            case "question": return getString(R.string.cplan_angle_question);
            case "comparison": return getString(R.string.cplan_angle_comparison);
            case "steps": return getString(R.string.cplan_angle_steps);
            case "fear_loss": return getString(R.string.cplan_angle_fear_loss);
            case "behind_the_scenes": return getString(R.string.cplan_angle_behind_the_scenes);
            case "customer_voice": return getString(R.string.cplan_angle_customer_voice);
            case "trend": return getString(R.string.cplan_angle_trend);
            default: return null;
        }
    }
}
