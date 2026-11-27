package com.jovoc.facecampresentationrecorder.contentplan;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.jovoc.facecampresentationrecorder.MainActivity;
import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.auth.SessionManager;
import com.jovoc.facecampresentationrecorder.db.Slide;
import com.jovoc.facecampresentationrecorder.db.SlideRepository;
import com.jovoc.facecampresentationrecorder.util.RecordSettings;

import org.json.JSONException;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The slim "Today's video" / "Next video" strip of the recorder screen: shown while a planned video is still to record, it
 * opens Practice (button) or the Topic sets sheet (tap). It also keeps every video's topic set saved and puts the next
 * one on screen once per video. It is never visible while recording (the screen is captured); no plan text is logged.
 */
public final class ContentPlanCard {

    static final String LINK_TARGET = "content-plan";
    private static final long FRESH_MS = 10L * 60 * 1000;
    private static final String PREF_LAST_AUTO_DAY = "cplan_last_auto_day";
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
    private final Button btnPractice;

    private PlanWeek plan;
    private String brandId;
    private PlanWeek.Day selected;
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
        btnPractice = card.findViewById(R.id.cplan_btn_practice);

        practiceLauncher = host.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) return;
                    String dayId = result.getData().getStringExtra(ContentPlanPracticeActivity.EXTRA_DAY_ID);
                    PlanWeek.Day day = findDay(dayId);
                    if (day != null) showSet(day);
                });

        btnPractice.setOnClickListener(v -> {
            if (selected != null) practice(selected.id);
        });
        card.setOnClickListener(v -> TopicSetsSheet.show(host));
    }

    /** Opens the Practice screen for one video of the cached week (strip button and Topic sets sheet). */
    public void practice(String dayId) {
        if (dayId == null || brandId == null) return;
        Intent i = new Intent(host, ContentPlanPracticeActivity.class);
        i.putExtra(ContentPlanPracticeActivity.EXTRA_BRAND_ID, brandId);
        i.putExtra(ContentPlanPracticeActivity.EXTRA_DAY_ID, dayId);
        practiceLauncher.launch(i);
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
     * old (or {@code force}, or the local date changed). Inside the 10 minutes it asks the cheap week stamp instead and
     * fetches the week only when the stamp changed. Any failure keeps the cached card.
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
                String brand = fresh && !force ? cacheBrand : session.brandForNewJob();
                if (brand == null) {
                    if (cacheBrand == null) main.post(() -> {
                        plan = null;
                        setContent(false);
                    });
                    return;
                }
                ContentPlanApi api = new ContentPlanApi(session.repository());
                // The stamp is asked before the week and the one asked is the one kept: a change that lands between
                // the two calls leaves the kept stamp older than the week, so the next resume fetches once more
                // (harmless), where a stamp asked after the week could hide that change for good.
                String stamp = readStamp(api, brand);
                if (!PlanRefreshPolicy.shouldFetchWeek(fresh, force, store.weekStamp(brand), stamp)) return;
                String json = api.week(brand);
                PlanWeek fetched = PlanWeek.parse(json);
                store.saveWeek(brand, json, System.currentTimeMillis());
                if (stamp != null) store.saveWeekStamp(brand, stamp);
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

    /** The server's week stamp, or null on any failure (offline, route not deployed yet, bad answer). */
    private static String readStamp(ContentPlanApi api, String brand) {
        try {
            return api.stamp(brand);
        } catch (ContentPlanException e) {
            return null;
        }
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

    /**
     * The strip shows today's first video still to record; when today has none it shows the next upcoming video still
     * to record ("Next video"), so Practice stays reachable on every day. It is hidden only when there is no plan, the
     * plan is not active and ready, or no unrecorded video with topics is left in the week.
     */
    private void bind(PlanWeek pw) {
        plan = pw;
        selected = null;
        if (pw == null || !pw.isActive() || pw.week == null || !pw.isReady()) {
            setContent(false);
            return;
        }
        String today = localToday(pw);
        PlanWeek.Day first = null;
        int others = 0;
        for (PlanWeek.Day d : pw.daysFor(today)) {
            if (d.beats.isEmpty() || isRecorded(d)) continue;
            if (first == null) first = d;
            else others++;
        }
        String text;
        if (first != null) {
            text = host.getString(R.string.cplan_today);
            if (others > 0) {
                text = host.getString(R.string.cplan_status_join, text, host.getString(R.string.cplan_more_today, others));
            }
        } else {
            first = PlanAutoTarget.target(pw, today, id -> store.recordedLocally(id) || serverRecorded(pw, id));
            if (first == null) {
                setContent(false);
                return;
            }
            text = host.getString(R.string.cplan_next_video, TopicSetChips.formatDate(first.date));
        }
        selected = first;
        label.setText(text);
        title.setText(first.title);
        setContent(true);
    }

    private boolean isRecorded(PlanWeek.Day d) {
        return d.isRecorded() || store.recordedLocally(d.id);
    }

    private PlanWeek.Day findDay(String id) {
        if (id == null || plan == null || plan.week == null) return null;
        for (PlanWeek.Day d : plan.week.days) {
            if (d.id.equals(id)) return d;
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

    // ---- topics ----

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
}
