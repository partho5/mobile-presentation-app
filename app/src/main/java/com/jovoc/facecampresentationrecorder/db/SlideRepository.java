package com.jovoc.facecampresentationrecorder.db;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SlideRepository {

    private final AppDatabase db;
    private final SlideDao slideDao;
    private final ExecutorService executor;
    private final Handler mainHandler;

    public interface Callback<T> {
        void onResult(T result);
    }

    public SlideRepository(Context context) {
        this.db = AppDatabase.getInstance(context);
        this.slideDao = db.slideDao();
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void getAllOrdered(Callback<List<Slide>> callback) {
        executor.execute(() -> {
            List<Slide> slides = slideDao.getAllOrdered();
            mainHandler.post(() -> callback.onResult(slides));
        });
    }

    /** The topics of one set, in order; a null id is the user's own topics. */
    public void getSet(String setIdOrNull, Callback<List<Slide>> callback) {
        executor.execute(() -> {
            List<Slide> slides = setIdOrNull == null
                    ? slideDao.getOwnOrdered() : slideDao.getSetOrdered(setIdOrNull);
            mainHandler.post(() -> callback.onResult(slides));
        });
    }

    /** Saves one topic's speaker note (null clears it); {@code onComplete} runs on the main thread. */
    public void updateNote(int slideId, String noteOrNull, Runnable onComplete) {
        executor.execute(() -> {
            slideDao.updateNote(slideId, noteOrNull);
            if (onComplete != null) mainHandler.post(onComplete);
        });
    }

    /** Swaps a whole set for new slides in one transaction; reports the ids that were removed. */
    public void replaceSet(String setId, List<Slide> slides, Callback<List<Integer>> removedIds) {
        List<Slide> copy = new ArrayList<>(slides);
        executor.execute(() -> {
            List<Integer> removed = new ArrayList<>();
            db.runInTransaction(() -> {
                removed.addAll(slideDao.idsOfSet(setId));
                slideDao.deleteSet(setId);
                for (Slide s : copy) s.setSetId(setId);
                slideDao.insertAll(copy);
            });
            mainHandler.post(() -> {
                if (removedIds != null) removedIds.onResult(removed);
            });
        });
    }

    /** Deletes one set (null = the user's own topics); reports the ids that were removed. */
    public void deleteSetOrOwn(String setIdOrNull, Callback<List<Integer>> removedIds) {
        executor.execute(() -> {
            List<Integer> removed = new ArrayList<>();
            db.runInTransaction(() -> {
                if (setIdOrNull == null) {
                    removed.addAll(slideDao.idsOfOwn());
                    slideDao.deleteOwn();
                } else {
                    removed.addAll(slideDao.idsOfSet(setIdOrNull));
                    slideDao.deleteSet(setIdOrNull);
                }
            });
            mainHandler.post(() -> {
                if (removedIds != null) removedIds.onResult(removed);
            });
        });
    }

    /** Ids of every plan set that has topics stored. */
    public void planSetIds(Callback<List<String>> callback) {
        executor.execute(() -> {
            List<String> ids = slideDao.planSetIds();
            mainHandler.post(() -> callback.onResult(ids));
        });
    }

    public void insert(Slide slide, Callback<Long> callback) {
        executor.execute(() -> {
            long id = slideDao.insert(slide);
            mainHandler.post(() -> {
                if (callback != null) callback.onResult(id);
            });
        });
    }

    public void update(Slide slide, Runnable onComplete) {
        executor.execute(() -> {
            slideDao.update(slide);
            if (onComplete != null) mainHandler.post(onComplete);
        });
    }

    public void updateAll(List<Slide> slides, Runnable onComplete) {
        List<Slide> copy = new ArrayList<>(slides);
        executor.execute(() -> {
            for (int i = 0; i < copy.size(); i++) {
                Slide slide = copy.get(i);
                slide.setOrderIndex(i);
                slideDao.update(slide);
            }
            if (onComplete != null) mainHandler.post(onComplete);
        });
    }

    public void delete(Slide slide, Runnable onComplete) {
        executor.execute(() -> {
            slideDao.delete(slide);
            if (onComplete != null) mainHandler.post(onComplete);
        });
    }

    public void deleteAll(Runnable onComplete) {
        executor.execute(() -> {
            slideDao.deleteAll();
            if (onComplete != null) mainHandler.post(onComplete);
        });
    }
}
