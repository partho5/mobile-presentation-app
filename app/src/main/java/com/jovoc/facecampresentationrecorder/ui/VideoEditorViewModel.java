package com.jovoc.facecampresentationrecorder.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.lifecycle.ViewModel;

import com.jovoc.facecampresentationrecorder.edit.EditHistory;
import com.jovoc.facecampresentationrecorder.edit.EditProject;
import com.jovoc.facecampresentationrecorder.edit.EditRenderer;
import com.jovoc.facecampresentationrecorder.edit.EditTimelineMath;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Everything the editor screen must keep across rotation: the edit history, the playhead, the
 * thumbnails and a running save. The screen re-reads this state whenever {@link Listener#onChanged}
 * fires, so a rotation mid-save simply re-attaches to the same render.
 */
public class VideoEditorViewModel extends ViewModel {

    private static final String TAG = "VideoEditorViewModel";
    static final int THUMB_COUNT = 14;
    private static final int THUMB_HEIGHT_PX = 160;

    public enum SaveState { IDLE, RUNNING, DONE, FAILED }

    public interface Listener {
        /** Something in the view model changed; re-read it. Always on the main thread. */
        void onChanged();
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final ExecutorService renderer = Executors.newSingleThreadExecutor();

    private Listener listener;
    private EditHistory history;
    private boolean loadStarted;
    private boolean loadFailed;
    private long durationMs;
    private Bitmap[] thumbs = new Bitmap[0];
    private volatile boolean cleared;

    // UI state that outlives the activity
    private long playheadMs;
    private boolean playing;

    // Save
    private SaveState saveState = SaveState.IDLE;
    private volatile int percent;
    private File output;
    private AtomicBoolean cancelFlag;

    public void setListener(Listener l) {
        this.listener = l;
    }

    private void changed() {
        main.post(() -> {
            if (!cleared && listener != null) listener.onChanged();
        });
    }

    // ---------------------------------------------------------------- loading

    /** Reads the length and thumbnails of {@code masterPath} once; later calls do nothing. */
    public void load(String masterPath) {
        if (loadStarted) return;
        loadStarted = true;
        history = new EditHistory(EditProject.wholeFile(masterPath));
        loader.execute(() -> {
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            try {
                mmr.setDataSource(masterPath);
                long dur = parseLong(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                if (dur <= 0) throw new IOException("No duration");
                durationMs = dur;
                changed();

                int w = parseInt(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                int h = parseInt(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                int rot = parseInt(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
                if (rot == 90 || rot == 270) { int t = w; w = h; h = t; }
                int thumbH = THUMB_HEIGHT_PX;
                int thumbW = (w > 0 && h > 0) ? Math.max(1, Math.round(thumbH * (w / (float) h))) : thumbH;

                Bitmap[] frames = new Bitmap[THUMB_COUNT];
                thumbs = frames;
                for (int i = 0; i < THUMB_COUNT && !cleared; i++) {
                    long atUs = (long) ((i + 0.5) * dur / THUMB_COUNT) * 1000L;
                    try {
                        frames[i] = mmr.getScaledFrameAtTime(atUs,
                                MediaMetadataRetriever.OPTION_CLOSEST_SYNC, thumbW, thumbH);
                    } catch (RuntimeException e) {
                        Log.w(TAG, "Thumbnail " + i + " failed", e);
                    }
                    changed();
                }
            } catch (Exception e) {
                Log.e(TAG, "Could not read " + masterPath, e);
                loadFailed = true;
                changed();
            } finally {
                try { mmr.release(); } catch (Exception ignored) {}
            }
        });
    }

    private static long parseLong(String s) {
        try { return s == null ? 0 : Long.parseLong(s); } catch (NumberFormatException e) { return 0; }
    }

    private static int parseInt(String s) {
        try { return s == null ? 0 : Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    public boolean isLoadFailed() { return loadFailed; }
    public boolean isReady() { return history != null && durationMs > 0; }
    public long getDurationMs() { return durationMs; }
    public Bitmap[] getThumbs() { return thumbs; }
    public EditHistory getHistory() { return history; }
    public EditProject getProject() { return history.current(); }

    // ---------------------------------------------------------------- UI state

    public long getPlayheadMs() { return playheadMs; }
    public void setPlayheadMs(long ms) { playheadMs = ms; }
    public boolean isPlaying() { return playing; }
    public void setPlaying(boolean p) { playing = p; }

    // ---------------------------------------------------------------- save

    public SaveState getSaveState() { return saveState; }
    public int getPercent() { return percent; }
    public File getOutput() { return output; }

    /** Back to idle once the screen has shown a finished or failed save. */
    public void clearSaveResult() {
        if (saveState == SaveState.DONE || saveState == SaveState.FAILED) {
            saveState = SaveState.IDLE;
            percent = 0;
        }
    }

    /** Renders the current edit to a new file beside the master, on a background thread. */
    public void startSave(Context context) {
        if (saveState == SaveState.RUNNING || !isReady()) return;
        final Context app = context.getApplicationContext();
        final EditProject project = history.current();
        final File out = EditTimelineMath.nextOutputFile(new File(project.getMasterPath()));
        final AtomicBoolean cancel = new AtomicBoolean(false);
        cancelFlag = cancel;
        output = out;
        percent = 0;
        saveState = SaveState.RUNNING;
        changed();

        renderer.execute(() -> {
            SaveState result;
            try {
                new EditRenderer().withCancelFlag(cancel).withContext(app)
                        .render(project, out, p -> {
                            percent = p;
                            changed();
                        });
                result = SaveState.DONE;
            } catch (CancellationException e) {
                result = SaveState.IDLE;
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "Save failed", e);
                result = SaveState.FAILED;
            }
            final SaveState finalState = result;
            main.post(() -> {
                saveState = finalState;
                if (finalState == SaveState.DONE) history.markSaved();
                if (finalState != SaveState.DONE) output = null;
                if (!cleared && listener != null) listener.onChanged();
            });
        });
    }

    public void cancelSave() {
        AtomicBoolean f = cancelFlag;
        if (f != null) f.set(true);
    }

    @Override
    protected void onCleared() {
        cleared = true;
        cancelSave();
        listener = null;
        loader.shutdownNow();
        renderer.shutdown();
    }
}
