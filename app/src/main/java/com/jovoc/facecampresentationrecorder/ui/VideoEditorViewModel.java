package com.jovoc.facecampresentationrecorder.ui;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.net.Uri;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;

import androidx.lifecycle.ViewModel;

import com.jovoc.facecampresentationrecorder.edit.AudioPeaks;
import com.jovoc.facecampresentationrecorder.edit.EditGroupMath;
import com.jovoc.facecampresentationrecorder.edit.EditHistory;
import com.jovoc.facecampresentationrecorder.edit.EditProject;
import com.jovoc.facecampresentationrecorder.edit.EditRenderer;
import com.jovoc.facecampresentationrecorder.edit.EditTimelineMath;
import com.jovoc.facecampresentationrecorder.util.VideoCropHelper;
import com.jovoc.facecampresentationrecorder.util.VideoGroupStore;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
    private final ExecutorService audioLoader = Executors.newSingleThreadExecutor();

    private Listener listener;
    private EditHistory history;
    private boolean loadStarted;
    private boolean loadFailed;
    private long durationMs;
    private Bitmap[] thumbs = new Bitmap[0];
    private volatile boolean cleared;

    // Audio lane: what the lane needs to draw, keyed by master path or track uri
    public static final int NO_TRACK = -2;
    public static final int ORIGINAL_TRACK = -1;
    private final Map<String, float[]> peaks = new ConcurrentHashMap<>();
    private final Map<String, Long> audioDurations = new ConcurrentHashMap<>();
    private final Map<String, String> audioNames = new ConcurrentHashMap<>();
    private final AtomicBoolean audioCancel = new AtomicBoolean(false);
    private volatile boolean masterHasAudio;
    private boolean audioBusy;
    private boolean audioError;
    private int selectedTrack = NO_TRACK;
    private Boolean audioExpanded;

    // UI state that outlives the activity
    private long playheadMs;
    private boolean playing;

    // Save
    private SaveState saveState = SaveState.IDLE;
    private volatile int percent;
    private File output;
    private boolean publishAfterSave;
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
                masterHasAudio = "yes".equals(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO));
                changed();
                if (masterHasAudio) loadPeaks(null, masterPath, dur);

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

    // ---------------------------------------------------------------- audio lane

    /** Computes the loudness profile of {@code location} in the background and caches it. */
    private void loadPeaks(Context context, String location, long durationMs) {
        if (cleared || peaks.containsKey(location)) return;
        try {
            audioLoader.execute(() -> {
            try {
                float[] p = AudioPeaks.compute(context, location, durationMs, AudioPeaks.BUCKETS, audioCancel);
                peaks.put(location, p);
            } catch (IOException | RuntimeException e) {
                // No picture for this track; the lane shows a plain bar
                Log.w(TAG, "No waveform for " + location, e);
            }
            changed();
        });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Screen is gone for good
        }
    }

    /**
     * Reads the length and name of a picked audio file, then adds it as a track starting at the
     * playhead. Fails quietly into {@link #takeAudioError}.
     */
    public void addAudio(Context context, Uri uri) {
        final Context app = context.getApplicationContext();
        audioBusy = true;
        changed();
        audioLoader.execute(() -> {
            long dur = 0;
            boolean hasAudio = false;
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            try {
                mmr.setDataSource(app, uri);
                dur = parseLong(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                hasAudio = "yes".equals(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO));
            } catch (RuntimeException e) {
                Log.w(TAG, "Cannot read " + uri, e);
            } finally {
                try { mmr.release(); } catch (Exception ignored) {}
            }
            final String key = uri.toString();
            final long finalDur = dur;
            final boolean ok = hasAudio && dur > 0;
            String name = null;
            if (ok) {
                try (Cursor c = app.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                        null, null, null)) {
                    if (c != null && c.moveToFirst()) name = c.getString(0);
                } catch (RuntimeException ignored) {
                }
                audioDurations.put(key, dur);
                audioNames.put(key, name == null ? "" : name);
            }
            main.post(() -> {
                audioBusy = false;
                if (!cleared && history != null) {
                    if (ok) {
                        EditProject p = history.current();
                        long at = p.sourceToOutput(playheadMs, durationMs);
                        long outDur = p.outputDurationMs(durationMs);
                        history.push(p.withAddedTrack(new EditProject.AddedAudio(key, 0, 0,
                                Math.max(0, Math.min(at < 0 ? 0 : at, outDur)), 1f)));
                        selectedTrack = history.current().getAdded().size() - 1;
                    } else {
                        audioError = true;
                    }
                }
                if (!cleared && listener != null) listener.onChanged();
            });
            if (ok) loadPeaks(app, key, finalDur);
        });
    }

    public boolean hasMasterAudio() { return masterHasAudio; }
    public boolean isAudioBusy() { return audioBusy; }
    /** The waveform for the master path or a track uri, or null while it is still being computed. */
    public float[] getPeaks(String key) { return peaks.get(key); }
    /** Length of an added file, or 0 when unknown. */
    public long getAudioDurationMs(String uri) {
        Long d = audioDurations.get(uri);
        return d == null ? 0 : d;
    }
    public String getAudioName(String uri) { return audioNames.get(uri); }
    /** Whether the audio lane is open; null until the screen chooses a default. */
    public Boolean getAudioExpanded() { return audioExpanded; }
    public void setAudioExpanded(boolean e) { audioExpanded = e; }
    public int getSelectedTrack() { return selectedTrack; }
    public void setSelectedTrack(int t) { selectedTrack = t; }

    /** True once after a picked file turned out not to be usable audio. */
    public boolean takeAudioError() {
        boolean e = audioError;
        audioError = false;
        return e;
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

    /** True when the running or finished save was started by Publish (publish the result after). */
    public boolean isPublishAfterSave() { return publishAfterSave; }

    /** Back to idle once the screen has shown a finished or failed save. */
    public void clearSaveResult() {
        if (saveState == SaveState.DONE || saveState == SaveState.FAILED) {
            saveState = SaveState.IDLE;
            percent = 0;
        }
    }

    /** Renders the current edit to a new file beside the master, on a background thread. */
    public void startSave(Context context) {
        startSave(context, false);
    }

    /**
     * Same as {@link #startSave(Context)}; the result is a new group: the edited file plus its own
     * crops in the ratios the master's group had (9:16 first), the old group untouched.
     */
    public void startSave(Context context, boolean publishAfter) {
        if (saveState == SaveState.RUNNING || !isReady()) return;
        publishAfterSave = publishAfter;
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
                startGroupCrops(app, project.getMasterPath(), out);
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

    /**
     * Gives the edited file a group of its own, before any list scan can see it, and crops it to
     * the ratios the old group had. Runs on the render thread; cropAsync is safe from any thread.
     */
    private static void startGroupCrops(Context app, String masterPath, File out) {
        List<String> oldGroup = VideoGroupStore.getGroupPaths(app, masterPath);
        List<int[]> ratios = EditGroupMath.cropRatiosOf(oldGroup);
        synchronized (VideoGroupStore.lockForRegistration()) {
            VideoGroupStore.ensureGroup(app, out.getAbsolutePath());
        }
        if (!ratios.isEmpty()) {
            VideoCropHelper.cropAsync(app, out.getAbsolutePath(), ratios, null);
        }
    }

    public void cancelSave() {
        AtomicBoolean f = cancelFlag;
        if (f != null) f.set(true);
    }

    @Override
    protected void onCleared() {
        cleared = true;
        cancelSave();
        audioCancel.set(true);
        listener = null;
        loader.shutdownNow();
        audioLoader.shutdownNow();
        renderer.shutdown();
    }
}
