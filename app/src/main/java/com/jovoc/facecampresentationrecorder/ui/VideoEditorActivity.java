package com.jovoc.facecampresentationrecorder.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.RangeSlider;
import com.google.android.material.slider.Slider;
import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.edit.AudioPeaks;
import com.jovoc.facecampresentationrecorder.edit.EditHistory;
import com.jovoc.facecampresentationrecorder.edit.EditProject;
import com.jovoc.facecampresentationrecorder.edit.EditProject.Range;
import com.jovoc.facecampresentationrecorder.edit.EditTimelineMath;
import com.jovoc.facecampresentationrecorder.edit.SectionSelection;
import com.jovoc.facecampresentationrecorder.edit.WaveformMath;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import com.jovoc.facecampresentationrecorder.publish.PostRecordTranscriber;
import com.jovoc.facecampresentationrecorder.subtitle.SubtitleCache;
import com.jovoc.facecampresentationrecorder.subtitle.SubtitleEmbedStore;
import com.jovoc.facecampresentationrecorder.subtitle.SubtitleStyle;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The video editor: preview with a timeline; the playhead is moved (drag, tap, nudge) and Set start /
 * Set end mark a section to remove (the rest is joined and the timeline shows the edited result), undo/redo, and a Save that renders the result to a new file beside the master (the master is never touched).
 * State lives in {@link VideoEditorViewModel}, so rotation keeps the edits and a running save.
 * Playback skips the deleted parts, so the preview matches what Save will write.
 *
 * Launch with {@link #newIntent}. On a successful save the activity finishes with RESULT_OK and
 * {@link #EXTRA_OUTPUT_PATH}. Publish is Save, then the existing publish flow (see
 * {@link PublishLauncher}); with no edits it publishes the master as it is.
 */
public class VideoEditorActivity extends AppCompatActivity
        implements VideoEditorViewModel.Listener, EditTimelineView.Listener,
                   AudioLaneView.Listener {

    public static final String EXTRA_MASTER_PATH = "master_path";
    public static final String EXTRA_OUTPUT_PATH = "output_path";

    /** Playback jumps over a deleted stretch when it gets this close to the end of a kept one. */
    private static final long SKIP_MARGIN_MS = 25;
    private static final long TICK_MS = 30;
    /** One nudge moves the playhead this far; holding repeats, and speeds up after a while. */
    private static final long NUDGE_MS = 100;
    private static final long NUDGE_FAST_MS = 500;
    private static final long NUDGE_REPEAT_MS = 100;
    private static final int NUDGE_FAST_AFTER = 10;

    public static Intent newIntent(Context context, String masterPath) {
        return new Intent(context, VideoEditorActivity.class).putExtra(EXTRA_MASTER_PATH, masterPath);
    }

    private VideoEditorViewModel vm;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private VideoView video;
    private KaraokeSubtitleView subtitlesView;
    private EditTimelineView timeline;
    private TextView tvStatus, tvTime, tvSavePercent, tvHint;
    private MaterialButton btnUndo, btnRedo, btnPlay, btnRemove, btnSave, btnPublish;
    private MaterialButton btnSetStart, btnSetEnd, btnNudgeBack, btnNudgeFwd, btnZoomFit;
    private View saveOverlay;
    private ProgressBar saveProgress;

    // Audio lane
    private AudioLaneView audioLane;
    private View audioSection, audioScroll, audioControls, trimGroup;
    private MaterialButton btnAudioToggle;
    private TextView tvAudioName, tvVolume, tvTrim;
    private MaterialButton btnAddAudio, btnAudioMute, btnAudioRemove;
    private Slider sliderVolume;
    private RangeSlider sliderTrim;
    private EditProject lastLaneProject;
    private int lastLaneSelected = Integer.MIN_VALUE;
    private int lastLaneReady = -1;
    private boolean lastLaneSaving;

    private final ActivityResultLauncher<String[]> pickAudio = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), this::onAudioPicked);

    private MediaPlayer player;
    private boolean prepared;
    private androidx.appcompat.app.AlertDialog discardDialog;
    /** The AI Edit row under the top bar: starts the AI edit of this recording (the saved original, not the draft). */
    private AiEditPanel aiPanel;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!vm.isPlaying() || !prepared) return;
            tick();
            handler.postDelayed(this, TICK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String path = getIntent().getStringExtra(EXTRA_MASTER_PATH);
        if (path == null || !new File(path).isFile()) {
            Toast.makeText(this, R.string.editor_missing_file, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        setContentView(R.layout.activity_video_editor);

        video = findViewById(R.id.editor_video);
        View aiRoot = findViewById(R.id.ai_edit_panel);
        aiRoot.findViewById(R.id.btn_record_again).setVisibility(View.GONE); // the preview's action, not the editor's
        aiPanel = new AiEditPanel(this, aiRoot, new AiEditPanel.Host() {
            @Override
            public File sourceVideo() {
                return new File(path);
            }

            @Override
            public void onVersionsChanged(File show) {
                // The results join the recording's group; the preview shows them when it is opened again
            }
        });
        subtitlesView = findViewById(R.id.editor_subtitles);
        if (subtitlesView != null) {
            subtitlesView.setStyle(SubtitleStyle.fromSettings(this));
            File masterFile = new File(path);
            subtitlesView.setTrack(SubtitleEmbedStore.isEmbedded(this, masterFile)
                    ? null : SubtitleCache.loadForVideo(getCacheDir(), masterFile));
        }
        timeline = findViewById(R.id.timeline);
        tvStatus = findViewById(R.id.tv_status);
        tvTime = findViewById(R.id.tv_time);
        tvSavePercent = findViewById(R.id.tv_save_percent);
        btnUndo = findViewById(R.id.btn_undo);
        btnRedo = findViewById(R.id.btn_redo);
        btnPlay = findViewById(R.id.btn_play);
        btnRemove = findViewById(R.id.btn_remove);
        btnSetStart = findViewById(R.id.btn_set_start);
        btnSetEnd = findViewById(R.id.btn_set_end);
        btnNudgeBack = findViewById(R.id.btn_nudge_back);
        btnNudgeFwd = findViewById(R.id.btn_nudge_fwd);
        btnZoomFit = findViewById(R.id.btn_zoom_fit);
        tvHint = findViewById(R.id.tv_hint);
        btnSave = findViewById(R.id.btn_save);
        btnPublish = findViewById(R.id.btn_publish);
        saveOverlay = findViewById(R.id.save_overlay);
        saveProgress = findViewById(R.id.save_progress);
        audioLane = findViewById(R.id.audio_lane);
        audioSection = findViewById(R.id.audio_section);
        btnAudioToggle = findViewById(R.id.btn_audio_toggle);
        audioScroll = findViewById(R.id.audio_scroll);
        audioControls = findViewById(R.id.audio_controls);
        trimGroup = findViewById(R.id.trim_group);
        tvAudioName = findViewById(R.id.tv_audio_name);
        tvVolume = findViewById(R.id.tv_volume);
        tvTrim = findViewById(R.id.tv_trim);
        btnAddAudio = findViewById(R.id.btn_add_audio);
        btnAudioMute = findViewById(R.id.btn_audio_mute);
        btnAudioRemove = findViewById(R.id.btn_audio_remove);
        sliderVolume = findViewById(R.id.slider_volume);
        sliderTrim = findViewById(R.id.slider_trim);

        vm = new ViewModelProvider(this).get(VideoEditorViewModel.class);
        vm.load(this, path);
        vm.setListener(this);
        if (vm.takeDraftRestored()) {
            Toast.makeText(this, R.string.editor_draft_restored, Toast.LENGTH_LONG).show();
        }

        timeline.setListener(this);
        audioLane.setListener(this);
        if (vm.getAudioExpanded() == null) {
            // The lane starts closed so the video keeps its room; it opens when audio is added
            vm.setAudioExpanded(false);
        }
        View.OnClickListener toggleAudio = v -> {
            vm.setAudioExpanded(!Boolean.TRUE.equals(vm.getAudioExpanded()));
            lastLaneProject = null;
            refresh();
        };
        findViewById(R.id.tv_audio_title).setOnClickListener(toggleAudio);
        btnAudioToggle.setOnClickListener(toggleAudio);
        btnAddAudio.setOnClickListener(v -> pickAudio.launch(new String[]{"audio/*"}));
        btnAudioMute.setOnClickListener(v -> toggleOriginalMute());
        btnAudioRemove.setOnClickListener(v -> removeSelectedAudio());
        sliderVolume.addOnChangeListener((s, value, fromUser) -> {
            if (fromUser) tvVolume.setText(getString(R.string.editor_audio_percent, Math.round(value * 100)));
        });
        sliderVolume.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(Slider slider) {
            }

            @Override
            public void onStopTrackingTouch(Slider slider) {
                commitVolume(slider.getValue());
            }
        });
        sliderTrim.addOnChangeListener((s, value, fromUser) -> {
            if (fromUser) showTrimText(s.getValues());
        });
        sliderTrim.addOnSliderTouchListener(new RangeSlider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(RangeSlider slider) {
            }

            @Override
            public void onStopTrackingTouch(RangeSlider slider) {
                commitTrim(slider.getValues());
            }
        });
        findViewById(R.id.btn_back).setOnClickListener(v -> onBackRequested());
        btnUndo.setOnClickListener(v -> undoRedo(true));
        btnRedo.setOnClickListener(v -> undoRedo(false));
        btnPlay.setOnClickListener(v -> togglePlay());
        btnRemove.setOnClickListener(v -> removeSection());
        btnZoomFit.setOnClickListener(v -> timeline.resetZoom());
        btnSetStart.setOnClickListener(v -> setMarker(true));
        btnSetEnd.setOnClickListener(v -> setMarker(false));
        bindNudge(btnNudgeBack, -1);
        bindNudge(btnNudgeFwd, 1);
        btnSave.setOnClickListener(v -> save());
        btnPublish.setOnClickListener(v -> publish());
        findViewById(R.id.btn_cancel_save).setOnClickListener(v -> vm.cancelSave());
        video.setOnClickListener(v -> togglePlay());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                onBackRequested();
            }
        });

        video.setOnPreparedListener(mp -> {
            player = mp;
            prepared = true;
            mp.setLooping(false);
            seekPrecise(vm.getPlayheadMs());
            if (subtitlesView != null) {
                subtitlesView.setPlayheadMs(vm.getPlayheadMs());
            }
            if (vm.isPlaying()) startPlayback();
            refresh();
        });
        video.setOnCompletionListener(mp -> {
            // Reached the real end of the file
            vm.setPlaying(false);
            handler.removeCallbacks(ticker);
            if (subtitlesView != null) {
                subtitlesView.setPlayheadMs(0);
            }
            refresh();
        });
        video.setOnErrorListener((mp, what, extra) -> {
            prepared = false;
            vm.setPlaying(false);
            tvStatus.setText(R.string.editor_load_failed);
            tvStatus.setVisibility(View.VISIBLE);
            return true;
        });
        video.setVideoPath(path);

        refresh();
    }

    private final BroadcastReceiver subtitlesReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && PostRecordTranscriber.ACTION_SUBTITLES_READY.equals(intent.getAction())) {
                String path = intent.getStringExtra(PostRecordTranscriber.EXTRA_VIDEO_PATH);
                String currentPath = getIntent().getStringExtra(EXTRA_MASTER_PATH);
                if (path != null && path.equals(currentPath)) {
                    if (subtitlesView != null) {
                        File f = new File(path);
                        subtitlesView.setTrack(SubtitleEmbedStore.isEmbedded(VideoEditorActivity.this, f)
                                ? null : SubtitleCache.loadForVideo(getCacheDir(), f));
                        if (video != null && prepared) {
                            subtitlesView.setPlayheadMs(video.getCurrentPosition());
                        }
                    }
                }
            }
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        if (subtitlesView != null) {
            subtitlesView.setStyle(SubtitleStyle.fromSettings(this));
        }
        IntentFilter filter = new IntentFilter(PostRecordTranscriber.ACTION_SUBTITLES_READY);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(subtitlesReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(subtitlesReceiver, filter);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(subtitlesReceiver);
        } catch (Exception ignored) {}
        handler.removeCallbacks(ticker);
        stopNudgeRepeat();
        if (video != null && prepared) {
            try {
                vm.setPlayheadMs(video.getCurrentPosition());
                // A rotation resumes playing; leaving the screen does not
                if (!isChangingConfigurations()) vm.setPlaying(false);
                video.pause();
            } catch (IllegalStateException ignored) {
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // The system may kill a stopped app at any time: keep the unsaved edit where it can find it
        if (vm != null && !isFinishing()) vm.persistDraft();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(ticker);
        if (discardDialog != null) discardDialog.dismiss();
        if (vm != null) vm.setListener(null);
        if (aiPanel != null) aiPanel.release();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- UI sync

    @Override
    public void onChanged() {
        refresh();
    }

    /** Redraws every control from the view model. Cheap, so called after any change. */
    private void refresh() {
        if (vm == null) return;
        boolean ready = vm.isReady();
        boolean saving = vm.getSaveState() == VideoEditorViewModel.SaveState.RUNNING;

        if (vm.isLoadFailed()) {
            tvStatus.setText(R.string.editor_load_failed);
            tvStatus.setVisibility(View.VISIBLE);
        } else {
            tvStatus.setVisibility(ready && prepared ? View.GONE : View.VISIBLE);
        }

        updateSaveOverlay(saving);
        finishSaveIfDone();

        if (!ready) {
            setEnabled(btnUndo, false);
            setEnabled(btnRedo, false);
            setEnabled(btnPlay, false);
            setEnabled(btnRemove, false);
            setEnabled(btnSetStart, false);
            setEnabled(btnSetEnd, false);
            setEnabled(btnNudgeBack, false);
            setEnabled(btnNudgeFwd, false);
            setEnabled(btnSave, false);
            setEnabled(btnPublish, false);
            setEnabled(btnAddAudio, false);
            setEnabled(btnAudioToggle, false);
            return;
        }

        long dur = vm.getDurationMs();
        EditProject project = vm.getProject();
        List<Range> ranges = project.effectiveKeep(dur);
        long playhead = EditTimelineMath.snapToKept(ranges, vm.getPlayheadMs());
        if (playhead != vm.getPlayheadMs()) vm.setPlayheadMs(playhead);

        // The timeline shows the edited result, so everything below is in edited milliseconds
        long outDur = project.outputDurationMs(dur);
        long outPos = project.sourceToOutput(playhead, dur);
        if (outPos < 0) outPos = outDur;
        if (!vm.getSelection().fitsIn(outDur)) vm.setSelection(SectionSelection.EMPTY);
        SectionSelection selection = vm.getSelection();

        timeline.setView(vm.getTimelineZoom(), vm.getTimelineStartMs());
        timeline.setDuration(outDur);
        timeline.setThumbnails(editedThumbs(vm.getThumbs(), project, dur, outDur));
        timeline.setSeams(EditTimelineMath.seams(project, dur));
        timeline.setSelection(selection.getStartMs(), selection.getEndMs());
        timeline.setPlayhead(outPos);

        EditHistory h = vm.getHistory();
        setEnabled(btnUndo, h.canUndo() && !saving);
        setEnabled(btnRedo, h.canRedo() && !saving);
        setEnabled(btnPlay, prepared && !saving);
        setEnabled(btnRemove, EditTimelineMath.canRemove(project, selection, dur) && !saving);
        setEnabled(btnSetStart, !saving);
        setEnabled(btnSetEnd, selection.hasStart() && !saving);
        setEnabled(btnNudgeBack, !saving);
        setEnabled(btnNudgeFwd, !saving);
        setEnabled(btnAudioToggle, !saving);
        btnZoomFit.setVisibility(vm.getTimelineZoom() > 1.01f ? View.VISIBLE : View.GONE);
        bindSelectionTexts(project, selection, dur);
        setEnabled(btnSave, !saving);
        setEnabled(btnPublish, !saving);

        refreshAudio(project, dur, saving);

        boolean playing = vm.isPlaying();
        btnPlay.setText(playing ? R.string.editor_pause : R.string.editor_play);
        btnPlay.setIconResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);

        tvTime.setText(getString(R.string.editor_time_fmt,
                EditTimelineMath.formatPrecise(outPos), EditTimelineMath.formatPrecise(outDur)));
    }

    /** The one line under the controls: what to do next, and what is selected. */
    private void bindSelectionTexts(EditProject project, SectionSelection selection, long dur) {
        if (!selection.hasStart()) {
            tvHint.setText(R.string.editor_hint_start);
        } else if (!selection.hasRange()) {
            tvHint.setText(getString(R.string.editor_hint_end,
                    EditTimelineMath.formatPrecise(selection.getStartMs())));
        } else if (EditTimelineMath.coversAll(project, selection, dur)) {
            tvHint.setText(R.string.editor_hint_all);
        } else {
            tvHint.setText(getString(R.string.editor_sel_range,
                    EditTimelineMath.formatPrecise(selection.getStartMs()),
                    EditTimelineMath.formatPrecise(selection.getEndMs()),
                    EditTimelineMath.formatSeconds(selection.lengthMs())));
        }
    }

    /**
     * Thumbnails for the edited timeline: slot i shows the master thumbnail nearest to the footage
     * that sits there, so only kept footage appears. Null entries stay null while they load.
     */
    private static Bitmap[] editedThumbs(Bitmap[] master, EditProject project, long dur, long outDur) {
        if (master.length == 0 || outDur <= 0 || dur <= 0 || !project.hasCuts(dur)) return master;
        Bitmap[] out = new Bitmap[master.length];
        for (int i = 0; i < out.length; i++) {
            long src = project.outputToSource((long) ((i + 0.5) * outDur / out.length), dur);
            int idx = (int) Math.max(0, Math.min(master.length - 1, src * master.length / dur));
            out[i] = master[idx];
        }
        return out;
    }

    // ---------------------------------------------------------------- audio lane

    private void onAudioPicked(Uri uri) {
        if (uri == null || vm == null) return;
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException | UnsupportedOperationException ignored) {
            // Not every provider offers a persistable grant; the grant for this session still works
        }
        vm.setAudioExpanded(true);
        lastLaneProject = null;
        vm.addAudio(this, uri);
    }

    /** Rebuilds the lane and the selected row's controls; skipped when nothing relevant changed. */
    private void refreshAudio(EditProject project, long dur, boolean saving) {
        if (vm.takeAudioError()) {
            Toast.makeText(this, R.string.editor_audio_pick_failed, Toast.LENGTH_LONG).show();
        }
        boolean showOriginal = vm.hasMasterAudio();
        List<EditProject.AddedAudio> added = project.getAdded();

        int selected = vm.getSelectedTrack();
        if (selected >= added.size() || (selected == VideoEditorViewModel.ORIGINAL_TRACK && !showOriginal)) {
            selected = VideoEditorViewModel.NO_TRACK;
            vm.setSelectedTrack(selected);
        }

        int ready = vm.getPeaks(project.getMasterPath()) != null ? 1 : 0;
        for (EditProject.AddedAudio a : added) {
            if (vm.getPeaks(a.uri) != null) ready++;
            if (vm.getAudioDurationMs(a.uri) > 0) ready += 100;
        }
        setEnabled(btnAddAudio, !saving && !vm.isAudioBusy());
        setEnabled(btnAudioMute, !saving);
        setEnabled(btnAudioRemove, !saving);
        sliderVolume.setEnabled(!saving);
        sliderTrim.setEnabled(!saving);
        if (lastLaneProject == project && lastLaneSelected == selected && lastLaneReady == ready
                && lastLaneSaving == saving) {
            return;
        }
        lastLaneProject = project;
        lastLaneSelected = selected;
        lastLaneReady = ready;
        lastLaneSaving = saving;

        boolean expanded = Boolean.TRUE.equals(vm.getAudioExpanded());
        boolean hasRows = showOriginal || !added.isEmpty();
        ((TextView) findViewById(R.id.tv_audio_title)).setText(!hasRows ? getString(R.string.editor_audio)
                : selected == VideoEditorViewModel.NO_TRACK ? getString(R.string.editor_audio_tap_hint)
                : getString(R.string.editor_audio));
        audioSection.setVisibility(expanded ? View.VISIBLE : View.GONE);
        audioScroll.setVisibility(hasRows && expanded ? View.VISIBLE : View.GONE);
        long outDur = project.outputDurationMs(dur);
        int cols = AudioPeaks.BUCKETS;

        List<AudioLaneView.Row> rows = new ArrayList<>();
        EditProject.AudioSettings o = project.getOriginal();
        if (showOriginal) {
            String label = getString(R.string.editor_audio_original);
            if (o.removed) label += " - " + getString(R.string.editor_audio_removed);
            else if (o.muted) label += " - " + getString(R.string.editor_audio_muted);
            rows.add(new AudioLaneView.Row(VideoEditorViewModel.ORIGINAL_TRACK, label,
                    vm.getPeaks(project.getMasterPath()) == null ? null
                            : WaveformMath.remapToOutput(vm.getPeaks(project.getMasterPath()),
                            project, dur, cols),
                    0, outDur, o.muted || o.removed || o.volume == 0f));
        }
        for (int i = 0; i < added.size(); i++) {
            EditProject.AddedAudio a = added.get(i);
            long srcDur = vm.getAudioDurationMs(a.uri);
            long used = srcDur > 0 ? a.usedLengthMs(srcDur) : outDur;
            float[] p = vm.getPeaks(a.uri);
            rows.add(new AudioLaneView.Row(i, trackName(a), p == null || srcDur <= 0 ? null
                    : WaveformMath.slice(p, srcDur, a.sourceInMs, used, cols),
                    a.startOnTimelineMs, used, a.volume == 0f));
        }
        audioLane.setRows(rows, outDur, selected);
        bindAudioControls(project, selected, saving);
    }

    private String trackName(EditProject.AddedAudio a) {
        String n = vm.getAudioName(a.uri);
        return n == null || n.isEmpty() ? getString(R.string.editor_audio_track_unnamed) : n;
    }

    private void bindAudioControls(EditProject project, int selected, boolean saving) {
        if (selected == VideoEditorViewModel.NO_TRACK) {
            audioControls.setVisibility(View.GONE);
            return;
        }
        audioControls.setVisibility(View.VISIBLE);
        float volume;
        if (selected == VideoEditorViewModel.ORIGINAL_TRACK) {
            EditProject.AudioSettings o = project.getOriginal();
            volume = o.volume;
            tvAudioName.setText(R.string.editor_audio_original);
            btnAudioMute.setVisibility(View.VISIBLE);
            btnAudioMute.setText(o.muted ? R.string.editor_audio_unmute : R.string.editor_audio_mute);
            btnAudioMute.setEnabled(!o.removed && !saving);
            btnAudioRemove.setText(o.removed ? R.string.editor_audio_restore : R.string.editor_audio_remove);
            trimGroup.setVisibility(View.GONE);
        } else {
            EditProject.AddedAudio a = project.getAdded().get(selected);
            volume = a.volume;
            tvAudioName.setText(getString(R.string.editor_audio_track_fmt, trackName(a)));
            btnAudioMute.setVisibility(View.GONE);
            btnAudioRemove.setText(R.string.editor_audio_remove);
            long srcDur = vm.getAudioDurationMs(a.uri);
            if (srcDur >= 2 * EditProject.MIN_TRACK_MS) {
                trimGroup.setVisibility(View.VISIBLE);
                if (!sliderTrim.isPressed()) {
                    long out = a.sourceOutMs <= a.sourceInMs ? srcDur : Math.min(a.sourceOutMs, srcDur);
                    float to = srcDur / 1000f;
                    sliderTrim.setValueFrom(0f);
                    sliderTrim.setValueTo(to);
                    sliderTrim.setValues(Math.min(a.sourceInMs / 1000f, to), Math.min(out / 1000f, to));
                    showTrimText(sliderTrim.getValues());
                }
            } else {
                trimGroup.setVisibility(View.GONE);
            }
        }
        if (!sliderVolume.isPressed()) {
            sliderVolume.setValue(Math.max(0f, Math.min(2f, volume)));
            tvVolume.setText(getString(R.string.editor_audio_percent, Math.round(volume * 100)));
        }
    }

    private void showTrimText(List<Float> v) {
        if (v.size() < 2) return;
        List<Float> sorted = new ArrayList<>(v);
        Collections.sort(sorted);
        tvTrim.setText(getString(R.string.editor_audio_trim_fmt,
                EditTimelineMath.formatPrecise(Math.round(sorted.get(0) * 1000)),
                EditTimelineMath.formatPrecise(Math.round(sorted.get(1) * 1000))));
    }

    /** One undo step for an audio edit. */
    private void pushAudioEdit(EditProject next) {
        if (!vm.isReady()) return;
        if (vm.isPlaying()) pausePlayback();
        vm.getHistory().push(next);
        refresh();
    }

    @Override
    public void onTrackSelected(int track) {
        vm.setSelectedTrack(track);
        refresh();
    }

    @Override
    public void onTrackMoved(int track, long startMs) {
        if (!vm.isReady()) return;
        EditProject p = vm.getProject();
        pushAudioEdit(p.withTrackMoved(track, startMs, p.outputDurationMs(vm.getDurationMs())));
    }

    private void commitVolume(float value) {
        if (!vm.isReady()) return;
        float v = Math.round(value * 100) / 100f;
        int sel = vm.getSelectedTrack();
        EditProject p = vm.getProject();
        if (sel == VideoEditorViewModel.ORIGINAL_TRACK) {
            EditProject.AudioSettings o = p.getOriginal();
            pushAudioEdit(p.withOriginal(new EditProject.AudioSettings(v, o.muted, o.removed)));
        } else if (sel >= 0) {
            pushAudioEdit(p.withTrackVolume(sel, v));
        }
    }

    private void commitTrim(List<Float> values) {
        int sel = vm.getSelectedTrack();
        if (!vm.isReady() || sel < 0 || values.size() < 2) return;
        EditProject p = vm.getProject();
        if (sel >= p.getAdded().size()) return;
        List<Float> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        long srcDur = vm.getAudioDurationMs(p.getAdded().get(sel).uri);
        pushAudioEdit(p.withTrackTrim(sel, Math.round(sorted.get(0) * 1000L),
                Math.round(sorted.get(1) * 1000L), srcDur));
    }

    private void toggleOriginalMute() {
        if (!vm.isReady()) return;
        EditProject p = vm.getProject();
        EditProject.AudioSettings o = p.getOriginal();
        pushAudioEdit(p.withOriginal(new EditProject.AudioSettings(o.volume, !o.muted, o.removed)));
    }

    private void removeSelectedAudio() {
        if (!vm.isReady()) return;
        int sel = vm.getSelectedTrack();
        EditProject p = vm.getProject();
        if (sel == VideoEditorViewModel.ORIGINAL_TRACK) {
            EditProject.AudioSettings o = p.getOriginal();
            pushAudioEdit(p.withOriginal(new EditProject.AudioSettings(o.volume, o.muted, !o.removed)));
        } else if (sel >= 0) {
            vm.setSelectedTrack(VideoEditorViewModel.NO_TRACK);
            pushAudioEdit(p.withoutTrack(sel));
        }
    }

    private static void setEnabled(View v, boolean enabled) {
        v.setEnabled(enabled);
        v.setAlpha(enabled ? 1f : 0.35f);
    }

    // ---------------------------------------------------------------- playback

    private void togglePlay() {
        if (!prepared || !vm.isReady() || vm.getSaveState() == VideoEditorViewModel.SaveState.RUNNING) return;
        if (vm.isPlaying()) {
            pausePlayback();
        } else {
            List<Range> ranges = vm.getProject().effectiveKeep(vm.getDurationMs());
            // Parked at the very end: play again from the first kept moment
            if (!ranges.isEmpty() && vm.getPlayheadMs() >= ranges.get(ranges.size() - 1).endMs - SKIP_MARGIN_MS) {
                vm.setPlayheadMs(ranges.get(0).startMs);
            }
            vm.setPlayheadMs(EditTimelineMath.snapToKept(ranges, vm.getPlayheadMs()));
            seekPrecise(vm.getPlayheadMs());
            startPlayback();
        }
        refresh();
    }

    private void startPlayback() {
        vm.setPlaying(true);
        try {
            video.start();
        } catch (IllegalStateException ignored) {
        }
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    private void pausePlayback() {
        vm.setPlaying(false);
        handler.removeCallbacks(ticker);
        try {
            if (video.isPlaying()) video.pause();
        } catch (IllegalStateException ignored) {
        }
    }

    /** One playback step: follow the player, jump over deleted parts, stop at the end. */
    private void tick() {
        List<Range> ranges = vm.getProject().renderRanges(vm.getDurationMs());
        if (ranges.isEmpty()) return;
        long pos = video.getCurrentPosition();
        int idx = EditTimelineMath.indexAt(ranges, pos);
        if (idx < 0 || pos >= ranges.get(idx).endMs - SKIP_MARGIN_MS) {
            long from = idx >= 0 ? ranges.get(idx).startMs : pos;
            long next = EditTimelineMath.nextStart(ranges, from);
            if (next < 0) {
                // Nothing left to play: park on the last frame of the result
                pausePlayback();
                vm.setPlayheadMs(Math.max(0, ranges.get(ranges.size() - 1).endMs - 1));
                seekPrecise(vm.getPlayheadMs());
                if (subtitlesView != null) {
                    subtitlesView.setPlayheadMs(vm.getPlayheadMs());
                }
                refresh();
                return;
            }
            pos = next;
            seekPrecise(next);
        }
        vm.setPlayheadMs(pos);
        if (subtitlesView != null) {
            subtitlesView.setPlayheadMs(video.getCurrentPosition());
        }
        refresh();
    }

    private void seekPrecise(long ms) {
        seek(ms, MediaPlayer.SEEK_CLOSEST);
    }

    private void seek(long ms, int mode) {
        if (!prepared) return;
        try {
            if (player != null) player.seekTo((int) ms, mode);
            else video.seekTo((int) ms);
        } catch (IllegalStateException ignored) {
        }
        if (subtitlesView != null) {
            subtitlesView.setPlayheadMs(ms);
        }
    }

    // ---------------------------------------------------------------- timeline gestures

    @Override
    public void onGestureStart() {
        if (vm.isPlaying()) pausePlayback();
    }

    /** Dragging the timeline: {@code ms} is on the edited timeline. */
    @Override
    public void onScrub(long ms) {
        if (!vm.isReady()) return;
        long snapped = movePlayheadToOutput(ms);
        seek(snapped, MediaPlayer.SEEK_CLOSEST_SYNC);
        if (subtitlesView != null) {
            subtitlesView.setPlayheadMs(snapped);
        }
        refresh();
    }

    /** A marker is being dragged: it moves within its limits and the preview shows that frame. */
    @Override
    public void onMarkerDrag(boolean isStart, long ms) {
        if (!vm.isReady() || vm.getSaveState() == VideoEditorViewModel.SaveState.RUNNING) return;
        long outDur = vm.getProject().outputDurationMs(vm.getDurationMs());
        SectionSelection moved = vm.getSelection().moveMarker(isStart, ms, outDur);
        vm.setSelection(moved);
        long at = isStart ? moved.getStartMs() : moved.getEndMs();
        if (at < 0) return;
        seek(movePlayheadToOutput(at), MediaPlayer.SEEK_CLOSEST_SYNC);
        refresh();
    }

    /** The drag ended: show the exact frame at the marker. */
    @Override
    public void onMarkerDragEnd() {
        if (!vm.isReady()) return;
        seekPrecise(vm.getPlayheadMs());
        refresh();
    }

    /** Zoom or scroll changed: remembered so rotation keeps the same view. */
    @Override
    public void onViewChanged(float zoom, long startMs) {
        vm.setTimelineView(zoom, startMs);
        if (btnZoomFit != null) btnZoomFit.setVisibility(zoom > 1.01f ? View.VISIBLE : View.GONE);
    }

    /** A tap on the timeline only moves the playhead (markers are set with the buttons). */
    @Override
    public void onTap(long ms) {
        if (!vm.isReady() || vm.getSaveState() == VideoEditorViewModel.SaveState.RUNNING) return;
        seekPrecise(movePlayheadToOutput(ms));
        refresh();
    }

    /** Playhead position on the edited timeline (the end of the result when parked past it). */
    private long playheadOutputMs() {
        long dur = vm.getDurationMs();
        EditProject p = vm.getProject();
        long out = p.sourceToOutput(vm.getPlayheadMs(), dur);
        return out < 0 ? p.outputDurationMs(dur) : out;
    }

    /** Set start / Set end: puts that marker at the playhead, or says why it can't. */
    private void setMarker(boolean isStart) {
        if (!vm.isReady() || vm.getSaveState() == VideoEditorViewModel.SaveState.RUNNING) return;
        if (vm.isPlaying()) pausePlayback();
        long outDur = vm.getProject().outputDurationMs(vm.getDurationMs());
        long at = playheadOutputMs();
        SectionSelection before = vm.getSelection();
        SectionSelection after = isStart ? before.setStart(at, outDur) : before.setEnd(at, outDur);
        if (after.equals(before)) {
            Toast.makeText(this, before.hasStart() ? R.string.editor_end_after_start
                    : R.string.editor_set_start_first, Toast.LENGTH_SHORT).show();
            return;
        }
        vm.setSelection(after);
        btnSetStart.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
        refresh();
    }

    /** Moves the playhead by {@code deltaMs} on the edited timeline and shows that frame. */
    private void nudge(long deltaMs) {
        if (!vm.isReady() || vm.getSaveState() == VideoEditorViewModel.SaveState.RUNNING) return;
        if (vm.isPlaying()) pausePlayback();
        long outDur = vm.getProject().outputDurationMs(vm.getDurationMs());
        long target = Math.max(0, Math.min(Math.max(0, outDur - 1), playheadOutputMs() + deltaMs));
        seekPrecise(movePlayheadToOutput(target));
        refresh();
    }

    private Runnable nudgeRepeat;

    /** A nudge button: a tap steps once, holding repeats (faster after a moment). */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void bindNudge(View button, int direction) {
        button.setOnClickListener(v -> nudge(direction * NUDGE_MS));
        button.setOnLongClickListener(v -> {
            stopNudgeRepeat();
            nudgeRepeat = new Runnable() {
                int steps;

                @Override
                public void run() {
                    nudge(direction * (steps++ < NUDGE_FAST_AFTER ? NUDGE_MS : NUDGE_FAST_MS));
                    handler.postDelayed(this, NUDGE_REPEAT_MS);
                }
            };
            handler.post(nudgeRepeat);
            return true;
        });
        button.setOnTouchListener((v, e) -> {
            int a = e.getActionMasked();
            if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) {
                stopNudgeRepeat();
            }
            return false;
        });
    }

    private void stopNudgeRepeat() {
        if (nudgeRepeat != null) handler.removeCallbacks(nudgeRepeat);
        nudgeRepeat = null;
    }

    /** Parks the playhead on edited-timeline position {@code outMs}; returns the source position. */
    private long movePlayheadToOutput(long outMs) {
        long dur = vm.getDurationMs();
        EditProject p = vm.getProject();
        long source = EditTimelineMath.snapToKept(p.effectiveKeep(dur), p.outputToSource(outMs, dur));
        vm.setPlayheadMs(source);
        return source;
    }

    // ---------------------------------------------------------------- edits

    private void removeSection() {
        if (!vm.isReady()) return;
        long dur = vm.getDurationMs();
        SectionSelection selection = vm.getSelection();
        EditProject next = EditTimelineMath.removeSection(vm.getProject(), selection, dur);
        if (next == null) return;
        if (vm.isPlaying()) pausePlayback();
        vm.getHistory().push(next);
        vm.setSelection(SectionSelection.EMPTY);
        // Park on the join, where the removed part used to be
        long outPos = Math.min(selection.getStartMs(), next.outputDurationMs(dur));
        vm.setPlayheadMs(EditTimelineMath.snapToKept(next.effectiveKeep(dur),
                next.outputToSource(outPos, dur)));
        seekPrecise(vm.getPlayheadMs());
        refresh();
    }

    private void undoRedo(boolean undo) {
        if (!vm.isReady()) return;
        if (vm.isPlaying()) pausePlayback();
        EditProject p = undo ? vm.getHistory().undo() : vm.getHistory().redo();
        if (p == null) return;
        vm.setSelection(SectionSelection.EMPTY);
        long dur = vm.getDurationMs();
        vm.setPlayheadMs(EditTimelineMath.snapToKept(p.effectiveKeep(dur), vm.getPlayheadMs()));
        seekPrecise(vm.getPlayheadMs());
        refresh();
    }

    // ---------------------------------------------------------------- save

    private void save() {
        if (!vm.isReady()) return;
        if (!vm.getHistory().isDirty()) {
            Toast.makeText(this, R.string.editor_nothing_to_save, Toast.LENGTH_SHORT).show();
            return;
        }
        if (vm.isPlaying()) pausePlayback();
        vm.startSave(this);
    }

    /** Save, then the publish flow on the result. Nothing edited: publishes the master as it is. */
    private void publish() {
        if (!vm.isReady()) return;
        if (!vm.getHistory().isDirty()) {
            PublishLauncher.publish(this, new File(vm.getProject().getMasterPath()));
            return;
        }
        if (vm.isPlaying()) pausePlayback();
        vm.startSave(this, true);
    }

    private void updateSaveOverlay(boolean saving) {
        saveOverlay.setVisibility(saving ? View.VISIBLE : View.GONE);
        if (saving) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            saveProgress.setProgress(vm.getPercent());
            tvSavePercent.setText(getString(R.string.editor_saving_percent, vm.getPercent()));
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    /** Shows the outcome of a save that finished while this screen was (re)attached. */
    private void finishSaveIfDone() {
        VideoEditorViewModel.SaveState state = vm.getSaveState();
        if (state == VideoEditorViewModel.SaveState.DONE) {
            File out = vm.getOutput();
            boolean publishAfter = vm.isPublishAfterSave();
            vm.clearSaveResult();
            if (out != null) {
                Toast.makeText(this, getString(R.string.editor_saved, out.getName()),
                        Toast.LENGTH_LONG).show();
                // The original master travels back too, so the host can offer "Delete original?"
                setResult(RESULT_OK, new Intent()
                        .putExtra(EXTRA_OUTPUT_PATH, out.getAbsolutePath())
                        .putExtra(EXTRA_MASTER_PATH, getIntent().getStringExtra(EXTRA_MASTER_PATH)));
                if (publishAfter) PublishLauncher.publish(this, out);
            }
            finish();
        } else if (state == VideoEditorViewModel.SaveState.FAILED) {
            VideoEditorViewModel.SaveError error = vm.getSaveError();
            vm.clearSaveResult();
            Toast.makeText(this, error == VideoEditorViewModel.SaveError.NO_SPACE
                    ? R.string.editor_save_no_space
                    : error == VideoEditorViewModel.SaveError.TOO_LONG
                    ? R.string.editor_save_too_long : R.string.editor_save_failed,
                    Toast.LENGTH_LONG).show();
        }
    }

    // ---------------------------------------------------------------- leaving

    private void onBackRequested() {
        if (vm != null && vm.getSaveState() == VideoEditorViewModel.SaveState.RUNNING) {
            return; // Cancel on the overlay is the way out while saving
        }
        if (vm != null && vm.isReady() && vm.getHistory().isDirty()) {
            discardDialog = new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.editor_discard_title)
                    .setMessage(R.string.editor_discard_message)
                    .setPositiveButton(R.string.editor_discard, (d, w) -> {
                        vm.clearDraft();
                        finish();
                    })
                    .setNegativeButton(R.string.editor_keep_editing, null)
                    .show();
        } else {
            finish();
        }
    }
}
