package com.jovoc.facecampresentationrecorder.contentplan;

import com.jovoc.facecampresentationrecorder.db.Slide;
import com.jovoc.facecampresentationrecorder.db.SlideRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * Saves every video of the fetched week as its own topic set (one TEXT topic per beat), one video after the other.
 * An unedited set is silently replaced by a newer server version; a set the user may have edited, or emptied, is never
 * touched. Main-thread glue: the repository callbacks run on the main thread. Plan texts are never logged.
 */
public final class PlanSetSync {

    private final SlideRepository slides;
    private final PlanSetIndex index;
    private final SlideRepository.Callback<List<Integer>> onRemoved;
    private boolean running;

    public PlanSetSync(SlideRepository slides, PlanSetIndex index,
                       SlideRepository.Callback<List<Integer>> onRemoved) {
        this.slides = slides;
        this.index = index;
        this.onRemoved = onRemoved;
    }

    /**
     * One TEXT slide per beat from its hook, in order, with the beat's spoken line as the speaker note (Practice shows
     * it); the 200-character cut is only a safety net.
     */
    static List<Slide> buildSlides(PlanWeek.Day day) {
        List<Slide> out = new ArrayList<>();
        int i = 0;
        for (PlanWeek.Beat b : day.beats) {
            String hook = b.hook.length() > 200 ? b.hook.substring(0, 200) : b.hook;
            Slide s = new Slide(Slide.TYPE_TEXT, i++, hook, null);
            s.setSpeakerNote(b.say == null || b.say.isEmpty() ? null : b.say);
            out.add(s);
        }
        return out;
    }

    /**
     * Brings the saved sets of {@code pw}'s videos up to date, then calls {@code done} (always, once, on the main
     * thread). Never throws into the caller.
     */
    public void sync(PlanWeek pw, String brandId, Runnable done) {
        if (pw == null || pw.week == null || running) {
            done.run();
            return;
        }
        final List<PlanWeek.Day> todo = new ArrayList<>();
        for (PlanWeek.Day d : pw.week.days) {
            if (!d.beats.isEmpty()) todo.add(d);
        }
        running = true;
        step(todo, 0, brandId, done);
    }

    private void finish(Runnable done) {
        running = false;
        done.run();
    }

    private void step(List<PlanWeek.Day> todo, int i, String brandId, Runnable done) {
        if (i >= todo.size()) {
            finish(done);
            return;
        }
        final PlanWeek.Day day = todo.get(i);
        final Runnable next = () -> step(todo, i + 1, brandId, done);
        try {
            final String setId = Slide.planSetId(day.id);
            slides.getSet(setId, existing -> {
                try {
                    PlanSetIndex.Entry entry = index.get(day.id);
                    PlanSetPlanner.Action action = PlanSetPlanner.decide(entry, !existing.isEmpty(),
                            PlanSetSignature.of(existing), day.version);
                    switch (action) {
                        case CREATE:
                        case REPLACE: {
                            final List<Slide> built = buildSlides(day);
                            final String signature = PlanSetSignature.of(built);
                            slides.replaceSet(setId, built, removed -> {
                                try {
                                    onRemoved.onResult(removed);
                                    index.put(entryFor(day, brandId, signature, entry));
                                } catch (RuntimeException e) {
                                    // keep going with the other videos
                                }
                                next.run();
                            });
                            return;
                        }
                        case ADOPT:
                            index.put(entryFor(day, brandId, null, entry));
                            break;
                        default:
                            break;
                    }
                } catch (RuntimeException e) {
                    // a failure for one video must not stop the others
                }
                next.run();
            });
        } catch (RuntimeException e) {
            finish(done);
        }
    }

    private static PlanSetIndex.Entry entryFor(PlanWeek.Day day, String brandId, String signature,
                                               PlanSetIndex.Entry previous) {
        long created = previous != null && previous.createdMs > 0 ? previous.createdMs : System.currentTimeMillis();
        return new PlanSetIndex.Entry(day.id, brandId, day.title, day.date, day.kind, day.version, signature,
                created, day.closingLine);
    }
}
