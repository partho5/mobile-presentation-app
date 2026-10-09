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
import com.jovoc.facecampresentationrecorder.db.PublishJob;
import com.jovoc.facecampresentationrecorder.model.RecordingItem;
import com.jovoc.facecampresentationrecorder.publish.PublishManager;
import com.jovoc.facecampresentationrecorder.util.RecordingStorage;
import com.jovoc.facecampresentationrecorder.util.VideoCropHelper;
import com.jovoc.facecampresentationrecorder.util.VideoFormatHelper;
import com.jovoc.facecampresentationrecorder.util.VideoGroupStore;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    /** Updates the publish status line of the affected cards as an upload progresses. */
    private final BroadcastReceiver publishStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshPublishStatus();
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
        IntentFilter publishFilter = new IntentFilter(PublishManager.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(publishStateReceiver, publishFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(publishStateReceiver, publishFilter);
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
        try {
            unregisterReceiver(publishStateReceiver);
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

                // No readable duration means a broken or half-written file. It cannot
                // be played, and a card for it cannot be acted on, so leave it out.
                if (durationMs <= 0) continue;

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

                items.add(item);
            }

            try {
                retriever.release();
            } catch (Exception ignored) {}
        }

        insertPendingPlaceholders(items, pending, dateFormat);
        List<RecordingItem> grouped = clusterByGroup(items);
        attachPublishJobs(grouped);
        return grouped;
    }

    /** Left-strip colours, cycled across recordings. */
    private static final int[] GROUP_COLORS = {
            0xFF22D3EE, 0xFFF59E0B, 0xFFA78BFA, 0xFF34D399, 0xFFF472B6
    };

    /**
     * Puts every version of a recording side by side (original first, then its
     * crops) and gives each recording one strip colour. Recordings are ordered by
     * their newest file. A file with no group gets its own, so even a lone video has
     * a colour that survives renames.
     */
    private List<RecordingItem> clusterByGroup(List<RecordingItem> items) {
        VideoGroupStore.prune(this, () -> {
            java.util.Set<String> keep = new java.util.HashSet<>();
            for (VideoCropHelper.PendingCrop crop : VideoCropHelper.getPendingCrops()) {
                keep.add(crop.outputPath);
            }
            return keep;
        });

        Map<String, List<RecordingItem>> groups = new java.util.LinkedHashMap<>();
        for (RecordingItem item : items) {
            String id = VideoGroupStore.ensureGroup(this, item.getFilePath());
            item.setGroupId(id);
            List<RecordingItem> members = groups.get(id);
            if (members == null) {
                members = new ArrayList<>();
                groups.put(id, members);
            }
            members.add(item);
        }

        List<Map.Entry<String, List<RecordingItem>>> ordered = new ArrayList<>(groups.entrySet());
        ordered.sort((g1, g2) -> Long.compare(newestModified(g2.getValue()), newestModified(g1.getValue())));

        int[] colorIndex = assignGroupColors(ordered);

        List<RecordingItem> result = new ArrayList<>(items.size());
        for (int g = 0; g < ordered.size(); g++) {
            List<RecordingItem> members = ordered.get(g).getValue();
            // Original was recorded first; a crop still encoding goes last.
            members.sort((m1, m2) -> Long.compare(orderKey(m1), orderKey(m2)));
            for (RecordingItem member : members) {
                member.setAccentColor(GROUP_COLORS[colorIndex[g]]);
                result.add(member);
            }
        }
        return result;
    }

    /**
     * Palette slot per group, in list order. A group keeps the slot it was first
     * given for good; only groups without one are placed, each in the slot whose
     * nearest user in the list is farthest away, so repeats end up as far apart as
     * the palette allows.
     */
    private int[] assignGroupColors(List<Map.Entry<String, List<RecordingItem>>> ordered) {
        int n = ordered.size();
        int[] slot = new int[n];
        for (int g = 0; g < n; g++) {
            slot[g] = VideoGroupStore.getColorIndex(this, ordered.get(g).getKey());
        }

        for (int g = 0; g < n; g++) {
            if (slot[g] >= 0) continue;

            int best = 0;
            int bestGap = -1;
            int bestUses = Integer.MAX_VALUE;
            for (int c = 0; c < GROUP_COLORS.length; c++) {
                int gap = Integer.MAX_VALUE; // nearest group already wearing colour c
                int uses = 0;
                for (int other = 0; other < n; other++) {
                    if (slot[other] != c) continue;
                    uses++;
                    gap = Math.min(gap, Math.abs(other - g));
                }
                if (gap > bestGap || (gap == bestGap && uses < bestUses)) {
                    best = c;
                    bestGap = gap;
                    bestUses = uses;
                }
            }
            slot[g] = best;
            VideoGroupStore.setColorIndex(this, ordered.get(g).getKey(), best);
        }
        return slot;
    }

    private static long newestModified(List<RecordingItem> members) {
        long newest = 0;
        for (RecordingItem m : members) newest = Math.max(newest, m.getFile().lastModified());
        return newest;
    }

    private static long orderKey(RecordingItem item) {
        return item.getStatus() == RecordingItem.Status.PROCESSING
                ? Long.MAX_VALUE : item.getFile().lastModified();
    }

    /** Tags every card with the newest publish attempt of its file (if any). */
    private void attachPublishJobs(List<RecordingItem> items) {
        PublishManager publish = PublishManager.get(this);
        publish.reconcile();
        Map<String, PublishJob> jobs = publish.latestByPath();
        for (RecordingItem item : items) {
            if (item.isReady()) item.setPublishJob(jobs.get(item.getFilePath()));
        }
    }

    /** Re-reads publish state and redraws only the cards whose state moved. */
    private void refreshPublishStatus() {
        if (scanExecutor.isShutdown()) return;
        scanExecutor.execute(() -> {
            Map<String, PublishJob> jobs = PublishManager.get(this).latestByPath();
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                for (int i = 0; i < recordingList.size(); i++) {
                    RecordingItem item = recordingList.get(i);
                    if (!item.isReady()) continue;
                    PublishJob now = jobs.get(item.getFilePath());
                    if (publishChanged(item.getPublishJob(), now)) {
                        item.setPublishJob(now);
                        adapter.notifyItemChanged(i);
                    }
                }
            });
        });
    }

    private static boolean publishChanged(PublishJob before, PublishJob after) {
        if (before == null || after == null) return before != after;
        return before.id != after.id || before.updatedAt != after.updatedAt
                || before.progress != after.progress
                || !java.util.Objects.equals(before.status, after.status);
    }

    /**
     * Adds a "preparing" card for each crop still encoding; clustering then files it
     * under the recording it came from.
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

            items.add(placeholder);
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
        // One "original" per recording, however many ratio versions it has.
        java.util.Set<String> originals = new java.util.HashSet<>();
        for (RecordingItem item : recordingList) {
            if (!item.isReady()) continue;
            count++;
            originals.add(item.getGroupId());
        }
        String total = count == 1 ? "1 video" : count + " videos";
        // Only worth explaining when some cards are edited versions of another.
        tvCountHeader.setText(originals.size() == count ? total
                : total + " (" + originals.size() + " recorded, others edited versions)");

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
