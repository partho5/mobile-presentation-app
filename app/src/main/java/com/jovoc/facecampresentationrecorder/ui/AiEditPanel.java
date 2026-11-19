package com.jovoc.facecampresentationrecorder.ui;

import android.app.Activity;
import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.activitylog.ActivityLog;
import com.jovoc.facecampresentationrecorder.aiedit.flow.AiEditApi;
import com.jovoc.facecampresentationrecorder.aiedit.flow.AiEditException;
import com.jovoc.facecampresentationrecorder.aiedit.flow.AiEditJob;
import com.jovoc.facecampresentationrecorder.aiedit.flow.AiEditListener;
import com.jovoc.facecampresentationrecorder.aiedit.flow.AiEditNames;
import com.jovoc.facecampresentationrecorder.aiedit.flow.AiEditService;
import com.jovoc.facecampresentationrecorder.aiedit.flow.AiEditSettings;
import com.jovoc.facecampresentationrecorder.aiedit.flow.AiEditStore;
import com.jovoc.facecampresentationrecorder.aiedit.flow.AiQuoteCache;
import com.jovoc.facecampresentationrecorder.auth.SessionManager;
import com.jovoc.facecampresentationrecorder.entitlements.Entitlements;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * The AI Edit row of the preview (spec §4): one primary button "AI Edit · N credits" that starts the edit at the
 * default level with no dialog, a chevron to the 3-option level sheet for this video only, and a status line that
 * shows progress, the result, or one line with one action ("Try again", "Top up"). The job itself runs in
 * {@link AiEditService}; this only starts it, listens to it while the preview is open, and can be rebuilt from the
 * job store when the preview is opened again. Main thread only, except where noted.
 */
final class AiEditPanel implements AiEditListener {

    /** What the preview gives the panel. */
    interface Host {
        /** The video an edit is made from (the group's master, else the centred version), or null. */
        File sourceVideo();

        /** An AI version exists or changed: reload the carousel and centre {@code show} when it is not null. */
        void onVersionsChanged(File show);
    }

    private static final String[] LEVELS = {
            AiEditSettings.LEVEL_BASIC, AiEditSettings.LEVEL_STANDARD, AiEditSettings.LEVEL_HEAVY};

    /** Video lengths already read, by file identity (main and executor threads). */
    private static final Map<String, Long> SECONDS = new HashMap<>();

    private final Context context;
    private final Context appContext;
    private final Host host;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final View pill;
    private final TextView tvLabel;
    private final View btnPrimary;
    private final View btnLevels;
    private final View divider;
    private final ProgressBar progress;
    private final View statusRow;
    private final TextView tvStatus;
    private final TextView btnAction;

    private boolean released;
    /** True from the tap until the service has answered whether it started, queued or refused the job. */
    private boolean starting;
    /** The level being worked on, or null when no job of this recording runs. */
    private String runningLevel;
    private String runningText;
    private int runningPercent = -1;
    /** Set after the first output of a run was brought to the centre, so a later output does not pull the view. */
    private boolean shownFirstOutput;
    /** The price of each level for this video, or null while unknown (the button then shows no number). */
    private AiEditApi.Quote quote;
    private String quotePath;

    AiEditPanel(Context context, View root, Host host) {
        this.context = context;
        this.appContext = context.getApplicationContext();
        this.host = host;
        pill = root.findViewById(R.id.ai_edit_pill);
        tvLabel = root.findViewById(R.id.tv_ai_edit_label);
        btnPrimary = root.findViewById(R.id.btn_ai_edit);
        btnLevels = root.findViewById(R.id.btn_ai_edit_levels);
        divider = root.findViewById(R.id.ai_edit_divider);
        progress = root.findViewById(R.id.ai_edit_progress);
        statusRow = root.findViewById(R.id.ai_edit_status_row);
        tvStatus = root.findViewById(R.id.tv_ai_edit_status);
        btnAction = root.findViewById(R.id.btn_ai_edit_action);

        btnPrimary.setOnClickListener(v -> startLevel(AiEditSettings.level(context)));
        btnLevels.setOnClickListener(v -> showLevelSheet());
        AiEditService.setListener(this);
        onSourceChanged();
    }

    /** The preview closed: stop listening (the job goes on in its service, with its notification). */
    void release() {
        released = true;
        AiEditService.setListener(null);
        handler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
    }

