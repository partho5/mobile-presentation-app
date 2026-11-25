package com.jovoc.facecampresentationrecorder.contentplan;

import android.os.Handler;
import android.os.Looper;

import com.jovoc.facecampresentationrecorder.MainActivity;
import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.auth.SessionManager;
import com.jovoc.facecampresentationrecorder.db.Slide;
import com.jovoc.facecampresentationrecorder.db.SlideRepository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The topic sets the user can switch between: "My topics" first, then one per planned video by date. Shared by the
 * Topic sets sheet and the chip row of the Topic Manager. Ids and titles only; no plan text is read or logged.
 */
public final class TopicSetList {

    /** One set. {@code setId} null = the user's own topics; {@code date} null = unknown / My topics. */
    public static final class Item {
        public final String setId;
        public final String title;
        public final String date;
        public final boolean recorded;
        public final boolean missed;
        public final boolean isToday;
        public final boolean onScreen;
        final long createdMs;

        Item(String setId, String title, String date, boolean recorded, boolean missed, boolean isToday,
             boolean onScreen, long createdMs) {
            this.setId = setId;
            this.title = title;
            this.date = date;
            this.recorded = recorded;
            this.missed = missed;
            this.isToday = isToday;
            this.onScreen = onScreen;
            this.createdMs = createdMs;
        }
    }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "topic-set-list");
        t.setDaemon(true);
        return t;
    });

    private TopicSetList() {
    }

    /** Builds the list and delivers it on the main thread. */
    public static void load(MainActivity host, SlideRepository.Callback<List<Item>> cb) {
        host.slideRepository().planSetIds(ids -> {
            final Handler main = new Handler(Looper.getMainLooper());
            final String active = host.activeSetId();
            IO.execute(() -> {
                List<Item> items = build(host, ids, active);
                main.post(() -> cb.onResult(items));
            });
        });
    }

    private static List<Item> build(MainActivity host, List<String> ids, String active) {
        List<Item> out = new ArrayList<>();
        out.add(new Item(null, null, null, false, false, false, Objects.equals(null, active), 0));
        try {
            PlanSetIndex index = PlanSetIndex.get(host);
            ContentPlanStore store = ContentPlanStore.get(host);
            PlanWeek pw = null;
            try {
                String brand = SessionManager.get(host).repository().store().workspaceId();
                String json = brand == null ? null : store.weekJson(brand);
                if (json != null) pw = PlanWeek.parse(json);
            } catch (Exception e) {
                // no readable cache: the device date is used
            }
            String today = pw == null ? LocalDate.now().toString() : ContentPlanCard.localToday(pw);
            if (today == null || today.isEmpty()) today = LocalDate.now().toString();

            Map<String, PlanSetIndex.Entry> entries = new HashMap<>();
            for (PlanSetIndex.Entry e : index.all()) entries.put(e.dayId, e);

            List<Item> videos = new ArrayList<>();
            for (String id : ids) {
                if (id == null || !id.startsWith(Slide.PLAN_SET_PREFIX)) continue;
                String dayId = id.substring(Slide.PLAN_SET_PREFIX.length());
                PlanSetIndex.Entry e = entries.get(dayId);
                String date = e == null || e.date == null || e.date.isEmpty() ? null : e.date;
                String title = e == null || e.title == null || e.title.isEmpty()
                        ? host.getString(R.string.cplan_set_unknown) : e.title;
                boolean recorded = store.recordedLocally(dayId) || serverRecorded(pw, dayId);
                boolean missed = date != null && date.compareTo(today) < 0 && !recorded;
                boolean isToday = date != null && date.equals(today);
                videos.add(new Item(id, title, date, recorded, missed, isToday,
                        Objects.equals(id, active), e == null ? 0 : e.createdMs));
            }
            Collections.sort(videos, (a, b) -> {
                if (a.date == null || b.date == null) {
                    if (a.date == null && b.date == null) return Long.compare(a.createdMs, b.createdMs);
                    return a.date == null ? 1 : -1;
                }
                int c = a.date.compareTo(b.date);
                return c != 0 ? c : Long.compare(a.createdMs, b.createdMs);
            });
            out.addAll(videos);
        } catch (RuntimeException e) {
            // keep at least "My topics"
        }
        return out;
    }

    private static boolean serverRecorded(PlanWeek pw, String dayId) {
        if (pw == null || pw.week == null) return false;
        for (PlanWeek.Day d : pw.week.days) {
            if (d.id.equals(dayId)) return d.isRecorded();
        }
        return false;
    }

    /** True when the list holds at least one video set. */
    public static boolean hasVideos(List<Item> items) {
        return items != null && items.size() > 1;
    }
}
