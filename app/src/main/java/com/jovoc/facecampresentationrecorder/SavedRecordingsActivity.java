package com.jovoc.facecampresentationrecorder;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.button.MaterialButton;
import com.jovoc.facecampresentationrecorder.adapter.RecordingsAdapter;
import com.jovoc.facecampresentationrecorder.model.RecordingItem;
import com.jovoc.facecampresentationrecorder.util.RecordingStorage;
import com.jovoc.facecampresentationrecorder.util.VideoCropHelper;
import com.jovoc.facecampresentationrecorder.util.VideoFormatHelper;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SavedRecordingsActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private SwipeRefreshLayout swipeRefresh;
    private LinearLayout emptyStateContainer;
    private TextView tvCountHeader;
    private RecordingsAdapter adapter;
    private final List<RecordingItem> recordingList = new ArrayList<>();

    // Contextual selection toolbar
    private RelativeLayout mainToolbar;
    private RelativeLayout selectionToolbar;
    private TextView tvSelectionCount;

    // Scanning the folder reads metadata off every file, so it stays off the main
    // thread — this screen now reloads on resume, on swipe, and on crop completion.
    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** Redraws the list as soon as a background crop appears or completes. */
    private final BroadcastReceiver cropStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            loadRecordings(false);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_saved_recordings);

        ImageButton btnBack = findViewById(R.id.btn_back);
        tvCountHeader = findViewById(R.id.tv_count_header);
        recyclerView = findViewById(R.id.recycler_recordings);
        swipeRefresh = findViewById(R.id.swipe_refresh);
        emptyStateContainer = findViewById(R.id.empty_state_container);
        MaterialButton btnGoMain = findViewById(R.id.btn_go_main);

        mainToolbar = findViewById(R.id.main_toolbar);
        selectionToolbar = findViewById(R.id.selection_toolbar);
        tvSelectionCount = findViewById(R.id.tv_selection_count);
        ImageButton btnCancelSelection = findViewById(R.id.btn_cancel_selection);
        ImageButton btnSelectAll = findViewById(R.id.btn_select_all);
        ImageButton btnDeleteSelected = findViewById(R.id.btn_delete_selected);

        btnBack.setOnClickListener(v -> finish());
        btnGoMain.setOnClickListener(v -> finish());

        btnCancelSelection.setOnClickListener(v -> {
            if (adapter != null) adapter.exitSelectionMode();
        });
        btnSelectAll.setOnClickListener(v -> {
            if (adapter != null) adapter.selectAll();
        });
        btnDeleteSelected.setOnClickListener(v -> confirmDeleteSelected());

        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        // One adapter for the life of the screen, so refreshing keeps its listeners.
        adapter = new RecordingsAdapter(this, recordingList, remainingCount -> updateUI());
        adapter.setOnSelectionChangeListener(this::updateSelectionToolbar);
        recyclerView.setAdapter(adapter);

        swipeRefresh.setColorSchemeColors(0xFF22D3EE);
        swipeRefresh.setProgressBackgroundColorSchemeColor(0xFF1E1E1E);
        // The empty state shares the swipe container, so ask the list itself whether
        // it can still scroll up rather than letting the wrapper guess.
        swipeRefresh.setOnChildScrollUpCallback((parent, child) ->
                recyclerView.getVisibility() == View.VISIBLE
                        && recyclerView.canScrollVertically(-1));
        swipeRefresh.setOnRefreshListener(() -> {
            if (adapter.isSelectionMode()) adapter.exitSelectionMode();
            loadRecordings(true);
        });

        // Back leaves selection mode first, rather than the whole screen.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (adapter != null && adapter.isSelectionMode()) {
                    adapter.exitSelectionMode();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        loadRecordings(false);
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(VideoCropHelper.ACTION_CROP_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(cropStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(cropStateReceiver, filter);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Files may have arrived while the user was recording or in the gallery.
        loadRecordings(false);
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            unregisterReceiver(cropStateReceiver);
        } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        scanExecutor.shutdownNow();
    }

    private void confirmDeleteSelected() {
        if (adapter == null) return;
        int count = adapter.getSelectedCount();
        if (count == 0) return;

        String message = count == 1
                ? "Delete this recording? This cannot be undone."
                : "Delete these " + count + " recordings? This cannot be undone.";

        new AlertDialog.Builder(this)
                .setTitle(count == 1 ? "Delete Recording" : "Delete " + count + " Recordings")
                .setMessage(message)
                .setPositiveButton("Delete", (d, w) -> adapter.deleteSelected())
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Rescans the recordings folder off the main thread and swaps in the result.
     *
     * Skipped while the user is selecting, so an automatic refresh never pulls items
     * out from under a pending delete.
     *
     * @param fromSwipe true when the user pulled to refresh, which owns the spinner
     */
    private void loadRecordings(boolean fromSwipe) {
        if (adapter != null && adapter.isSelectionMode() && !fromSwipe) return;
        if (scanExecutor.isShutdown()) return;

        scanExecutor.execute(() -> {
            List<RecordingItem> scanned = scanRecordings();
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                recordingList.clear();
                recordingList.addAll(scanned);
                adapter.notifyDataSetChanged();
                updateUI();
                if (fromSwipe) swipeRefresh.setRefreshing(false);
            });
        });
    }

    /** Builds the card list: finished files, plus a placeholder per in-flight crop. */
    private List<RecordingItem> scanRecordings() {
        List<RecordingItem> items = new ArrayList<>();

        List<VideoCropHelper.PendingCrop> pending = VideoCropHelper.getPendingCrops();

        // Current folder plus the one used before the app was renamed.
        List<File> found = new ArrayList<>();
        for (File appDir : RecordingStorage.getAllDirs(this)) {
            if (!appDir.exists() || !appDir.isDirectory()) continue;
            deleteOrphanedPartFiles(appDir, pending);
            File[] dirFiles = appDir.listFiles((dir, name) ->
                    !name.startsWith(".") && name.toLowerCase(Locale.US).endsWith(".mp4"));
            if (dirFiles != null) found.addAll(Arrays.asList(dirFiles));
        }
        File[] files = found.toArray(new File[0]);
        SimpleDateFormat dateFormat = new SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.US);

        if (files.length > 0) {
            // Sort by last modified descending (newest first)
            Arrays.sort(files, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));

            MediaMetadataRetriever retriever = new MediaMetadataRetriever();

            for (File file : files) {
                long durationMs = 0;
                String formattedDuration = "00:00";
                int videoWidth = 0;
                int videoHeight = 0;
                try {
                    retriever.setDataSource(file.getAbsolutePath());
                    String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                    if (durationStr != null) {
                        durationMs = Long.parseLong(durationStr);
                        formattedDuration = formatDuration(durationMs);
                    }
                    // Dimensions drive the format tag, so it stays correct after a rename.
                    String w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                    String h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                    if (w != null) videoWidth = Integer.parseInt(w);
                    if (h != null) videoHeight = Integer.parseInt(h);
                } catch (Exception ignored) {}

                String formattedDate = dateFormat.format(new Date(file.lastModified()));
                String formatTag = VideoFormatHelper.getFormatTag(file.getName(), videoWidth, videoHeight);

                RecordingItem item = new RecordingItem(
                        file,
                        file.getName(),
                        file.getAbsolutePath(),
                        durationMs,
                        formattedDuration,
                        formattedDate,
                        formatTag
                );

                // No readable duration means no playable video. Say so on the card
                // instead of offering a 00:00 thumbnail that dies on tap.
                if (durationMs <= 0) {
                    item.setStatus(RecordingItem.Status.UNAVAILABLE);
                    item.setStatusLabel("Unavailable — this file is incomplete");
                }

                items.add(item);
            }

            try {
                retriever.release();
            } catch (Exception ignored) {}
        }

        insertPendingPlaceholders(items, pending, dateFormat);
        return items;
    }

    /**
     * Adds a "preparing" card for each crop still encoding, directly beneath the
     * recording it came from so the relationship is obvious.
     */
    private void insertPendingPlaceholders(List<RecordingItem> items,
                                           List<VideoCropHelper.PendingCrop> pending,
                                           SimpleDateFormat dateFormat) {
        for (VideoCropHelper.PendingCrop crop : pending) {
            if (new File(crop.outputPath).exists()) continue; // already landed

            RecordingItem placeholder = RecordingItem.processing(
                    crop.outputName,
                    crop.outputPath,
                    dateFormat.format(new Date(crop.sourceLastModified)),
                    crop.ratioLabel,
                    "Preparing " + crop.ratioLabel + " version…");

            int sourceIndex = -1;
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).getFileName().equals(crop.sourceName)) {
                    sourceIndex = i;
                    break;
                }
            }
            items.add(sourceIndex >= 0 ? sourceIndex + 1 : 0, placeholder);
        }
    }

    /** Clears ".part" files abandoned by a crash, leaving live encodes alone. */
    private void deleteOrphanedPartFiles(File appDir, List<VideoCropHelper.PendingCrop> pending) {
        File[] parts = appDir.listFiles((dir, name) -> VideoCropHelper.isPartialFile(name));
        if (parts == null) return;

        for (File part : parts) {
            boolean live = false;
            for (VideoCropHelper.PendingCrop crop : pending) {
                if (VideoCropHelper.partFileNameFor(crop.outputPath).equals(part.getName())) {
                    live = true;
                    break;
                }
            }
            if (!live) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
            }
        }
    }

    private void updateUI() {
        int count = 0;
        for (RecordingItem item : recordingList) {
            if (item.isReady()) count++;
        }
        tvCountHeader.setText(count == 1 ? "1 video" : count + " videos");

        boolean empty = recordingList.isEmpty();
        recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
        emptyStateContainer.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    private void updateSelectionToolbar(boolean inSelectionMode, int selectedCount) {
        if (selectionToolbar == null || mainToolbar == null) return;

        selectionToolbar.setVisibility(inSelectionMode ? View.VISIBLE : View.GONE);
        mainToolbar.setVisibility(inSelectionMode ? View.GONE : View.VISIBLE);

        if (inSelectionMode && tvSelectionCount != null) {
            tvSelectionCount.setText(selectedCount + " selected");
        }
    }

    private String formatDuration(long durationMs) {
        long seconds = durationMs / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        seconds = seconds % 60;
        minutes = minutes % 60;

        if (hours > 0) {
            return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds);
        } else {
            return String.format(Locale.US, "%02d:%02d", minutes, seconds);
        }
    }
}