    /** The source video of the preview may have changed: look up its price and where its job stands. */
    void onSourceChanged() {
        if (released) return;
        File src = source();
        pill.setVisibility(src == null ? View.GONE : View.VISIBLE);
        render();
        if (src == null) return;
        final String path = src.getAbsolutePath();
        run(() -> {
            AiEditApi.Quote q = loadQuote(src);
            State state = readState(path);
            handler.post(() -> {
                if (released || !path.equals(sourcePath())) return;
                quote = q;
                quotePath = path;
                // A job started or finished since the lookup began is already shown; do not overwrite it
                if (runningLevel == null && !starting) applyState(state);
                render();
            });
        });
    }

    // ---- taps ----

    /** Starts the edit of the source at {@code level}, or finds the job it already has. */
    private void startLevel(String level) {
        final File src = source();
        if (released || src == null || starting || runningLevel != null) return;
        ActivityLog.log("player_action", "item", "ai_edit", "level", level);
        starting = true;
        shownFirstOutput = false;
        setRunning(level, appContext.getString(R.string.ai_edit_notif_uploading), -1);
        run(() -> {
            AiEditService.StartResult result = AiEditService.start(appContext, src, level);
            handler.post(() -> onStarted(level, result));
        });
    }

    private void onStarted(String level, AiEditService.StartResult result) {
        starting = false;
        if (released) return;
        switch (result) {
            case STARTED:
            case RESUMED:
            case ALREADY_RUNNING:
                break; // the listener tells how it goes
            case ALREADY_DONE:
                clearRunning();
                showReady();
                host.onVersionsChanged(firstOutput(sourcePath(), level));
                break;
            case NO_SPEECH:
                clearRunning();
                showFailure(level, AiEditException.NO_SPEECH, false);
                break;
            case NOT_SIGNED_IN:
                clearRunning();
                Toast.makeText(context, R.string.ai_edit_sign_in, Toast.LENGTH_SHORT).show();
                break;
            default:
                clearRunning();
                Toast.makeText(context, R.string.ai_edit_start_failed, Toast.LENGTH_SHORT).show();
        }
        render();
    }

