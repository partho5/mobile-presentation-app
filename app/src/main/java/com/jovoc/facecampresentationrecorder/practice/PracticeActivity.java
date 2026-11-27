package com.jovoc.facecampresentationrecorder.practice;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.bumptech.glide.Glide;
import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.contentplan.ContentPlanStore;
import com.jovoc.facecampresentationrecorder.contentplan.PlanSetIndex;
import com.jovoc.facecampresentationrecorder.contentplan.PlanWeek;
import com.jovoc.facecampresentationrecorder.db.Slide;
import com.jovoc.facecampresentationrecorder.db.SlideRepository;
import com.jovoc.facecampresentationrecorder.ui.TextSlideSize;

import org.json.JSONException;

import java.io.File;
import java.util.List;

/**
 * Practice for any topic set: each topic as it will look in the recording, one at a time, with "What I'll say" under
 * it, then the plan's closing line when there is one. The line is the topic's saved note, else the plan's spoken line;
 * the user can write or change it and it is saved to that topic. A separate screen on purpose: it never starts the
 * camera or the screen capture, so nothing can be recorded here. Topic texts and notes are shown only, never logged.
 */
public class PracticeActivity extends AppCompatActivity {

    /** The set to practise: null = the user's own topics, "plan:&lt;dayId&gt;" = a plan video's. */
    public static final String EXTRA_SET_ID = "set_id";
    /** True when the set is the one on the recorder screen (then "Set for Recording" is not offered). */
    public static final String EXTRA_ON_SCREEN = "on_screen";
    /** Result: put {@link #EXTRA_SET_ID} on the recorder screen. */
    public static final String EXTRA_SWITCH = "switch";

    /** Opens Practice for {@code setIdOrNull}. */
    public static Intent intent(Context context, String setIdOrNull, boolean onScreen) {
        return new Intent(context, PracticeActivity.class)
                .putExtra(EXTRA_SET_ID, setIdOrNull)
                .putExtra(EXTRA_ON_SCREEN, onScreen);
    }

    private SlideRepository repository;
    private String setId;
    private PracticeScript script;
    /** The current line of each step (edited or not); {@link #saved} is what the topic holds now. */
    private String[] lines;
    private String[] saved;
    /** 0..steps-1 = a topic; steps = the closing line. */
    private int step;

