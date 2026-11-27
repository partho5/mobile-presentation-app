package com.jovoc.facecampresentationrecorder.contentplan;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.jovoc.facecampresentationrecorder.MainActivity;
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
 * Keeps the content plan of the recorder screen up to date without showing anything of its own: it refreshes the
 * week, saves every video's topic set, and puts the next video's set on screen once per video. Practice opens from the
 * top bar for whatever set is on screen (MainActivity). No plan text is logged.
 */
public final class ContentPlanLoader {

    static final String LINK_TARGET = "content-plan";
    private static final long FRESH_MS = 10L * 60 * 1000;
    private static final String PREF_LAST_AUTO_DAY = "cplan_last_auto_day";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "content-plan-loader");
        t.setDaemon(true);
        return t;
    });

    private final MainActivity host;
    private final ContentPlanStore store;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SlideRepository slides;
    private final PlanSetSync sync;

    private String brandId;
    private volatile boolean fetching;
    private String fetchedForDate;

    public ContentPlanLoader(MainActivity host, ContentPlanStore store) {
        this.host = host;
        this.store = store;
        this.slides = host.slideRepository();
        this.sync = new PlanSetSync(slides, PlanSetIndex.get(host), host::discardSlideData);
    }

    // ---- loading ----

    /**
     * Reads the cached week at once, then asks the server unless the last answer for this brand is under 10 minutes
     * old (or {@code force}, or the local date changed). Inside the 10 minutes it asks the cheap week stamp instead and
     * fetches the week only when the stamp changed. Any failure keeps the cached week.
     */
    public void refresh(boolean force) {
        final Context app = host.getApplicationContext();
        if (fetching) return;
        fetching = true;
        IO.execute(() -> {
            SessionManager session = SessionManager.get(app);
            if (!session.hasSession()) {
                fetching = false;
                main.post(() -> brandId = null);
                return;
            }
            String cacheBrand = session.repository().store().workspaceId();
            PlanWeek cached = readCache(cacheBrand);
            final String cachedBrand = cacheBrand;
            main.post(() -> brandId = cachedBrand);
            try {
                String today = cached == null ? null : localToday(cached);
                boolean fresh = cacheBrand != null && cached != null
                        && System.currentTimeMillis() - store.fetchedAt(cacheBrand) < FRESH_MS
                        && today != null && (fetchedForDate == null || today.equals(fetchedForDate));
                String brand = fresh && !force ? cacheBrand : session.brandForNewJob();
                if (brand == null) return;
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
                    sync.sync(fetched, brand, () -> maybeAutoSwitch(fetched, true));
                });
            } catch (ContentPlanException | JSONException e) {
                // Offline or a server problem: the cached week stays, silently.
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

    /** Re-reads the cache (a recording may have marked a day recorded) and moves on to the next video if due. */
    public void rebind() {
        if (brandId == null) return;
        PlanWeek cached = readCache(brandId);
        if (cached != null) maybeAutoSwitch(cached, false);
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

    /**
     * Once per video: puts the next video's saved set on screen. No toast, no dialog. On a fresh app launch any new
     * target switches; after a week fetched on resume ({@code fetched}) only the owner's own instant video does (see
     * {@link PlanAutoTarget#decide}). The fresh-launch flag stays set while the decision could not be made yet
     * (recording, Topic Manager open, set not saved yet) so a later call retries.
     */
    private void maybeAutoSwitch(PlanWeek pw, boolean fetched) {
        final boolean fresh = host.isFreshLaunch();
        if (!fresh && !fetched) return;
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
                    host.isRecordingNow(), host.isTopicManagerOpen(), !existing.isEmpty(), fresh);
            if (s != PlanAutoTarget.Switch.NONE) prefs.edit().putString(PREF_LAST_AUTO_DAY, day.id).apply();
            if (s == PlanAutoTarget.Switch.SWITCH) host.switchSlideSet(setId);
            if (s != PlanAutoTarget.Switch.NONE || day.id.equals(lastAuto)) host.clearFreshLaunch();
        });
    }

    private static boolean serverRecorded(PlanWeek pw, String dayId) {
        if (pw.week == null) return false;
        for (PlanWeek.Day d : pw.week.days) {
            if (d.id.equals(dayId)) return d.isRecorded();
        }
        return false;
    }
}
