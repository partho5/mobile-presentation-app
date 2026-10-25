package com.jovoc.facecampresentationrecorder.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.signature.ObjectKey;
import com.google.android.material.card.MaterialCardView;
import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.SavedRecordingsActivity;
import com.jovoc.facecampresentationrecorder.activitylog.ActivityLog;
import com.jovoc.facecampresentationrecorder.edit.EditGroupMath;
import com.jovoc.facecampresentationrecorder.edit.EditorGate;
import com.jovoc.facecampresentationrecorder.service.BgReplaceService;
import com.jovoc.facecampresentationrecorder.util.VideoCropHelper;
import com.jovoc.facecampresentationrecorder.util.VideoFormatHelper;
import com.jovoc.facecampresentationrecorder.util.VideoGroupStore;
import com.jovoc.facecampresentationrecorder.util.VideoRenameHelper;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Preview player. Shows the opened video in the centre and, when the recording has other
 * aspect-ratio versions (the auto-crops), a small cut-off preview of each on the sides that
 * can be swiped or tapped into the centre. Only the centred version owns a live VideoView.
 */
public class VideoPlayerDialog {

    private static final String TAG = "VideoPlayerDialog";

    private static final float GAP_DP = 10f;
    private static final float PEEK_DP = 30f;
    private static final float MIN_CARD_W_DP = 140f;
    private static final long THUMB_FADE_FALLBACK_MS = 700L;

    public interface OnVideoActionListener {
        void onVideoRenamed(File oldFile, File newFile);
        void onVideoDeleted(File deletedFile);
    }

    public static Dialog show(Context context, File initialFile, OnVideoActionListener listener) {
        if (context == null || initialFile == null || !initialFile.exists()) {
            if (context != null) {
                Toast.makeText(context, "Video file not found", Toast.LENGTH_SHORT).show();
            }
            return null;
        }
        return new VideoPlayerDialog(context, initialFile, listener).dialog;
    }

    /** One playable version of the recording. Identity is {@link #id}, never the list index. */
    private static final class Version {
        final long id;
        File file;
        int w, h;
        int cardW, cardH;
        boolean animateIn;

        Version(long id) {
            this.id = id;
        }

        String path() {
            return file.getAbsolutePath();
        }
    }

    /** Result of a background reload: a file that is complete and playable, with its size. */
    private static final class Candidate {
        final File file;
        final int w, h;

        Candidate(File file, int w, int h) {
            this.file = file;
            this.w = w;
            this.h = h;
        }
    }

    /** Immutable view of a Version, so DiffUtil can compare "before" with "after". */
    private static final class Snap {
        final long id;
        final String path;
        final int cardW, cardH;

        Snap(Version v) {
            id = v.id;
            path = v.path();
            cardW = v.cardW;
            cardH = v.cardH;
        }
    }

    private static final class PageHolder extends RecyclerView.ViewHolder {
        final MaterialCardView card;
        final VideoView video;
        final ImageView thumb;
        final View tap;
        Version version;
        long fadingVersionId = -1;

        PageHolder(@NonNull View itemView) {
            super(itemView);
            card = itemView.findViewById(R.id.page_card);
            video = itemView.findViewById(R.id.page_video);
            thumb = itemView.findViewById(R.id.page_thumb);
            tap = itemView.findViewById(R.id.page_tap);
        }
    }

    private final Context context;
    private final Context appContext;
    private final OnVideoActionListener listener;
    private final Dialog dialog;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    /** Touched on the executor thread only. */
    private final Map<String, int[]> dimsCache = new HashMap<>();

    private final float density;
    private final int stageW;
    private final int maxCardH;
    private final int singleMaxCardW;
    private final int multiMaxCardW;
    private final int minCardW;

    private final ViewPager2 pager;
    private RecyclerView pagerList;
    private final SeekBar seekBar;
    private final TextView tvCurrentTime;
    private final TextView tvTotalDuration;
    private final TextView tvFileName;
    private final TextView tvMetadata;
    private final ImageButton btnBarPlayPause;