    private TextView stage;
    private ImageView stageImage;
    private TextView stageKind;
    private TextView sayLabel;
    private EditText say;
    private TextView closing;
    private TextView count;
    private Button prev;
    private Button next;
    private Button toggleScript;
    private LinearLayout scriptList;
    private GestureDetector swipe;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setId = getIntent().getStringExtra(EXTRA_SET_ID);
        setContentView(R.layout.activity_practice);
        final View root = findViewById(R.id.practice_root);
        root.setVisibility(View.INVISIBLE); // filled once the topics are read
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return insets;
        });
        repository = new SlideRepository(this);
        repository.getSet(setId, this::onSlides);
    }

    private void onSlides(List<Slide> slides) {
        if (isFinishing() || isDestroyed()) return;
        PlanSetIndex.Entry entry = null;
        PlanWeek.Day day = null;
        if (setId != null && setId.startsWith(Slide.PLAN_SET_PREFIX)) {
            String dayId = setId.substring(Slide.PLAN_SET_PREFIX.length());
            entry = PlanSetIndex.get(this).get(dayId);
            day = cachedDay(entry == null ? null : entry.brandId, dayId);
        }
        script = PracticeScript.build(slides, day, entry == null ? null : entry.closingLine);
        if (script.steps.isEmpty()) {
            Toast.makeText(this, R.string.practice_add_topic, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        lines = new String[script.steps.size()];
        saved = new String[lines.length];
        for (int i = 0; i < lines.length; i++) {
            PracticeScript.Step s = script.steps.get(i);
            lines[i] = s.line == null ? "" : s.line;
            saved[i] = noteOf(slides, s.slideId);
        }
        setUp(day, entry);
    }

    private void setUp(PlanWeek.Day day, PlanSetIndex.Entry entry) {
        findViewById(R.id.practice_root).setVisibility(View.VISIBLE);
        String title = day != null && !day.title.isEmpty() ? day.title
                : entry != null && !entry.title.isEmpty() ? entry.title
                : setId == null ? getString(R.string.practice_own_title) : getString(R.string.cplan_set_unknown);
        ((TextView) findViewById(R.id.practice_title)).setText(title);
        TextView why = findViewById(R.id.practice_why);
        String whyText = day == null ? "" : day.why;
        why.setText(whyText);
        why.setVisibility(whyText.isEmpty() ? View.GONE : View.VISIBLE);
        TextView chip = findViewById(R.id.practice_chip);
        String chipText = day == null ? null
                : day.isInstantIdea() ? getString(R.string.cplan_your_idea) : angleLabel(day.angle);
        if (chipText != null) {
            chip.setText(chipText);
            chip.setVisibility(View.VISIBLE);
        }

        stage = findViewById(R.id.practice_stage);
        stageImage = findViewById(R.id.practice_stage_image);
        stageKind = findViewById(R.id.practice_stage_kind);
        sayLabel = findViewById(R.id.practice_say_label);
        say = findViewById(R.id.practice_say);
        closing = findViewById(R.id.practice_closing);
        count = findViewById(R.id.practice_count);
        prev = findViewById(R.id.practice_prev);
        next = findViewById(R.id.practice_next);
        toggleScript = findViewById(R.id.practice_toggle_script);
        scriptList = findViewById(R.id.practice_script);

        prev.setOnClickListener(v -> go(step - 1));
        next.setOnClickListener(v -> go(step + 1));
        toggleScript.setOnClickListener(v -> {
            keepLine();
            boolean show = scriptList.getVisibility() != View.VISIBLE;
            if (show) fillScript();
            scriptList.setVisibility(show ? View.VISIBLE : View.GONE);
            toggleScript.setText(show ? R.string.cplan_hide_script : R.string.cplan_show_script);
        });
        findViewById(R.id.practice_done).setOnClickListener(v -> finish());
        Button use = findViewById(R.id.practice_use);
        if (!getIntent().getBooleanExtra(EXTRA_ON_SCREEN, true)) {
            use.setVisibility(View.VISIBLE);
            use.setOnClickListener(v -> {
                setResult(RESULT_OK, new Intent().putExtra(EXTRA_SWITCH, true).putExtra(EXTRA_SET_ID, setId));
                finish();
            });
        }
        setupSwipe();
        render();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveNotes();
    }

    // ---- notes ----

    /** Copies the box into the current step's line (only a topic step has a box). */
    private void keepLine() {
        if (say != null && script != null && step < lines.length) lines[step] = say.getText().toString();
    }

    /** Writes every changed line to its topic; an emptied line clears the note. */
    private void saveNotes() {
        if (script == null || lines == null) return;
        keepLine();
        for (int i = 0; i < lines.length; i++) {
            String now = lines[i].trim().isEmpty() ? null : lines[i].trim();
            String was = saved[i] == null || saved[i].trim().isEmpty() ? null : saved[i].trim();
            if (now == null ? was == null : now.equals(was)) continue;
            // A plan line shown but never touched stays unsaved, so an unedited plan set can still be updated.
            PracticeScript.Step s = script.steps.get(i);
            if (was == null && s.line != null && s.line.trim().equals(now)) continue;
            saved[i] = now;
            repository.updateNote(s.slideId, now, null);
        }
    }

    private static String noteOf(List<Slide> slides, int id) {
        for (Slide s : slides) {
            if (s.getId() == id) return s.getSpeakerNote();
        }
        return null;
    }

    // ---- steps ----

    private int lastStep() {
        return script.closingLine == null ? lines.length - 1 : lines.length;
    }

    private void go(int to) {
        keepLine();
        int target = Math.max(0, Math.min(lastStep(), to));
        if (target != step) {
            say.clearFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(say.getWindowToken(), 0);
        }
        step = target;
        saveNotes();
        render();
    }

    private void render() {
        int topics = lines.length;
        boolean isClosing = step >= topics;
        PracticeScript.Step s = script.steps.get(isClosing ? topics - 1 : step);
        renderStage(s);
        if (isClosing) {
            sayLabel.setText(R.string.cplan_end_with);
            say.setVisibility(View.GONE);
            closing.setText(script.closingLine);
            closing.setVisibility(View.VISIBLE);
        } else {
            sayLabel.setText(R.string.practice_say_label);
            closing.setVisibility(View.GONE);
            say.setVisibility(View.VISIBLE);
            say.setText(lines[step]);
        }
        count.setText(getString(R.string.cplan_step_count, Math.min(step + 1, topics), topics));
        styleStepButton(prev, step > 0);
        styleStepButton(next, step < lastStep());
    }

    /** The topic as it looks on camera: text at the recorder's size, a picture or video frame, or a link label. */
    private void renderStage(PracticeScript.Step s) {
        String type = s.type == null ? Slide.TYPE_TEXT : s.type;
        boolean media = (Slide.TYPE_IMAGE.equals(type) || Slide.TYPE_VIDEO.equals(type)) && s.imagePath != null;
        stageImage.setVisibility(media ? View.VISIBLE : View.GONE);
        stage.setVisibility(media ? View.GONE : View.VISIBLE);
        String kind = kindLabel(type);
        stageKind.setText(kind);
        stageKind.setVisibility(kind == null ? View.GONE : View.VISIBLE);
        if (media) {
            Glide.with(this).load(new File(s.imagePath)).fitCenter().into(stageImage);
            return;
        }
        String text = s.text == null ? "" : s.text;
        stage.setText(text);
        stage.setTextSize(TypedValue.COMPLEX_UNIT_SP,
                Slide.TYPE_TEXT.equals(type) ? TextSlideSize.sizeSp(text.length()) : 16);
    }

    private String kindLabel(String type) {
        switch (type) {
            case Slide.TYPE_IMAGE: return getString(R.string.practice_kind_image);
            case Slide.TYPE_VIDEO: return getString(R.string.practice_kind_video);
            case Slide.TYPE_WEBSITE: return getString(R.string.practice_kind_website);
            case Slide.TYPE_YOUTUBE: return getString(R.string.practice_kind_youtube);
            default: return null;
        }
    }

    /** Active = accent fill; at the first/last step dimmed. The button stays clickable, go() clamps, so it is a no-op. */
    private static void styleStepButton(Button b, boolean active) {
        b.setBackgroundTintList(ColorStateList.valueOf(active ? 0xFF64B5F6 : 0xFF2A2A2A));
        b.setTextColor(active ? 0xFF0B1620 : 0xFF888888);
    }

    /** The whole script in one list: every topic in bold with its line under it, then the closing line. */
    private void fillScript() {
        scriptList.removeAllViews();
        for (int i = 0; i < lines.length; i++) {
            PracticeScript.Step s = script.steps.get(i);
            String head = Slide.TYPE_TEXT.equals(s.type) || s.type == null
                    ? (s.text == null ? "" : s.text) : kindLabel(s.type);
            scriptList.addView(scriptText(head, 15, true, 0xFFFFFFFF, 12));
            boolean empty = lines[i].trim().isEmpty();
            scriptList.addView(scriptText(empty ? getString(R.string.practice_nothing_yet) : lines[i].trim(), 15,
                    false, empty ? 0x80FFFFFF : 0xFFFFFFFF, 2));
        }
        if (script.closingLine != null) {
            scriptList.addView(scriptText(getString(R.string.cplan_end_with), 12, false, 0xB3FFFFFF, 16));
            scriptList.addView(scriptText(script.closingLine, 15, false, 0xFFFFFFFF, 2));
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

    /**
     * Horizontal swipe moves between topics, the same direction and thresholds as the recorder's slides
     * (MainActivity.setupGestureDetector): swipe left = next, swipe right = previous. Horizontal dominance keeps
     * vertical scrolling of long texts working; the buttons stay.
     */
    private void setupSwipe() {
        swipe = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null || e2 == null) return false;
                float diffX = e2.getX() - e1.getX();
                float diffY = e2.getY() - e1.getY();
                if (Math.abs(diffX) > Math.abs(diffY) && Math.abs(diffX) > 100 && Math.abs(velocityX) > 100) {
                    go(diffX > 0 ? step - 1 : step + 1);
                    return true;
                }
                return false;
            }
        });
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (swipe != null) swipe.onTouchEvent(ev);
        return super.dispatchTouchEvent(ev);
    }

    /** The plan video {@code dayId} from the cached week of {@code brandId}, or null when that week is gone. */
    private PlanWeek.Day cachedDay(String brandId, String dayId) {
        String json = ContentPlanStore.get(this).weekJson(brandId);
        if (json == null) return null;
        try {
            PlanWeek pw = PlanWeek.parse(json);
            if (pw.week == null) return null;
            for (PlanWeek.Day d : pw.week.days) {
                if (d.id.equals(dayId)) return d;
            }
        } catch (JSONException e) {
            // an unreadable cache: practise without the plan's extras
        }
        return null;
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
