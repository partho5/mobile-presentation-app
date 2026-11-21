package com.jovoc.facecampresentationrecorder.tracker;

import android.content.Context;
import android.util.Log;

import com.jovoc.facecampresentationrecorder.BuildConfig;
import com.jovoc.facecampresentationrecorder.db.AppDatabase;
import com.jovoc.facecampresentationrecorder.db.TrackedActivity;
import com.jovoc.facecampresentationrecorder.db.TrackedActivityDao;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The tracker's writer: one daemon thread with a bounded queue (overflowing work is dropped), which
 * writes every record into the Room buffer. Nothing here ever throws to a caller.
 */
public final class TrackerStore {

    /** Told after each stored record, with how many records are waiting to be uploaded. */
    public interface Listener {
        void onStored(int pending);
    }

    static volatile Listener listener;

    private static final ExecutorService EXECUTOR = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(TrackerConfig.QUEUE_CAP),
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "activity-tracker");
                    t.setDaemon(true);
                    t.setPriority(Thread.MIN_PRIORITY);
                    return t;
                }
            },
            new ThreadPoolExecutor.DiscardPolicy());

    private TrackerStore() { }

    /** Runs {@code task} on the tracker thread. Returns at once; drops the task when the queue is full. */
    static void submit(Runnable task) {
        try {
            EXECUTOR.execute(() -> {
                try {
                    task.run();
                } catch (Throwable ignored) {
                    // the tracker never fails loudly
                }
            });
        } catch (Throwable ignored) {
            // queue full or shut down: the record is dropped
        }
    }

    /** Stores {@code r} on the tracker thread. {@code afterStored} runs there too, when non-null. */
    public static void write(Context c, TrackedRecord r, Runnable afterStored) {
        submit(() -> storeOn(c, r, afterStored));
    }

    /** Stores {@code r} and waits at most {@code maxWaitMs}. False when it did not finish in time. */
    public static boolean writeAndWait(Context c, TrackedRecord r, long maxWaitMs) {
        return writeAndWait(c, Collections.singletonList(r), maxWaitMs);
    }

    /** Stores all of {@code records} in order, waiting at most {@code maxWaitMs} in total. */
    static boolean writeAndWait(Context c, List<TrackedRecord> records, long maxWaitMs) {
        try {
            Future<?> f = EXECUTOR.submit(() -> {
                for (TrackedRecord r : records) storeOn(c, r, null);
            });
            f.get(maxWaitMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Runs on the tracker thread only. */
    static void storeOn(Context c, TrackedRecord r, Runnable afterStored) {
        try {
            String json = r.toJson();
            if (json == null) return;

            TrackedActivityDao dao = AppDatabase.getInstance(c).trackedActivityDao();
            if (dao.count() >= TrackerConfig.LOCAL_MAX_ROWS) {
                dao.deleteOldest(TrackerConfig.TRIM_CHUNK);
                TrackedRecord overflow = ActivityTracker.overflowRecord(c, TrackerConfig.TRIM_CHUNK);
                String overflowJson = overflow == null ? null : overflow.toJson();
                if (overflowJson != null) dao.insert(row(overflow, overflowJson));
            }

            dao.insert(row(r, json));
            if (BuildConfig.DEBUG) Log.d("ActivityTracker", json);

            Listener l = listener;
            if (l != null) l.onStored(dao.count());
            if (afterStored != null) afterStored.run();
        } catch (Throwable ignored) {
            // a record that cannot be stored is dropped
        }
    }

    private static TrackedActivity row(TrackedRecord r, String json) {
        TrackedActivity row = new TrackedActivity();
        row.id = r.id;
        row.seq = r.seq;
        row.createdAt = System.currentTimeMillis();
        row.json = json;
        row.batchId = null;
        return row;
    }
}