    /** The chevron: Basic, Standard or Heavy for this video only; the default in Settings stays. */
    private void showLevelSheet() {
        if (released || runningLevel != null || starting || source() == null) return;
        if (!(context instanceof Activity) || ((Activity) context).isFinishing()) return;
        ActivityLog.log("player_action", "item", "ai_edit_levels");
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_ai_edit_levels, null);
        final BottomSheetDialog sheet = new BottomSheetDialog(context);
        sheet.setContentView(view);
        sheet.setOnShowListener(d -> {
            View frame = sheet.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (frame == null) return;
            frame.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(frame);
            behavior.setSkipCollapsed(true);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        });
        bindLevelRow(sheet, view, R.id.row_ai_basic, R.id.tv_ai_basic_cost, AiEditSettings.LEVEL_BASIC);
        bindLevelRow(sheet, view, R.id.row_ai_standard, R.id.tv_ai_standard_cost, AiEditSettings.LEVEL_STANDARD);
        bindLevelRow(sheet, view, R.id.row_ai_heavy, R.id.tv_ai_heavy_cost, AiEditSettings.LEVEL_HEAVY);
        sheet.show();
    }

    private void bindLevelRow(BottomSheetDialog sheet, View view, int rowId, int costId, String level) {
        TextView cost = view.findViewById(costId);
        cost.setText(quote != null ? creditsText(quote.forLevel(level)) : "");
        view.findViewById(rowId).setOnClickListener(v -> {
            sheet.dismiss();
            startLevel(level);
        });
    }

    // ---- what the job reports (main thread, from AiEditService) ----

    @Override
    public void onProgress(String sourcePath, String level, Stage stage, String ratio, int percent) {
        if (released || !isMine(sourcePath)) return;
        String text;
        switch (stage) {
            case QUEUED:
                text = appContext.getString(R.string.ai_edit_notif_queued);
                break;
            case TRANSCRIBING:
                text = appContext.getString(R.string.ai_edit_notif_transcribing);
                break;
            case PLANNING:
                text = appContext.getString(R.string.ai_edit_notif_planning);
                break;
            case RENDERING:
                text = appContext.getString(R.string.ai_edit_notif_rendering, ratio);
                break;
            default:
                text = appContext.getString(R.string.ai_edit_notif_uploading);
        }
        setRunning(level, text, percent);
    }

    @Override
    public void onOutput(String sourcePath, String level, File output) {
        if (released || !isMine(sourcePath)) return;
        // The first result (9:16) comes to the centre next to the original; later ones only join the carousel
        host.onVersionsChanged(shownFirstOutput ? null : output);
        shownFirstOutput = true;
    }

    @Override
    public void onDone(String sourcePath, String level) {
        if (released || !isMine(sourcePath)) return;
        clearRunning();
        showReady();
        render();
        host.onVersionsChanged(null);
    }

    @Override
    public void onFailed(String sourcePath, String level, String code, boolean retryable) {
        if (released || !isMine(sourcePath)) return;
        clearRunning();
        if (!AiEditException.CANCELLED.equals(code)) showFailure(level, code, retryable);
        render();
    }

    // ---- states -> views ----

    private void setRunning(String level, String text, int percent) {
        runningLevel = level;
        runningText = text;
        runningPercent = percent;
        render();
    }

    private void clearRunning() {
        runningLevel = null;
        runningText = null;
        runningPercent = -1;
        // The running line is stale now; a ready or failed line, when there is one, is set right after
        statusRow.setVisibility(View.GONE);
    }

    /** The button label and the running status; the ready and failed lines are set by their own calls. */
    private void render() {
        boolean busy = runningLevel != null;
        String level = AiEditSettings.level(context);
        if (busy) {
            tvLabel.setText(R.string.ai_edit_working);
        } else if (quote != null && sourcePath() != null && sourcePath().equals(quotePath)) {
            tvLabel.setText(appContext.getString(R.string.ai_edit_button_cost, creditsText(quote.forLevel(level))));
        } else {
            tvLabel.setText(R.string.ai_edit_button);
        }
        btnPrimary.setEnabled(!busy);
        btnLevels.setEnabled(!busy);
        pill.setAlpha(busy ? 0.55f : 1f);
        btnLevels.setVisibility(busy ? View.GONE : View.VISIBLE);
        divider.setVisibility(busy ? View.GONE : View.VISIBLE);
        if (busy) {
            progress.setVisibility(View.VISIBLE);
            progress.setIndeterminate(runningPercent < 0);
            if (runningPercent >= 0) progress.setProgress(runningPercent);
            String text = runningText != null ? runningText : appContext.getString(R.string.ai_edit_working);
            if (runningPercent >= 0) {
                text = appContext.getString(R.string.ai_edit_progress_percent, text, runningPercent);
            }
            showStatus(text, null, null);
        } else {
            progress.setVisibility(View.GONE);
        }
    }

    private void showReady() {
        showStatus(appContext.getString(R.string.ai_edit_ready), null, null);
    }

    /** One line, and one action when there is one that helps. */
    private void showFailure(String level, String code, boolean retryable) {
        String message = appContext.getString(messageFor(code));
        if (AiEditException.INSUFFICIENT_CREDITS.equals(code)) {
            showStatus("✗ " + message, appContext.getString(R.string.ai_edit_action_top_up), this::topUp);
        } else if (AiEditException.AI_CAP_REACHED.equals(code)) {
            showStatus("✗ " + message, appContext.getString(R.string.ai_edit_action_upgrade), this::topUp);
        } else if (retryable) {
            showStatus("✗ " + message, appContext.getString(R.string.ai_edit_action_try_again),
                    () -> startLevel(level));
        } else {
            showStatus("✗ " + message, null, null);
        }
    }

    private void showStatus(String text, String actionLabel, Runnable action) {
        tvStatus.setText(text);
        statusRow.setVisibility(View.VISIBLE);
        if (actionLabel == null) {
            btnAction.setVisibility(View.GONE);
            btnAction.setOnClickListener(null);
        } else {
            btnAction.setText(actionLabel);
            btnAction.setVisibility(View.VISIBLE);
            btnAction.setOnClickListener(v -> action.run());
        }
    }

    /** The existing top-up path: the billing page, signed in; the plans sheet when the build has no web link. */
    private void topUp() {
        if (!(context instanceof Activity)) return;
        Activity activity = (Activity) context;
        ActivityLog.log("player_action", "item", "ai_edit_top_up");
        if (BillingLink.enabled()) {
            BillingLink.open(activity, Entitlements.plan(), () -> { }, () -> { });
        } else {
            PlansSheet.show(activity);
        }
    }

    /** The string of one of our failure codes: one short line, never the server's words. */
    static int messageFor(String code) {
        if (code == null) return R.string.ai_edit_err_generic;
        switch (code) {
            case AiEditException.INSUFFICIENT_CREDITS:
                return R.string.ai_edit_err_credits;
            case AiEditException.AI_CAP_REACHED:
                return R.string.ai_edit_err_cap;
            case AiEditException.VIDEO_TOO_LONG:
                return R.string.ai_edit_err_too_long;
            case AiEditException.NO_AUDIO:
                return R.string.ai_edit_err_no_audio;
            case AiEditException.NO_SPEECH:
                return R.string.ai_edit_err_no_speech;
            case AiEditException.NETWORK:
                return R.string.ai_edit_err_offline;
            case AiEditException.SIGNED_OUT:
                return R.string.ai_edit_err_signed_out;
            case AiEditException.NO_SPACE:
                return R.string.ai_edit_err_no_space;
            case AiEditException.SOURCE_UNREADABLE:
                return R.string.ai_edit_err_source;
            case AiEditException.UNSUPPORTED_PLAN:
                return R.string.ai_edit_err_update_app;
            case AiEditException.NOTHING_TO_RENDER:
                return R.string.ai_edit_err_nothing;
            default:
                return R.string.ai_edit_err_generic;
        }
    }

    // ---- where the recording's jobs stand (executor thread) ----

    /** What the recording's jobs say right now: a running one, else the latest that failed or finished. */
    private static final class State {
        String level;
        boolean running;
        String failure;
        boolean retryable;
        boolean ready;
    }

    private State readState(String path) {
        AiEditStore store = AiEditStore.get(appContext);
        State state = new State();
        AiEditJob latest = null;
        for (String level : LEVELS) {
            if (AiEditService.isActive(path, level)) {
                state.level = level;
                state.running = true;
                return state;
            }
            AiEditJob job = store.find(path, level);
            if (job != null && (latest == null || job.updatedMs > latest.updatedMs)) latest = job;
        }
        if (latest == null) return state;
        state.level = latest.level;
        if (latest.hasFailed()) {
            state.failure = latest.error;
            state.retryable = latest.errorRetryable;
        } else if (AiEditJob.STAGE_DONE.equals(latest.stage)) {
            state.ready = firstOutput(path, latest.level) != null;
        }
        return state;
    }

    private void applyState(State state) {
        if (state.running) {
            setRunning(state.level, appContext.getString(R.string.ai_edit_working), -1);
        } else if (state.failure != null) {
            showFailure(state.level, state.failure, state.retryable);
        } else if (state.ready) {
            showReady();
        }
    }

    /** The first finished output (9:16 first) of the job of {@code path} at {@code level}, or null. */
    private File firstOutput(String path, String level) {
        if (path == null) return null;
        AiEditJob job = AiEditStore.get(appContext).find(path, level);
        if (job == null || job.outputBase == null) return null;
        for (String ratio : job.doneRatios) {
            File f = AiEditNames.outputFile(job.outputBase, ratio);
            if (f.isFile()) return f;
        }
        return null;
    }

    // ---- price ----

    /** Executor thread. The price list for this video's length, or null when it cannot be had now. */
    private AiEditApi.Quote loadQuote(File src) {
        SessionManager session = SessionManager.get(appContext);
        if (!session.hasSession()) return null;
        final String workspace = session.repository().store().workspaceId();
        long seconds = secondsOf(src);
        try {
            return AiQuoteCache.shared().get(seconds, workspace,
                    (s, w) -> new AiEditApi(session.repository()).quote(s, w));
        } catch (AiEditException e) {
            return null; // the button then shows no number; starting the edit tells the real answer
        }
    }

    private static long secondsOf(File file) {
        String key = file.getAbsolutePath() + "|" + file.length() + "|" + file.lastModified();
        synchronized (SECONDS) {
            Long known = SECONDS.get(key);
            if (known != null) return known;
        }
        long seconds = 1;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (ms != null) seconds = Math.max(1, (Long.parseLong(ms) + 999) / 1000);
        } catch (Exception ignored) {
            // unreadable: priced as the shortest block
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) { }
        }
        synchronized (SECONDS) {
            SECONDS.put(key, seconds);
        }
        return seconds;
    }

    private String creditsText(int credits) {
        return appContext.getResources().getQuantityString(R.plurals.ai_edit_credits, credits, credits);
    }

    // ---- small helpers ----

    /** The video an edit is made from, or null when this preview cannot start one (an AI edit result alone). */
    private File source() {
        File f = host.sourceVideo();
        if (f == null || !f.isFile() || AiEditNames.isAiEditedName(f.getName())) return null;
        return f;
    }

    private String sourcePath() {
        File f = source();
        return f != null ? f.getAbsolutePath() : null;
    }

    private boolean isMine(String path) {
        return path != null && path.equals(sourcePath());
    }

    private void run(Runnable task) {
        try {
            executor.execute(task);
        } catch (RejectedExecutionException ignored) {
            // the preview is closed
        }
    }
}