    private final List<Version> versions = new ArrayList<>();
    private final PageAdapter adapter = new PageAdapter();
    private long nextVersionId = 1;
    private boolean firstLoadDone;
    private boolean dismissed;
    /** True while a reload re-centres the pager; page-selected events are ignored then. */
    private boolean restoring;
    /** A reload that arrived mid-swipe; applied once the pager settles. */
    private List<Candidate> deferred;

    /** The file that should be playing: the centred page's file. */
    private String playingPath;
    private PageHolder activeHolder;
    private VideoView activeVideo;
    private boolean prepared;
    /** Where the next activation resumes, and whether it plays or stays paused. */
    private int pendingSeekMs = 0;
    private boolean pendingPlay = true;

    private BroadcastReceiver cropReceiver;

    private final ImageButton btnEdit;
    private final ImageButton btnPublish;
    /** The group's master video, or null when it is gone (Edit hidden). Main thread only. */
    private String masterPath;

    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            VideoView v = activeVideo;
            if (v != null && v.isPlaying()) {
                int currentPosition = v.getCurrentPosition();
                seekBar.setProgress(currentPosition);
                tvCurrentTime.setText(formatDuration(currentPosition));
                handler.postDelayed(this, 250);
            }
        }
    };

    private final Runnable thumbFadeRunnable = new Runnable() {
        @Override
        public void run() {
            fadeThumb(activeHolder);
        }
    };

    private VideoPlayerDialog(Context context, File initialFile, OnVideoActionListener listener) {
        this.context = context;
        this.appContext = context.getApplicationContext();
        this.listener = listener;

        dialog = new Dialog(context);
        dialog.setContentView(R.layout.dialog_video_player);

        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        density = dm.density;
        int dialogW = (int) (dm.widthPixels * 0.92f);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            // Inset from the screen edges so the rounded corners and hairline
            // border are actually visible rather than running off the display.
            window.setLayout(dialogW, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.82f);
        }

        // The pager spans the dialog less 1dp each side (see dialog_video_player.xml)
        stageW = dialogW - (int) (2 * density);
        // Let the video take whatever height the dialog's other rows (header, controls,
        // file card, paddings: ~270dp) leave within 90% of the screen, but never more than
        // 70% of it, so a tall slide still leaves room around it to swipe
        int fitH = Math.max((int) (200 * density), (int) (dm.heightPixels * 0.90f - 270 * density));
        maxCardH = Math.min(fitH, (int) (dm.heightPixels * 0.70f));
        minCardW = (int) (MIN_CARD_W_DP * density);
        singleMaxCardW = Math.min((int) (dm.widthPixels * 0.82f), stageW);
        // Leaves room for a peek of the neighbouring version on each side
        multiMaxCardW = Math.max(minCardW, stageW - (int) (2 * (PEEK_DP + GAP_DP) * density));

        ImageButton btnClose = dialog.findViewById(R.id.btn_close_dialog);
        pager = dialog.findViewById(R.id.player_pager);
        btnBarPlayPause = dialog.findViewById(R.id.btn_bar_play_pause);
        seekBar = dialog.findViewById(R.id.player_seek_bar);
        tvCurrentTime = dialog.findViewById(R.id.tv_current_time);
        tvTotalDuration = dialog.findViewById(R.id.tv_total_duration);
        tvFileName = dialog.findViewById(R.id.tv_player_filename);
        tvMetadata = dialog.findViewById(R.id.tv_player_metadata);
        ImageButton btnRename = dialog.findViewById(R.id.btn_rename_video);
        ImageButton btnDelete = dialog.findViewById(R.id.btn_delete_video);
        ImageButton btnShare = dialog.findViewById(R.id.btn_share_video);
        ImageButton btnAllVideos = dialog.findViewById(R.id.btn_all_videos);
        btnEdit = dialog.findViewById(R.id.btn_edit_video);
        btnPublish = dialog.findViewById(R.id.btn_publish_video);

        // The opened file alone first, sized before the dialog is shown, so a recording
        // without versions looks and behaves exactly like a plain player
        Version opened = new Version(nextVersionId++);
        opened.file = initialFile;
        int[] dims = readDims(initialFile);
        if (dims != null) {
            opened.w = dims[0];
            opened.h = dims[1];
        }
        versions.add(opened);
        playingPath = opened.path();
        recomputeSizes();

        updateMetadataUI(initialFile, tvFileName, tvMetadata);

        setupPager();

        // Controls
        View.OnClickListener togglePlayPause = v -> togglePlayPause();
        btnBarPlayPause.setOnClickListener(togglePlayPause);

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser && activeVideo != null) {
                    activeVideo.seekTo(progress);
                    tvCurrentTime.setText(formatDuration(progress));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                handler.removeCallbacks(progressRunnable);
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                if (activeVideo != null && activeVideo.isPlaying()) {
                    handler.post(progressRunnable);
                }
            }
        });

        btnClose.setOnClickListener(v -> dialog.dismiss());

        // Rename Action — the prompt, validation and MediaStore bookkeeping live
        // in VideoRenameHelper; this only saves and restores playback around it.
        btnRename.setOnClickListener(v -> {
            ActivityLog.log("player_action", "item", "rename");
            Version current = findVersion(playingPath);
            if (current == null) return;
            VideoRenameHelper.promptRename(context, current.file,
                    new VideoRenameHelper.RenameCallback() {
                        @Override
                        public void onBeforeRename() {
                            // Release the open file handle, or the rename fails.
                            captureState();
                            deactivateActive();
                            // If the rename then fails nothing else restarts playback
                            handler.post(() -> syncActive());
                        }

                        @Override
                        public void onRenamed(File oldFile, File newFile) {
                            Version renamed = findVersion(oldFile.getAbsolutePath());
                            if (renamed == null) return;
                            boolean wasPlayingOne = renamed.path().equals(playingPath);
                            renamed.file = newFile;
                            if (wasPlayingOne) playingPath = newFile.getAbsolutePath();
                            int index = versions.indexOf(renamed);
                            if (index >= 0) adapter.notifyItemChanged(index);
                            updateMetadataUI(newFile, tvFileName, tvMetadata);
                            syncActive();
                            reloadVersions();

                            if (VideoPlayerDialog.this.listener != null) {
                                VideoPlayerDialog.this.listener.onVideoRenamed(oldFile, newFile);
                            }
                        }
                    });
        });

        // Delete Action — always the version in the centre
        btnDelete.setOnClickListener(v -> {
            ActivityLog.log("player_action", "item", "delete");
            Version current = findVersion(playingPath);
            if (current == null) return;
            final File fileToDelete = current.file;
            new AlertDialog.Builder(context)
                    .setTitle("Delete Recording")
                    .setMessage("Are you sure you want to delete \"" + fileToDelete.getName() + "\"?")
                    .setPositiveButton("Delete", (d, which) -> {
                        if (activeVideo != null) {
                            activeVideo.stopPlayback();
                        }
                        boolean deleted = false;
                        if (fileToDelete.exists()) {
                            deleted = fileToDelete.delete();
                        }

                        if (deleted || !fileToDelete.exists()) {
                            VideoGroupStore.remove(context, fileToDelete.getAbsolutePath());
                            try {
                                ContentResolver resolver = context.getContentResolver();
                                resolver.delete(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                                        MediaStore.Video.Media.DATA + "=?",
                                        new String[]{fileToDelete.getAbsolutePath()});
                            } catch (Exception ignored) {}

                            MediaScannerConnection.scanFile(context, new String[]{fileToDelete.getAbsolutePath()}, null, null);

                            if (listener != null) {
                                listener.onVideoDeleted(fileToDelete);
                            }
                            Toast.makeText(context, "Video deleted", Toast.LENGTH_SHORT).show();
                            dialog.dismiss();
                        } else {
                            Toast.makeText(context, "Failed to delete video", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("Cancel", (d, which) -> d.dismiss())
                    .show();
        });

        // Share Action — always the version in the centre
        btnShare.setOnClickListener(v -> {
            ActivityLog.log("player_action", "item", "share");
            Version current = findVersion(playingPath);
            if (current == null) return;
            try {
                Uri uri = FileProvider.getUriForFile(
                        context,
                        context.getPackageName() + ".fileprovider",
                        current.file
                );
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("video/mp4");
                shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                context.startActivity(Intent.createChooser(shareIntent, "Share Video Recording"));
            } catch (Exception e) {
                Log.e(TAG, "Error sharing video", e);
                Toast.makeText(context, "Unable to share video: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        // "See all videos": opens My Videos; pointless when the player was opened from there
        if (context instanceof SavedRecordingsActivity) {
            btnAllVideos.setVisibility(View.GONE);
        } else {
            btnAllVideos.setOnClickListener(v -> {
                ActivityLog.log("player_action", "item", "all_videos");
                dialog.dismiss();
                context.startActivity(new Intent(context, SavedRecordingsActivity.class));
            });
        }

        // Edit: the editor always works on the group's master and saves a new group
        btnEdit.setOnClickListener(v -> {
            ActivityLog.log("player_action", "item", "edit");
            String master = masterPath;
            if (master == null) return;
            if (BgReplaceService.isReplacePending(master)) {
                Toast.makeText(context, R.string.editor_preparing, Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            EditorLauncher.launch(context, master);
        });
        updateEditButtons(findMaster(initialFile.getAbsolutePath()));

        // Publish: the existing flow for the version in the centre, no edit. Not offered while
        // auto-publish is on (the 9:16 is already queued).
        btnPublish.setVisibility(EditorGate.showPublishButton(context) ? View.VISIBLE : View.GONE);
        btnPublish.setOnClickListener(v -> {
            ActivityLog.log("player_action", "item", "publish");
            Version current = findVersion(playingPath);
            if (current == null) return;
            PublishLauncher.publish(context, current.file);
        });

        // Versions arrive while the dialog is open. The receiver is registered before the
        // first load so a crop finishing in between is not missed.
        cropReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                reloadVersions();
            }
        };
        ContextCompat.registerReceiver(appContext, cropReceiver,
                new IntentFilter(VideoCropHelper.ACTION_CROP_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED);

        // Cleanup on dismiss
        final long openedAt = android.os.SystemClock.elapsedRealtime();
        dialog.setOnDismissListener(d -> {
            ActivityLog.log("player_close", "open_ms", android.os.SystemClock.elapsedRealtime() - openedAt);
            dismissed = true;
            handler.removeCallbacksAndMessages(null);
            if (activeVideo != null) {
                try {
                    activeVideo.stopPlayback();
                } catch (Exception ignored) {}
            }
            activeVideo = null;
            activeHolder = null;
            try {
                appContext.unregisterReceiver(cropReceiver);
            } catch (Exception ignored) {}
            executor.shutdownNow();
            pager.setAdapter(null);
        });

        dialog.show();
        pager.post(() -> syncActive());
        reloadVersions();
    }

    // ------------------------------------------------------------------ pager

    private void setupPager() {
        pager.setAdapter(adapter);
        pager.setOffscreenPageLimit(3);
        pager.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        pager.setUserInputEnabled(versions.size() > 1);
        pagerList = (RecyclerView) pager.getChildAt(0);
        pagerList.setOverScrollMode(View.OVER_SCROLL_NEVER);
        // A change animation would swap the ViewHolder (and with it the playing VideoView)
        pagerList.setItemAnimator(null);

        final float gap = GAP_DP * density;
        pager.setPageTransformer((page, position) -> {
            int i = pagerList.getChildAdapterPosition(page);
            if (i < 0 || i >= versions.size()) return;
            float stage = page.getWidth() > 0 ? page.getWidth() : stageW;
            float ownW = versions.get(i).cardW;
            int towardIndex = position > 0 ? i - 1 : i + 1;
            float towardW = towardIndex >= 0 && towardIndex < versions.size()
                    ? versions.get(towardIndex).cardW : ownW;
            float offset = PlayerCarouselMath.centreOffset(position, ownW, towardW, gap, stage);
            // ViewPager2 has already placed the page at position * stage
            page.setTranslationX(offset - position * stage);
            float scale = PlayerCarouselMath.scale(position);
            page.setScaleX(scale);
            page.setScaleY(scale);
            page.setAlpha(PlayerCarouselMath.alpha(position));
        });

        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                if (dismissed || restoring) return;
                if (position >= 0 && position < versions.size()) {
                    setPlayingPath(versions.get(position).path());
                }
            }

            @Override
            public void onPageScrollStateChanged(int state) {
                if (state == ViewPager2.SCROLL_STATE_IDLE && deferred != null) {
                    handler.post(() -> {
                        List<Candidate> list = deferred;
                        deferred = null;
                        if (list != null) applyVersions(list);
                    });
                }
            }
        });
    }

    /** Card sizes for every version and the pager height; call after the version list changes. */
    private void recomputeSizes() {
        int maxW = versions.size() >= 2 ? multiMaxCardW : singleMaxCardW;
        int pagerH = 0;
        for (Version v : versions) {
            float aspect = v.w > 0 && v.h > 0 ? (float) v.w / v.h : 9f / 16f;
            int[] card = PlayerCarouselMath.fitCard(aspect, maxW, maxCardH, minCardW);
            v.cardW = card[0];
            v.cardH = card[1];
            pagerH = Math.max(pagerH, v.cardH);
        }
        ViewGroup.LayoutParams lp = pager.getLayoutParams();
        if (lp != null && pagerH > 0 && lp.height != pagerH) {
            lp.height = pagerH;
            pager.setLayoutParams(lp);
        }
    }

    private final class PageAdapter extends RecyclerView.Adapter<PageHolder> {

        PageAdapter() {
            setHasStableIds(true);
        }

        @Override
        public int getItemCount() {
            return versions.size();
        }

        @Override
        public long getItemId(int position) {
            return versions.get(position).id;
        }

        @NonNull
        @Override
        public PageHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_player_page, parent, false);
            return new PageHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull PageHolder h, int position) {
            Version v = versions.get(position);
            if (h == activeHolder && h.version != v) {
                // This holder now shows another version than the one that was playing
                deactivateActive();
            }
            h.version = v;

            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) h.card.getLayoutParams();
            if (lp.width != v.cardW || lp.height != v.cardH) {
                lp.width = v.cardW;
                lp.height = v.cardH;
                h.card.setLayoutParams(lp);
            }

            if (h != activeHolder) {
                h.video.setVisibility(View.GONE);
                h.thumb.animate().cancel();
                h.thumb.setAlpha(1f);
                h.thumb.setVisibility(View.VISIBLE);
            }
            Glide.with(appContext)
                    .load(v.file)
                    .apply(RequestOptions.frameOf(1_000_000L))
                    .signature(new ObjectKey(v.file.lastModified() + ":" + v.file.length()))
                    .centerCrop()
                    .into(h.thumb);

            h.tap.setOnClickListener(view -> onPageTapped(h));

            // The card fades in; the page root belongs to the transformer
            if (v.animateIn) {
                v.animateIn = false;
                h.fadingVersionId = v.id;
                h.card.animate().cancel();
                h.card.setAlpha(0f);
                h.card.animate().alpha(1f).setDuration(400)
                        .setInterpolator(new DecelerateInterpolator())
                        .withEndAction(() -> h.fadingVersionId = -1)
                        .start();
            } else if (h.fadingVersionId != v.id) {
                h.card.animate().cancel();
                h.card.setAlpha(1f);
            }
        }

        @Override
        public void onViewAttachedToWindow(@NonNull PageHolder h) {
            // The playing page may attach after the dialog is already open
            pager.post(() -> syncActive());
        }

        @Override
        public void onViewRecycled(@NonNull PageHolder h) {
            if (h == activeHolder) deactivateActive();
            h.card.animate().cancel();
            h.card.setAlpha(1f);
            h.fadingVersionId = -1;
            h.version = null;
            if (!dismissed) {
                try {
                    Glide.with(appContext).clear(h.thumb);
                } catch (Exception ignored) {}
            }
        }
    }

    private void onPageTapped(PageHolder h) {
        int pos = h.getBindingAdapterPosition();
        if (pos == RecyclerView.NO_POSITION) return;
        if (pos == pager.getCurrentItem()) {
            togglePlayPause();
        } else {
            pager.setCurrentItem(pos, true);
        }
    }

    // ------------------------------------------------------------- versions

    private Version findVersion(String path) {
        if (path == null) return null;
        for (Version v : versions) {
            if (path.equals(v.path())) return v;
        }
        return null;
    }

    private int indexOfPath(String path) {
        for (int i = 0; i < versions.size(); i++) {
            if (versions.get(i).path().equals(path)) return i;
        }
        return -1;
    }

    private void reloadVersions() {
        if (dismissed) return;
        final String anchor = playingPath;
        try {
            executor.execute(() -> {
                List<Candidate> list = loadCandidates(anchor);
                String master = findMaster(anchor);
                handler.post(() -> {
                    if (dismissed) return;
                    updateEditButtons(master);
                    applyVersions(list);
                });
            });
        } catch (RejectedExecutionException ignored) {
            // dialog already closed
        }
    }

    /** The group's master video for the group that {@code anchorPath} belongs to, or null. */
    private String findMaster(String anchorPath) {
        return EditGroupMath.masterOf(VideoGroupStore.getGroupPaths(appContext, anchorPath));
    }

    /**
     * Main thread. Edit is hidden without a master; while the background replace of that master
     * still runs it looks disabled and says "Preparing..." when tapped.
     */
    private void updateEditButtons(String master) {
        masterPath = master;
        btnEdit.setVisibility(master == null ? View.GONE : View.VISIBLE);
        boolean preparing = master != null && BgReplaceService.isReplacePending(master);
        btnEdit.setAlpha(preparing ? 0.35f : 1f);
    }

    /** Executor thread. Complete, playable files of the group, in display order. */
    private List<Candidate> loadCandidates(String anchorPath) {
        Set<String> pending = new HashSet<>();
        for (VideoCropHelper.PendingCrop crop : VideoCropHelper.getPendingCrops()) {
            pending.add(crop.outputPath);
        }

        List<Candidate> out = new ArrayList<>();
        for (String path : VideoGroupStore.getGroupPaths(appContext, anchorPath)) {
            boolean isAnchor = path.equals(anchorPath);
            File f = new File(path);
            if (!f.exists() || f.length() == 0) continue;
            // The group lists crops before their files exist; skip anything not final
            if (!isAnchor && (VideoCropHelper.isPartialFile(f.getName()) || pending.contains(path))) {
                continue;
            }
            int[] dims = cachedDims(f);
            if (dims == null && !isAnchor) continue;
            out.add(new Candidate(f, dims != null ? dims[0] : 0, dims != null ? dims[1] : 0));
        }

        // Tallest (smallest width/height) first, so the original is L0 and 9:16 is L1
        Collections.sort(out, (a, b) -> {
            int c = Float.compare(aspectOf(a.w, a.h), aspectOf(b.w, b.h));
            return c != 0 ? c : a.file.getAbsolutePath().compareTo(b.file.getAbsolutePath());
        });
        return PlayerCarouselMath.displayOrder(out);
    }

    private int[] cachedDims(File f) {
        String key = f.getAbsolutePath() + "|" + f.length() + "|" + f.lastModified();
        int[] dims = dimsCache.get(key);
        if (dims == null) {
            dims = readDims(f);
            if (dims != null) dimsCache.put(key, dims);
        }
        return dims;
    }

    private static float aspectOf(int w, int h) {
        return w > 0 && h > 0 ? (float) w / h : 9f / 16f;
    }

    /** Display size of a video (rotation applied), or null when unreadable. */
    private static int[] readDims(File file) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String ws = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String hs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            String rs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if (ws == null || hs == null) return null;
            int w = Integer.parseInt(ws);
            int h = Integer.parseInt(hs);
            int rotation = rs != null ? Integer.parseInt(rs) : 0;
            if (w <= 0 || h <= 0) return null;
            return rotation == 90 || rotation == 270 ? new int[]{h, w} : new int[]{w, h};
        } catch (Exception e) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {}
        }
    }

    /** Main thread. Merges a fresh version list into the pager without touching playback. */
    private void applyVersions(List<Candidate> list) {
        if (dismissed) return;
        // A result computed before a rename or without the playing file is stale
        boolean hasPlaying = false;
        for (Candidate c : list) {
            if (c.file.getAbsolutePath().equals(playingPath)) {
                hasPlaying = true;
                break;
            }
        }
        if (!hasPlaying) return;

        if (pager.getScrollState() != ViewPager2.SCROLL_STATE_IDLE) {
            deferred = list;
            return;
        }

        Map<String, Version> byPath = new HashMap<>();
        for (Version v : versions) byPath.put(v.path(), v);

        List<Snap> before = new ArrayList<>(versions.size());
        for (Version v : versions) before.add(new Snap(v));

        List<Version> next = new ArrayList<>(list.size());
        for (Candidate c : list) {
            Version v = byPath.get(c.file.getAbsolutePath());
            if (v == null) {
                v = new Version(nextVersionId++);
                v.file = c.file;
                // Versions that already existed when the dialog opened do not fade in
                v.animateIn = firstLoadDone;
            }
            v.w = c.w;
            v.h = c.h;
            next.add(v);
        }
        firstLoadDone = true;

        versions.clear();
        versions.addAll(next);
        recomputeSizes();

        List<Snap> after = new ArrayList<>(versions.size());
        for (Version v : versions) after.add(new Snap(v));

        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return before.size();
            }

            @Override
            public int getNewListSize() {
                return after.size();
            }

            @Override
            public boolean areItemsTheSame(int o, int n) {
                return before.get(o).id == after.get(n).id;
            }

            @Override
            public boolean areContentsTheSame(int o, int n) {
                Snap a = before.get(o), b = after.get(n);
                return a.path.equals(b.path) && a.cardW == b.cardW && a.cardH == b.cardH;
            }
        });
        diff.dispatchUpdatesTo(adapter);

        pager.setUserInputEnabled(versions.size() > 1);

        // Indices shifted: keep the playing file centred, without the playback restarting
        restoring = true;
        int index = indexOfPath(playingPath);
        if (index >= 0) pager.setCurrentItem(index, false);
        pager.post(() -> {
            if (dismissed) return;
            int i = indexOfPath(playingPath);
            if (i >= 0) pager.setCurrentItem(i, false);
            restoring = false;
            pager.requestTransform();
            syncActive();
        });
    }

    // --------------------------------------------------------------- playback

    /** The centred page changed to {@code path}: carry position and play state over. */
    private void setPlayingPath(String path) {
        if (path == null || path.equals(playingPath)) return;
        captureState();
        deactivateActive();
        playingPath = path;
        Version v = findVersion(path);
        if (v != null) updateMetadataUI(v.file, tvFileName, tvMetadata);
        syncActive();
    }

    /** Binds the playing file to its page if it is not bound yet. */
    private void syncActive() {
        if (dismissed || activeHolder != null) return;
        PageHolder h = holderFor(playingPath);
        if (h != null) startOn(h);
    }

    private PageHolder holderFor(String path) {
        for (int i = 0; i < pagerList.getChildCount(); i++) {
            RecyclerView.ViewHolder vh = pagerList.getChildViewHolder(pagerList.getChildAt(i));
            if (vh instanceof PageHolder) {
                PageHolder h = (PageHolder) vh;
                if (h.version != null && h.version.path().equals(path)) return h;
            }
        }
        return null;
    }

    private void captureState() {
        if (activeVideo == null || !prepared) return;
        try {
            pendingSeekMs = activeVideo.getCurrentPosition();
            pendingPlay = activeVideo.isPlaying();
        } catch (Exception ignored) {}
    }

    private void startOn(PageHolder h) {
        final VideoView video = h.video;
        final int seekMs = pendingSeekMs;
        final boolean play = pendingPlay;
        activeHolder = h;
        activeVideo = video;
        prepared = false;

        h.thumb.animate().cancel();
        h.thumb.setAlpha(1f);
        h.thumb.setVisibility(View.VISIBLE);
        video.setVisibility(View.VISIBLE);

        video.setOnPreparedListener(mp -> {
            if (dismissed || video != activeVideo) return;
            prepared = true;
            int duration = mp.getDuration();
            seekBar.setMax(duration);
            tvTotalDuration.setText(formatDuration(duration));

            int target = duration > 0 ? Math.min(seekMs, duration) : seekMs;
            // A paused version still needs a seek to put its frame on screen
            if (target > 0 || !play) video.seekTo(target);
            seekBar.setProgress(target);
            tvCurrentTime.setText(formatDuration(target));

            if (play) {
                video.start();
                btnBarPlayPause.setImageResource(R.drawable.ic_pause);
                handler.post(progressRunnable);
            } else {
                btnBarPlayPause.setImageResource(R.drawable.ic_play);
            }
            handler.removeCallbacks(thumbFadeRunnable);
            handler.postDelayed(thumbFadeRunnable, THUMB_FADE_FALLBACK_MS);
        });

        video.setOnInfoListener((mp, what, extra) -> {
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && video == activeVideo) {
                fadeThumb(h);
            }
            return false;
        });

        video.setOnCompletionListener(mp -> {
            btnBarPlayPause.setImageResource(R.drawable.ic_play);
            seekBar.setProgress(0);
            tvCurrentTime.setText("00:00");
            handler.removeCallbacks(progressRunnable);
        });

        video.setVideoPath(playingPath);
    }

    private void fadeThumb(PageHolder h) {
        if (h == null || h != activeHolder || h.thumb.getVisibility() != View.VISIBLE) return;
        h.thumb.animate().alpha(0f).setDuration(120)
                .withEndAction(() -> h.thumb.setVisibility(View.INVISIBLE))
                .start();
    }

    /** Releases the live VideoView and puts the thumbnail back. Exactly one VideoView may be live. */
    private void deactivateActive() {
        handler.removeCallbacks(progressRunnable);
        handler.removeCallbacks(thumbFadeRunnable);
        PageHolder h = activeHolder;
        activeHolder = null;
        activeVideo = null;
        prepared = false;
        if (h == null) return;
        try {
            h.video.setOnPreparedListener(null);
            h.video.setOnInfoListener(null);
            h.video.setOnCompletionListener(null);
            h.video.stopPlayback();
        } catch (Exception ignored) {}
        h.video.setVisibility(View.GONE);
        h.thumb.animate().cancel();
        h.thumb.setAlpha(1f);
        h.thumb.setVisibility(View.VISIBLE);
    }

    private void togglePlayPause() {
        VideoView v = activeVideo;
        if (v == null || !prepared) return;
        if (v.isPlaying()) {
            v.pause();
            btnBarPlayPause.setImageResource(R.drawable.ic_play);
            handler.removeCallbacks(progressRunnable);
        } else {
            v.start();
            btnBarPlayPause.setImageResource(R.drawable.ic_pause);
            handler.post(progressRunnable);
        }
    }

    // ------------------------------------------------------------------ misc

    private static void updateMetadataUI(File file, TextView tvFileName, TextView tvMetadata) {
        if (file == null || tvFileName == null || tvMetadata == null) return;
        tvFileName.setText(file.getName());

        String formattedDate = new SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.US).format(new Date(file.lastModified()));
        long sizeInBytes = file.length();
        String formattedSize;
        if (sizeInBytes >= 1024 * 1024) {
            formattedSize = String.format(Locale.US, "%.1f MB", sizeInBytes / (1024.0 * 1024.0));
        } else {
            formattedSize = String.format(Locale.US, "%d KB", sizeInBytes / 1024);
        }

        String metadata = formattedDate + " • " + formattedSize;

        // Append the aspect ratio so the popup matches the card's format tag.
        try {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            retriever.setDataSource(file.getAbsolutePath());
            String w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            retriever.release();
            if (w != null && h != null) {
                String ratio = VideoFormatHelper.describeRatio(
                        Integer.parseInt(w), Integer.parseInt(h));
                if (ratio != null) {
                    metadata += " • " + ratio;
                }
            }
        } catch (Exception ignored) {}

        tvMetadata.setText(metadata);
    }

    private static String formatDuration(long durationMs) {
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
