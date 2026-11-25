package com.jovoc.facecampresentationrecorder.contentplan;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.jovoc.facecampresentationrecorder.MainActivity;
import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.auth.SessionManager;
import com.jovoc.facecampresentationrecorder.db.Slide;
import com.jovoc.facecampresentationrecorder.db.SlideRepository;
import com.jovoc.facecampresentationrecorder.util.RecordSettings;

import org.json.JSONException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The "Today's video" card of the recorder screen: shows the day of the AI content plan to record, loads its hooks as
 * the topics of the recorder, opens Practice and the week list. It only consumes the plan; nothing here changes it.
 * The card is never visible while recording (the screen is captured), and no plan text is ever logged.
 */
public final class ContentPlanCard {

    static final String LINK_TARGET = "content-plan";
    private static final long FRESH_MS = 10L * 60 * 1000;
    private static final String PREF_LAST_AUTO_DAY = "cplan_last_auto_day";
    private static final String[] ANGLES = {"problem", "mistake", "story", "question", "comparison", "steps",
            "fear_loss", "behind_the_scenes", "customer_voice", "trend"};
    private static final int[] ANGLE_LABELS = {R.string.cplan_angle_problem, R.string.cplan_angle_mistake,
            R.string.cplan_angle_story, R.string.cplan_angle_question, R.string.cplan_angle_comparison,
            R.string.cplan_angle_steps, R.string.cplan_angle_fear_loss, R.string.cplan_angle_behind_the_scenes,
            R.string.cplan_angle_customer_voice, R.string.cplan_angle_trend};

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "content-plan-card");
        t.setDaemon(true);
        return t;
    });

    private final MainActivity host;
    private final View card;
    private final ContentPlanStore store;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SlideRepository slides;
    private final ActivityResultLauncher<Intent> practiceLauncher;
    private final PlanSetSync sync;

    private final TextView label;
    private final TextView title;
    private final TextView chip;
    private final TextView why;
    private final View buttons;
    private final Button btnUse;
    private final Button btnPractice;
    private final Button btnWeek;
    private final Button btnSetup;
    private final TextView status;

    private PlanWeek plan;
    private String brandId;
    private PlanWeek.Day selected;
    /** The day the user picked in the week list; null = the card chooses. */
    private String pickedDayId;
    private boolean hasContent;
    private boolean allowed;
    private volatile boolean fetching;
    private String fetchedForDate;

    public ContentPlanCard(MainActivity host, View card, ContentPlanStore store) {
        this.host = host;
        this.card = card;
        this.store = store;
        this.slides = host.slideRepository();
        this.sync = new PlanSetSync(slides, PlanSetIndex.get(host), host::discardSlideData);
        label = card.findViewById(R.id.cplan_label);
        title = card.findViewById(R.id.cplan_title);
        chip = card.findViewById(R.id.cplan_chip);
        why = card.findViewById(R.id.cplan_why);
        buttons = card.findViewById(R.id.cplan_buttons);
        btnUse = card.findViewById(R.id.cplan_btn_use);
        btnPractice = card.findViewById(R.id.cplan_btn_practice);
        btnWeek = card.findViewById(R.id.cplan_btn_week);
        btnSetup = card.findViewById(R.id.cplan_btn_setup);
        status = card.findViewById(R.id.cplan_status);

        practiceLauncher = host.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) return;
                    String dayId = result.getData().getStringExtra(ContentPlanPracticeActivity.EXTRA_DAY_ID);
                    PlanWeek.Day day = findDay(dayId);
                    if (day != null) showSet(day);
                });

        btnUse.setOnClickListener(v -> onUseClicked());
        btnPractice.setOnClickListener(v -> {
            if (selected == null) return;
            Intent i = new Intent(host, ContentPlanPracticeActivity.class);
            i.putExtra(ContentPlanPracticeActivity.EXTRA_BRAND_ID, brandId);
            i.putExtra(ContentPlanPracticeActivity.EXTRA_DAY_ID, selected.id);
            practiceLauncher.launch(i);
        });
        btnWeek.setOnClickListener(v -> showWeekSheet());
        btnSetup.setOnClickListener(v -> openWebPage());
    }

    /** True when there is something to show; the host shows the card only then, and never while recording. */
    public boolean hasContent() {
        return hasContent;
    }

    /** The host says whether the card may be on screen now (not recording, menu bar open). */
    public void setAllowed(boolean allowed) {
        this.allowed = allowed;
        card.setVisibility(allowed && hasContent ? View.VISIBLE : View.GONE);
    }

    // ---- loading ----

    /**
     * Shows the cached week at once, then asks the server unless the last answer for this brand is under 10 minutes
     * old (or {@code force}, or the local date changed). Any failure keeps the cached card.
     */
    public void refresh(boolean force) {
        final Context app = host.getApplicationContext();
        if (fetching) return;
        fetching = true;
        IO.execute(() -> {
            SessionManager session = SessionManager.get(app);
            if (!session.hasSession()) {
                fetching = false;
                main.post(() -> {
                    plan = null;
                    brandId = null;
                    setContent(false);
                });
                return;
            }
            String cacheBrand = session.repository().store().workspaceId();
            PlanWeek cached = readCache(cacheBrand);
            final String cachedBrand = cacheBrand;
            main.post(() -> {
                brandId = cachedBrand;
                bind(cached);
            });
            try {
                String today = cached == null ? null : localToday(cached);
                boolean fresh = cacheBrand != null && cached != null
                        && System.currentTimeMillis() - store.fetchedAt(cacheBrand) < FRESH_MS
                        && today != null && (fetchedForDate == null || today.equals(fetchedForDate));
                if (fresh && !force) return;
                String brand = session.brandForNewJob();
                if (brand == null) {
                    if (cacheBrand == null) main.post(() -> {
                        plan = null;
                        setContent(false);
                    });
                    return;
                }
                String json = new ContentPlanApi(session.repository()).week(brand);
                PlanWeek fetched = PlanWeek.parse(json);
                store.saveWeek(brand, json, System.currentTimeMillis());
                final String forDate = localToday(fetched);
                main.post(() -> {
                    brandId = brand;
                    fetchedForDate = forDate;
                    bind(fetched);
                    sync.sync(fetched, brand, () -> maybeAutoSwitch(fetched));
                });
            } catch (ContentPlanException | JSONException e) {
                // Offline or a server problem: the cached card stays, silently.
            } finally {
                fetching = false;
            }
        });
    }

    /** Re-reads the cache and shows it again (a recording may have marked a day recorded). */
    public void rebind() {
        if (brandId == null) return;
        PlanWeek cached = readCache(brandId);
        if (cached != null) {
            bind(cached);
            maybeAutoSwitch(cached);
        }
    }

    private PlanWeek readCache(String brand) {
        String json = store.weekJson(brand);
        if (json == null) return null;
        try {
            return PlanWeek.parse(json);
        } catch (JSONException e) {
            return null;
        }
    }

    // ---- showing ----

    private void setContent(boolean has) {
        hasContent = has;
        host.syncPlanCardVisibility();
    }

    private void bind(PlanWeek pw) {
        plan = pw;
        selected = null;
        if (pw == null) {
            setContent(false);
            return;
        }
        chip.setVisibility(View.GONE);
        why.setVisibility(View.GONE);
        status.setVisibility(View.GONE);
        buttons.setVisibility(View.GONE);
        btnSetup.setVisibility(View.GONE);
        label.setText("");
        label.setVisibility(View.GONE);
        btnWeek.setVisibility(View.VISIBLE);

        if (!pw.isActive()) {
            // NONE, SETUP or PROPOSED: the plan is made on the web.
            title.setText(R.string.cplan_setup_title);
            btnSetup.setVisibility(View.VISIBLE);
            showBackIfPlanSetActive();
            setContent(true);
            return;
        }
        if (pw.week == null || !pw.isReady()) {
            boolean failed = pw.week != null && "FAILED".equals(pw.week.status);
            title.setText(failed ? R.string.cplan_failed : R.string.cplan_writing);
            showBackIfPlanSetActive();
            setContent(true);
            return;
        }

        String today = localToday(pw);
        if (!pw.week.weekEnd.isEmpty() && today.compareTo(pw.week.weekEnd) > 0) {
            title.setText(R.string.cplan_stale);
            showBackIfPlanSetActive();
            setContent(true);
            return;
        }

        int labelRes = R.string.cplan_today;
        PlanWeek.Day day = findDay(pickedDayId);
        if (day != null) {
            labelRes = today.equals(day.date) ? R.string.cplan_today : R.string.cplan_video;
        } else {
            pickedDayId = null;
            List<PlanWeek.Day> todays = pw.daysFor(today);
            if (!todays.isEmpty()) {
                day = firstUnrecorded(todays);
                if (day == null) day = todays.get(0);
            } else {
                day = nextUnrecorded(pw, today);
                labelRes = R.string.cplan_next;
            }
        }
        if (day == null) {
            title.setText(R.string.cplan_week_done);
            btnWeek.setVisibility(View.VISIBLE);
            buttons.setVisibility(View.VISIBLE);
            btnUse.setVisibility(View.GONE);
            btnPractice.setVisibility(View.GONE);
            showBackIfPlanSetActive();
            setContent(true);
            return;
        }
        selected = day;
        bindDay(pw, day, today, labelRes);
        setContent(true);
    }

    private void bindDay(PlanWeek pw, PlanWeek.Day day, String today, int labelRes) {
        label.setVisibility(View.VISIBLE);
        label.setText(host.getString(R.string.cplan_card_label, host.getString(labelRes), formatDate(day.date)));
        title.setText(day.title);
        String chipText = day.isInstantIdea() ? host.getString(R.string.cplan_your_idea) : angleLabel(day.angle);
        if (chipText != null) {
            chip.setText(chipText);
            chip.setVisibility(View.VISIBLE);
        }
        if (!day.why.isEmpty()) {
            why.setText(day.why);
            why.setVisibility(View.VISIBLE);
        }
        buttons.setVisibility(View.VISIBLE);
        boolean hasBeats = !day.beats.isEmpty();
        btnUse.setVisibility(hasBeats ? View.VISIBLE : View.GONE);
        btnPractice.setVisibility(hasBeats ? View.VISIBLE : View.GONE);
        btnWeek.setVisibility(View.VISIBLE);
        btnUse.setText(Slide.planSetId(day.id).equals(host.activeSetId())
                ? R.string.cplan_back_topics : R.string.cplan_use_topics);

        String line = null;
        if (isRecorded(day)) line = host.getString(R.string.cplan_recorded);
        if (day.date.equals(today)) {
            int others = 0;
            for (PlanWeek.Day d : pw.daysFor(today)) {
                if (!d.id.equals(day.id) && !isRecorded(d)) others++;
            }
            if (others > 0) {
                String more = host.getString(R.string.cplan_more_today, others);
                line = line == null ? more : host.getString(R.string.cplan_status_join, line, more);
            }
        }
        if (line != null) {
            status.setText(line);
            status.setVisibility(View.VISIBLE);
        }
    }

    /** With no day on the card, a plan set that is still active must have a way back. */
    private void showBackIfPlanSetActive() {
        String active = host.activeSetId();
        boolean plan = active != null && active.startsWith(Slide.PLAN_SET_PREFIX);
        buttons.setVisibility(plan ? View.VISIBLE : View.GONE);
        btnUse.setVisibility(plan ? View.VISIBLE : View.GONE);
        btnUse.setText(R.string.cplan_back_topics);
        btnPractice.setVisibility(View.GONE);
        btnWeek.setVisibility(View.GONE);
    }

    private boolean isRecorded(PlanWeek.Day d) {
        return d.isRecorded() || store.recordedLocally(d.id);
    }

    private PlanWeek.Day firstUnrecorded(List<PlanWeek.Day> days) {
        for (PlanWeek.Day d : days) {
            if (!isRecorded(d)) return d;
        }
        return null;
    }

    /** The first day after {@code today} not yet recorded, in date order (server order within a date). */
    private PlanWeek.Day nextUnrecorded(PlanWeek pw, String today) {
        PlanWeek.Day best = null;
        for (PlanWeek.Day d : pw.week.days) {
            if (d.date.compareTo(today) <= 0 || isRecorded(d)) continue;
            if (best == null || d.date.compareTo(best.date) < 0) best = d;
        }
        return best;
    }

    private PlanWeek.Day findDay(String id) {
        if (id == null || plan == null || plan.week == null) return null;
        for (PlanWeek.Day d : plan.week.days) {
            if (d.id.equals(id)) return d;
        }
        return null;
    }

    private String angleLabel(String key) {
        for (int i = 0; i < ANGLES.length; i++) {
            if (ANGLES[i].equals(key)) return host.getString(ANGLE_LABELS[i]);
        }
        return null;
    }

    /** The device date in the plan's timezone (ISO yyyy-MM-dd); the server's date when the zone is unknown. */
    static String localToday(PlanWeek pw) {
        try {
            if (pw.timezone != null && !pw.timezone.isEmpty()) {
                return ZonedDateTime.now(ZoneId.of(pw.timezone)).toLocalDate().toString();
            }
        } catch (RuntimeException e) {
            // an unknown zone: use the server's date
        }
        return pw.today;
    }

    private static String formatDate(String iso) {
        try {
            return LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()));
        } catch (DateTimeParseException e) {
            return iso;
        }
    }

    // ---- topics ----

    private void onUseClicked() {
        String active = host.activeSetId();
        if (selected == null || (active != null && active.equals(Slide.planSetId(selected.id)))) {
            host.switchSlideSet(null);
            afterSwitch();
            return;
        }
        showSet(selected);
    }

    /** Puts the saved set of {@code day} on screen, when it has topics. Nothing is written or replaced here. */
    public void showSet(PlanWeek.Day day) {
        if (day == null || day.beats.isEmpty()) return;
        final String setId = Slide.planSetId(day.id);
        slides.getSet(setId, existing -> {
            if (existing.isEmpty()) return;
            host.switchSlideSet(setId);
            afterSwitch();
        });
    }

    /**
     * Once per video, on a fresh app launch only: puts the next video's saved set on screen. No toast, no dialog.
     * The fresh-launch flag stays set while the decision could not be made yet (recording, Topic Manager open, set
     * not saved yet) so a later call retries.
     */
    private void maybeAutoSwitch(PlanWeek pw) {
        if (!host.isFreshLaunch()) return;
        if (pw == null) return;
        final String today = localToday(pw);
        final PlanWeek.Day day = PlanAutoTarget.target(pw, today, id -> store.recordedLocally(id) || serverRecorded(pw, id));
        if (day == null) {
            host.clearFreshLaunch();
            return;
        }
        final String setId = Slide.planSetId(day.id);
        final SharedPreferences prefs = host.getSharedPreferences(RecordSettings.PREF_NAME, Context.MODE_PRIVATE);
        slides.getSet(setId, existing -> {
            String lastAuto = prefs.getString(PREF_LAST_AUTO_DAY, null);
            PlanAutoTarget.Switch s = PlanAutoTarget.decide(day, lastAuto, host.activeSetId(),
                    host.isRecordingNow(), host.isTopicManagerOpen(), !existing.isEmpty());
            if (s != PlanAutoTarget.Switch.NONE) prefs.edit().putString(PREF_LAST_AUTO_DAY, day.id).apply();
            if (s == PlanAutoTarget.Switch.SWITCH) host.switchSlideSet(setId);
            if (s != PlanAutoTarget.Switch.NONE || day.id.equals(lastAuto)) host.clearFreshLaunch();
            afterSwitch();
        });
    }

    private static boolean serverRecorded(PlanWeek pw, String dayId) {
        if (pw.week == null) return false;
        for (PlanWeek.Day d : pw.week.days) {
            if (d.id.equals(dayId)) return d.isRecorded();
        }
        return false;
    }

    private void afterSwitch() {
        if (plan != null) bind(plan);
    }

    // ---- week list ----

    private void showWeekSheet() {
        if (plan == null || plan.week == null) return;
        final BottomSheetDialog dialog = new BottomSheetDialog(host);
        LinearLayout root = new LinearLayout(host);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF121212);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        TextView heading = new TextView(host);
        heading.setText(R.string.cplan_week_title);
        heading.setTextColor(0xFFFFFFFF);
        heading.setTextSize(20);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setPadding(0, 0, 0, dp(8));
        root.addView(heading);

        String today = localToday(plan);
        for (PlanWeek.Day d : plan.week.days) {
            LinearLayout row = new LinearLayout(host);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            row.setBackgroundColor(0xFF222222);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(6);
            row.setLayoutParams(lp);

            TextView head = new TextView(host);
            String state = stateOf(d, today);
            head.setText(state == null ? formatDate(d.date)
                    : host.getString(R.string.cplan_status_join, formatDate(d.date), state));
            head.setTextColor(0xB3FFFFFF);
            head.setTextSize(12);
            head.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
            row.addView(head);

            TextView name = new TextView(host);
            name.setText(d.title);
            name.setTextColor(0xFFFFFFFF);
            name.setTextSize(15);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
            row.addView(name);

            if (d.isInstantIdea()) {
                TextView idea = new TextView(host);
                idea.setText(R.string.cplan_your_idea);
                idea.setTextColor(0xFF64B5F6);
                idea.setTextSize(12);
                row.addView(idea);
            }
            row.setOnClickListener(v -> {
                pickedDayId = d.id;
                dialog.dismiss();
                if (plan != null) bind(plan);
            });
            root.addView(row);
        }
        android.widget.ScrollView scroll = new android.widget.ScrollView(host);
        scroll.setBackgroundColor(0xFF121212);
        scroll.addView(root);
        dialog.setContentView(scroll);
        dialog.show();
    }

    /** "Today", "Recorded ✓" or "Missed" for the week list; null for a day still to come. */
    private String stateOf(PlanWeek.Day d, String today) {
        if (isRecorded(d)) return host.getString(R.string.cplan_recorded);
        if ("MISSED".equals(d.status) || d.date.compareTo(today) < 0) return host.getString(R.string.cplan_day_missed);
        if (d.date.equals(today)) return host.getString(R.string.cplan_day_today);
        return null;
    }

    private int dp(int v) {
        return Math.round(v * host.getResources().getDisplayMetrics().density);
    }

    // ---- the web page ----

    private void openWebPage() {
        ContentPlanWeb.open(host);
    }
}
