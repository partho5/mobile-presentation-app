package com.jovoc.facecampresentationrecorder.ui;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.os.Handler;
import android.net.Uri;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;

import androidx.lifecycle.ViewModel;

import com.jovoc.facecampresentationrecorder.edit.AudioPeaks;
import com.jovoc.facecampresentationrecorder.edit.EditDraftStore;
import com.jovoc.facecampresentationrecorder.edit.EditGroupMath;
import com.jovoc.facecampresentationrecorder.edit.EditHistory;
import com.jovoc.facecampresentationrecorder.edit.EditProject;
import com.jovoc.facecampresentationrecorder.edit.EditRenderer;
import com.jovoc.facecampresentationrecorder.edit.EditTimelineMath;
import com.jovoc.facecampresentationrecorder.edit.SectionSelection;
import com.jovoc.facecampresentationrecorder.subtitle.SubtitleCache;
import com.jovoc.facecampresentationrecorder.subtitle.SubtitleEmbedStore;
import com.jovoc.facecampresentationrecorder.publish.CaptionUsage;
import com.jovoc.facecampresentationrecorder.util.RecordSettings;
import com.jovoc.facecampresentationrecorder.util.VideoCropHelper;
import com.jovoc.facecampresentationrecorder.util.VideoGroupStore;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
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

    /** Why the last save failed, so the screen can say something useful. */
    public enum SaveError { GENERIC, NO_SPACE, TOO_LONG }

    private static final String DRAFT_DIR = "editor_drafts";
    /** Storage left below this after a failed save counts as "ran out of space". */
    private static final long LOW_SPACE_BYTES = 32L * 1024 * 1024;

    public interface Listener {
        /** Something in the view model changed; re-read it. Always on the main thread. */
        void onChanged();
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final ExecutorService renderer = Executors.newSingleThreadExecutor();
    private final ExecutorService audioLoader = Executors.newSingleThreadExecutor();
    /** Waveforms get their own thread: a long recording's takes a while and must not delay adding a track. */
    private final ExecutorService peakLoader = Executors.newSingleThreadExecutor();

    private Listener listener;
    private EditHistory history;
    private boolean loadStarted;
    private volatile boolean loadFailed;
    private volatile long durationMs;
    private volatile Bitmap[] thumbs = new Bitmap[0];
    private File draftDir;
    private String masterPath;
    private boolean draftRestored;
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
    private SectionSelection selection = SectionSelection.EMPTY;
    private float timelineZoom = 1f;
    private long timelineStartMs;

    // Save
    private SaveState saveState = SaveState.IDLE;
    private volatile int percent;
    private File output;
    private SaveError saveError = SaveError.GENERIC;
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

    /**
     * Reads the length and thumbnails of {@code masterPath} once; later calls do nothing. When the
     * app was killed with unsaved edits, they come back from the draft (see {@link #persistDraft}).
     */
    public void load(Context context, String masterPath) {
        if (loadStarted) return;
        loadStarted = true;
        this.masterPath = masterPath;
        final Context app = context.getApplicationContext();
        draftDir = new File(app.getFilesDir(), DRAFT_DIR);
        history = new EditHistory(EditProject.wholeFile(masterPath));
        EditDraftStore.Draft draft = EditDraftStore.load(draftDir, masterPath, System.currentTimeMillis());
        if (draft != null) {
            history.push(draft.project);
            playheadMs = draft.playheadMs;
            draftRestored = true;
        }
        if (draft != null && !draft.project.getAdded().isEmpty()) {
            restoreTracks(app, new ArrayList<>(draft.project.getAdded()));
        }
        loader.execute(() -> {
            EditDraftStore.prune(draftDir, System.currentTimeMillis());
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
            peakLoader.execute(() -> {
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
            final String key = uri.toString();
            final AudioInfo info = probeAudio(app, uri);
            final boolean ok = info != null;
            if (ok) {
                audioDurations.put(key, info.durationMs);
                audioNames.put(key, info.name);
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
            if (ok) loadPeaks(app, key, info.durationMs);
        });
    }

    /** Length and display name of a usable audio file. */
    private static final class AudioInfo {
        final long durationMs;
        final String name;

        AudioInfo(long durationMs, String name) {
            this.durationMs = durationMs;
            this.name = name;
        }
    }

    /** Reads an audio file's length and name; null when it cannot be opened or has no audio. */
    private static AudioInfo probeAudio(Context app, Uri uri) {
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
        if (!hasAudio || dur <= 0) return null;
        String name = null;
        try (Cursor c = app.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (RuntimeException ignored) {
        }
        return new AudioInfo(dur, name == null ? "" : name);
    }

    /**
     * After a restore from a draft the added files are looked up again (their length, name and
     * waveform are not in the draft). A file that is gone or no longer readable (grant lost) is
     * dropped from the project, with the usual "couldn't use that file" message.
     */
    private void restoreTracks(Context app, List<EditProject.AddedAudio> tracks) {
        audioLoader.execute(() -> {
            List<String> missing = new ArrayList<>();
            for (EditProject.AddedAudio t : tracks) {
                if (cleared) return;
                AudioInfo info = probeAudio(app, Uri.parse(t.uri));
                if (info == null) {
                    missing.add(t.uri);
                } else {
                    audioDurations.put(t.uri, info.durationMs);
                    audioNames.put(t.uri, info.name);
                    loadPeaks(app, t.uri, info.durationMs);
                }
            }
            main.post(() -> {
                if (cleared || history == null) return;
                if (!missing.isEmpty()) {
                    EditProject p = history.current();
                    List<EditProject.AddedAudio> keep = new ArrayList<>();
                    for (EditProject.AddedAudio a : p.getAdded()) {
                        if (!missing.contains(a.uri)) keep.add(a);
                    }
                    history.push(p.withAdded(keep));
                    selectedTrack = NO_TRACK;
                    audioError = true;
                }
                if (listener != null) listener.onChanged();
            });
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
    /** The section picked for removal, on the edited timeline; kept across rotation. */
    public SectionSelection getSelection() { return selection; }
    public void setSelection(SectionSelection s) { selection = s == null ? SectionSelection.EMPTY : s; }

    /** Timeline zoom (1 = whole video) and the scroll position; kept across rotation. */
    public float getTimelineZoom() { return timelineZoom; }
    public long getTimelineStartMs() { return timelineStartMs; }
    public void setTimelineView(float zoom, long startMs) {
        timelineZoom = zoom;
        timelineStartMs = startMs;
    }

    // ---------------------------------------------------------------- draft

    /** True once, when this session started from edits that survived the app being killed. */
    public boolean takeDraftRestored() {
        boolean r = draftRestored;
        draftRestored = false;
        return r;
    }

    /** Writes the unsaved edit to a small file, so it can come back if the app is killed. */
    public void persistDraft() {
        if (draftDir == null || history == null || !isReady()) return;
        if (!history.isDirty()) {
            clearDraft(); // everything undone: an older draft must not come back
            return;
        }
        EditDraftStore.save(draftDir, history.current(), playheadMs, System.currentTimeMillis());
    }

    /** Forgets the draft: after a save, or when the user chose to discard. */
    public void clearDraft() {
        if (draftDir != null && masterPath != null) EditDraftStore.clear(draftDir, masterPath);
    }

    // ---------------------------------------------------------------- save

    public SaveState getSaveState() { return saveState; }
    public int getPercent() { return percent; }
    public File getOutput() { return output; }
    public SaveError getSaveError() { return saveError; }

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
        final int cropCount = EditGroupMath.cropRatiosOf(
                VideoGroupStore.getGroupPaths(app, project.getMasterPath())).size();
        final AtomicBoolean cancel = new AtomicBoolean(false);
        cancelFlag = cancel;
        output = out;
        percent = 0;
        saveState = SaveState.RUNNING;
        changed();

        renderer.execute(() -> {
            SaveState result;
            SaveError error = SaveError.GENERIC;
            try {
                new EditRenderer().withCancelFlag(cancel).withContext(app).withCropCopies(cropCount)
                        .render(project, out, p -> {
                            percent = p;
                            changed();
                        });
                if (cancel.get()) {
                    // Cancelled after the file landed: do not leave an unregistered, uncropped video
                    //noinspection ResultOfMethodCallIgnored
                    out.delete();
                    throw new CancellationException("Edit cancelled");
                }
                File masterFile = new File(project.getMasterPath());
                boolean burnedDuringRender = SubtitleEmbedStore.isEmbedded(app, masterFile)
                        || (RecordSettings.subtitlesEnabled(app)
                        && SubtitleCache.loadForVideo(app.getCacheDir(), masterFile) != null);
                if (burnedDuringRender) {
                    SubtitleEmbedStore.markEmbedded(app, out);
                    CaptionUsage.useFor(app, masterFile);
                }
                startGroupCrops(app, project.getMasterPath(), out);
                MediaScannerConnection.scanFile(app, new String[]{out.getAbsolutePath()}, null, null);
                result = SaveState.DONE;
            } catch (CancellationException e) {
                result = SaveState.IDLE;
            } catch (EditRenderer.NotEnoughSpaceException e) {
                Log.w(TAG, "Save failed: " + e.getMessage());
                result = SaveState.FAILED;
                error = SaveError.NO_SPACE;
            } catch (EditRenderer.TooLongException e) {
                Log.w(TAG, "Save failed: " + e.getMessage());
                result = SaveState.FAILED;
                error = SaveError.TOO_LONG;
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "Save failed", e);
                result = SaveState.FAILED;
                // A write that dies on a full disk looks like any other error: ask the disk
                File dir = out.getAbsoluteFile().getParentFile();
                if (dir != null && dir.getUsableSpace() < LOW_SPACE_BYTES) error = SaveError.NO_SPACE;
            }
            final SaveState finalState = result;
            final SaveError finalError = error;
            main.post(() -> {
                saveState = finalState;
                saveError = finalError;
                if (finalState == SaveState.DONE) {
                    history.markSaved();
                    clearDraft();
                    // Kept until a list screen shows it, in case the editor was left before the end
                    EditorLauncher.rememberOffer(app, project.getMasterPath(), out.getAbsolutePath());
                }
                if (finalState != SaveState.DONE) output = null;
                if (!cleared && listener != null) listener.onChanged();
            });
        });
    }

    /**
     * Puts the edited file in the master's group (same strip colour, shown as "Edited"), before any
     * list scan can see it, and crops it to the ratios the group had. Runs on the render thread;
     * cropAsync is safe from any thread.
     */
    private static void startGroupCrops(Context app, String masterPath, File out) {
        List<String> oldGroup = VideoGroupStore.getGroupPaths(app, masterPath);
        List<int[]> ratios = EditGroupMath.cropRatiosOf(oldGroup);
        synchronized (VideoGroupStore.lockForRegistration()) {
            VideoGroupStore.assign(app, out.getAbsolutePath(),
                    VideoGroupStore.ensureGroup(app, masterPath));
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
        peakLoader.shutdownNow();
        renderer.shutdown();
    }
}
