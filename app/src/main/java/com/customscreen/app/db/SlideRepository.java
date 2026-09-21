package com.customscreen.app.db;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SlideRepository {

    private final SlideDao slideDao;
    private final ExecutorService executor;
    private final Handler mainHandler;

    public interface Callback<T> {
        void onResult(T result);
    }

    public SlideRepository(Context context) {
        AppDatabase db = AppDatabase.getInstance(context);
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
}
