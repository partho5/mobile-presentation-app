package com.jovoc.facecampresentationrecorder.adapter;

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.graphics.drawable.Drawable;
import android.provider.MediaStore;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.card.MaterialCardView;
import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.auth.SessionManager;
import com.jovoc.facecampresentationrecorder.db.PublishJob;
import com.jovoc.facecampresentationrecorder.model.RecordingItem;
import com.jovoc.facecampresentationrecorder.publish.PublishLabels;
import com.jovoc.facecampresentationrecorder.publish.AutoPublishUndo;
import com.jovoc.facecampresentationrecorder.publish.PublishManager;
import com.jovoc.facecampresentationrecorder.ui.PublishActivity;
import com.jovoc.facecampresentationrecorder.ui.VideoPlayerDialog;
import com.jovoc.facecampresentationrecorder.util.VideoGroupStore;
import com.jovoc.facecampresentationrecorder.util.VideoRenameHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class RecordingsAdapter extends RecyclerView.Adapter<RecordingsAdapter.RecordingViewHolder> {

    public interface OnListChangeListener {
        void onListChanged(int remainingCount);
    }

    /** Lets the host activity mirror selection state in its toolbar. */
    public interface OnSelectionChangeListener {
        void onSelectionChanged(boolean inSelectionMode, int selectedCount);
    }

    private final Context context;
    private final List<RecordingItem> itemList;
    private final OnListChangeListener listChangeListener;
    private OnSelectionChangeListener selectionChangeListener;

    private boolean selectionMode = false;

    public RecordingsAdapter(Context context, List<RecordingItem> itemList, OnListChangeListener listChangeListener) {
        this.context = context;
        this.itemList = itemList;
        this.listChangeListener = listChangeListener;
    }

    public void setOnSelectionChangeListener(OnSelectionChangeListener listener) {
        this.selectionChangeListener = listener;
    }

    @NonNull
    @Override
    public RecordingViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_recording_card, parent, false);
        return new RecordingViewHolder(view);
    }

    /** Payload for a redraw of the publish status line only (the undo countdown's tick). */
    public static final Object PAYLOAD_PUBLISH_STATUS = new Object();

    @Override
    public void onBindViewHolder(@NonNull RecordingViewHolder holder, int position,
                                 @NonNull java.util.List<Object> payloads) {
        if (!payloads.isEmpty() && payloads.contains(PAYLOAD_PUBLISH_STATUS)
                && position < itemList.size()) {
            bindPublishStatus(holder, itemList.get(position));
            return;
        }
        super.onBindViewHolder(holder, position, payloads);
    }

    @Override
    public void onBindViewHolder(@NonNull RecordingViewHolder holder, int position) {
        RecordingItem item = itemList.get(position);

        boolean ready = item.isReady();

        holder.tvFilename.setText(item.getFileName());
        holder.tvDuration.setText(item.getFormattedDuration());
        holder.tvFormatTag.setText(item.getFormatTag());

        // An unfinished or broken file gets a status line instead of a date, so the
        // card never looks like a normal recording that simply refuses to play.
        holder.tvStatus.setVisibility(ready ? View.GONE : View.VISIBLE);
        holder.tvRecordedTime.setVisibility(ready ? View.VISIBLE : View.GONE);
        if (ready) {
            holder.tvRecordedTime.setText(item.getFormattedDate());
        } else {
            holder.tvStatus.setText(item.getStatusLabel());
            holder.tvStatus.setTextColor(
                    item.getStatus() == RecordingItem.Status.PROCESSING ? 0xFF22D3EE : 0xFFFFB347);
        }

        holder.groupAccent.setBackgroundColor(item.getAccentColor());

        bindPublishStatus(holder, item);

        holder.processingOverlay.setVisibility(
                item.getStatus() == RecordingItem.Status.PROCESSING ? View.VISIBLE : View.GONE);
        holder.card.setAlpha(ready ? 1f : 0.6f);

        if (ready) {
            Glide.with(context)
                    .load(item.getFile())
                    .centerCrop()
                    .placeholder(R.drawable.ic_video_library)
                    .into(holder.ivThumbnail);
        } else {
            Glide.with(context).clear(holder.ivThumbnail);
            holder.ivThumbnail.setImageResource(R.drawable.ic_video_library);
        }

        // Selection visuals. Only ready files can be selected, so a pending crop is
        // never swept into a bulk delete.
        boolean selected = ready && selectionMode && item.isSelected();
        holder.selectionOverlay.setVisibility(selected ? View.VISIBLE : View.GONE);
        holder.card.setStrokeColor(selected ? 0xFF06B6D4 : 0x00000000);
        holder.btnMenu.setVisibility(ready && !selectionMode ? View.VISIBLE : View.GONE);

        // Card tap: toggles selection while selecting, otherwise opens the player popup.
        holder.itemView.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            RecordingItem tapped = itemList.get(pos);
            if (!tapped.isReady()) {
                Toast.makeText(context, tapped.getStatusLabel(), Toast.LENGTH_SHORT).show();
                return;
            }
            if (selectionMode) {
                toggleSelection(pos);
            } else {
                openVideo(tapped);
            }
        });

        holder.itemView.setOnLongClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return false;
            if (!itemList.get(pos).isReady()) return true;
            if (!selectionMode) {
                enterSelectionMode(pos);
            } else {
                toggleSelection(pos);
            }
            return true;
        });

        // 3-dot menu: its own menu, independent of the card tap.
        holder.btnMenu.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION && itemList.get(pos).isReady()) {
                showCardMenu(v, pos);
            }
        });
    }

    @Override
    public int getItemCount() {
        return itemList.size();
    }

    // --- Publish state ---

    /** Status line (and upload bar) for a recording that has been sent to channels. */
    private void bindPublishStatus(RecordingViewHolder holder, RecordingItem item) {
        PublishJob job = item.isReady() ? item.getPublishJob() : null;
        String text = PublishLabels.status(context, job);
        if (text == null) {
            holder.tvPublishStatus.setVisibility(View.GONE);
            holder.pbPublish.setVisibility(View.GONE);
            return;
        }
        holder.tvPublishStatus.setVisibility(View.VISIBLE);
        holder.tvPublishStatus.setText(text);
        holder.tvPublishStatus.setTextColor(PublishLabels.color(job));
        // A live post's status line opens the post; recycled holders must not keep an old link.
        // Same for a failed auto-publish that was never set up: the line opens the setup page.
        String liveUrl = PublishLabels.liveUrl(job);
        final String linkUrl = liveUrl != null ? liveUrl : PublishLabels.setupUrl(job);
        // Inside the undo window of an auto-publish the line itself is the Cancel: one tap, no
        // confirmation (nothing has been uploaded yet), the same cancel as the menu entry.
        final boolean undo = AutoPublishUndo.secondsLeft(job, System.currentTimeMillis()) > 0;
        float density = context.getResources().getDisplayMetrics().density;
        holder.tvPublishStatus.setMinHeight(undo ? (int) (40 * density) : 0);
        holder.tvPublishStatus.setGravity(undo ? android.view.Gravity.CENTER_VERTICAL : android.view.Gravity.TOP);
        if (undo) {
            holder.tvPublishStatus.setPaintFlags(
                    holder.tvPublishStatus.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
            holder.tvPublishStatus.setOnClickListener(v -> PublishManager.get(context).cancel(job.id));
        } else if (linkUrl != null) {
            holder.tvPublishStatus.setPaintFlags(
                    holder.tvPublishStatus.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
            holder.tvPublishStatus.setOnClickListener(v -> openLivePost(linkUrl));
        } else {
            holder.tvPublishStatus.setPaintFlags(
                    holder.tvPublishStatus.getPaintFlags() & ~android.graphics.Paint.UNDERLINE_TEXT_FLAG);
            holder.tvPublishStatus.setOnClickListener(null);
            holder.tvPublishStatus.setClickable(false);
        }
        boolean uploading = PublishJob.UPLOADING.equals(job.status);
        holder.pbPublish.setVisibility(uploading ? View.VISIBLE : View.GONE);
        if (uploading) holder.pbPublish.setProgress(job.progress);
    }

    private void openLivePost(String url) {
        try {
            context.startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Toast.makeText(context, R.string.publish_open_link_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void startPublish(RecordingItem item) {
        File file = item.getFile();
        if (file == null || !file.exists()) {
            Toast.makeText(context, "File no longer exists", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent publish = new Intent(context, PublishActivity.class)
                .putExtra(PublishActivity.EXTRA_FILE_PATH, file.getAbsolutePath());
        if (SessionManager.get(context).hasSession()) {
            context.startActivity(publish);
        } else {
            // Publishing needs an account; after signing in the user lands on Publish for this recording.
            SessionManager.openSignIn(context, publish);
        }
    }

    private void confirmCancelPublish(PublishJob job) {
        new AlertDialog.Builder(context)
                .setMessage(R.string.publish_cancel_confirm)
                .setPositiveButton(R.string.publish_cancel_yes,
                        (d, w) -> PublishManager.get(context).cancel(job.id))
                .setNegativeButton(R.string.publish_cancel_no, null)
                .show();
    }

    // --- Overflow menu ---

    private void showCardMenu(View anchor, int position) {
        RecordingItem item = itemList.get(position);
        PublishJob job = item.getPublishJob();

        // Themed wrapper pins the popup dark; the app theme is DayNight but every
        // screen here is hardcoded dark, so the system default would go white.
        Context themedContext = new ContextThemeWrapper(context, R.style.ThemeOverlay_PopupMenu_Dark);
        PopupMenu popup = new PopupMenu(themedContext, anchor);
        popup.getMenuInflater().inflate(R.menu.recording_card_menu, popup.getMenu());
        popup.setForceShowIcon(true);
        tintMenuIcons(popup.getMenu());

        // Publish entries follow the recording's publish state: start, again, retry or cancel.
        boolean running = job != null && job.isActive();
        boolean failed = job != null
                && (PublishJob.FAILED.equals(job.status) || job.isRemoteFailed());
        Menu menu = popup.getMenu();
        menu.findItem(R.id.action_publish).setVisible(!running);
        menu.findItem(R.id.action_publish).setTitle(
                job != null && !running ? R.string.publish_menu_again : R.string.publish_menu);
        menu.findItem(R.id.action_publish_retry).setVisible(failed);
        menu.findItem(R.id.action_publish_cancel).setVisible(running);
        String setupUrl = PublishLabels.setupUrl(job);
        menu.findItem(R.id.action_publish_set_up).setVisible(setupUrl != null);

        popup.setOnMenuItemClickListener(menuItem -> {
            int id = menuItem.getItemId();
            if (id == R.id.action_play) {
                openVideo(item);
                return true;
            } else if (id == R.id.action_publish) {
                startPublish(item);
                return true;
            } else if (id == R.id.action_publish_retry) {
                if (job != null) PublishManager.get(context).retry(job.id);
                return true;
            } else if (id == R.id.action_publish_set_up) {
                if (setupUrl != null) openLivePost(setupUrl);
                return true;
            } else if (id == R.id.action_publish_cancel) {
                if (job != null) confirmCancelPublish(job);
                return true;
            } else if (id == R.id.action_rename) {
                renameVideo(item);
                return true;
            } else if (id == R.id.action_share) {
                shareVideo(item);
                return true;
            } else if (id == R.id.action_select) {
                int pos = itemList.indexOf(item);
                if (pos >= 0) enterSelectionMode(pos);
                return true;
            } else if (id == R.id.action_delete) {
                confirmDeleteSingle(item);
                return true;
            }
            return false;
        });

        popup.show();
    }

    /** Tints every icon so the menu reads as one set, with Delete called out in red. Publish keeps its full-colour icon. */
    private void tintMenuIcons(Menu menu) {
        for (int i = 0; i < menu.size(); i++) {
            MenuItem menuItem = menu.getItem(i);
            Drawable icon = menuItem.getIcon();
            if (icon == null || menuItem.getItemId() == R.id.action_publish) continue;

            icon = DrawableCompat.wrap(icon.mutate());
            DrawableCompat.setTint(icon,
                    menuItem.getItemId() == R.id.action_delete ? 0xFFFF6B6B : 0xFF22D3EE);
            menuItem.setIcon(icon);
        }
    }

    private void renameVideo(RecordingItem item) {
        VideoRenameHelper.promptRename(context, item.getFile(),
                new VideoRenameHelper.RenameCallback() {
                    @Override
                    public void onRenamed(File oldFile, File newFile) {
                        PublishManager.get(context).onRenamed(oldFile, newFile);
                        item.setFile(newFile);
                        int pos = itemList.indexOf(item);
                        if (pos >= 0) notifyItemChanged(pos);
                    }
                });
    }

    private void shareVideo(RecordingItem item) {
        try {
            Uri uri = FileProvider.getUriForFile(
                    context,
                    context.getPackageName() + ".fileprovider",
                    item.getFile());
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("video/mp4");
            shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(Intent.createChooser(shareIntent, "Share Video Recording"));
        } catch (Exception e) {
            Toast.makeText(context, "Unable to share video", Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmDeleteSingle(RecordingItem item) {
        new AlertDialog.Builder(context)
                .setTitle("Delete Recording")
                .setMessage("Delete \"" + item.getFileName() + "\"?")
                .setPositiveButton("Delete", (d, w) -> {
                    if (deleteFile(item)) {
                        int pos = itemList.indexOf(item);
                        if (pos >= 0) {
                            itemList.remove(pos);
                            notifyItemRemoved(pos);
                            notifyItemRangeChanged(pos, itemList.size());
                        }
                        notifyListChanged();
                        Toast.makeText(context, "Video deleted", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(context, "Failed to delete video", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // --- Selection mode ---

    public boolean isSelectionMode() {
        return selectionMode;
    }

    private void enterSelectionMode(int position) {
        selectionMode = true;
        itemList.get(position).setSelected(true);
        notifyDataSetChanged();
        notifySelectionChanged();
    }

    public void exitSelectionMode() {
        selectionMode = false;
        for (RecordingItem item : itemList) {
            item.setSelected(false);
        }
        notifyDataSetChanged();
        notifySelectionChanged();
    }

    private void toggleSelection(int position) {
        RecordingItem item = itemList.get(position);
        item.setSelected(!item.isSelected());
        notifyItemChanged(position);

        // Deselecting the last item leaves selection mode, so the user is never
        // stranded in an empty selection with no obvious way out.
        if (getSelectedCount() == 0) {
            exitSelectionMode();
        } else {
            notifySelectionChanged();
        }
    }

    public void selectAll() {
        selectionMode = true;
        for (RecordingItem item : itemList) {
            item.setSelected(item.isReady());
        }
        notifyDataSetChanged();
        notifySelectionChanged();
    }

    public int getSelectedCount() {
        int count = 0;
        for (RecordingItem item : itemList) {
            if (item.isSelected()) count++;
        }
        return count;
    }

    /** Deletes every selected recording, then leaves selection mode. */
    public void deleteSelected() {
        List<RecordingItem> toDelete = new ArrayList<>();
        for (RecordingItem item : itemList) {
            if (item.isSelected()) toDelete.add(item);
        }
        if (toDelete.isEmpty()) return;

        int deleted = 0;
        int failed = 0;
        for (RecordingItem item : toDelete) {
            if (deleteFile(item)) {
                itemList.remove(item);
                deleted++;
            } else {
                item.setSelected(false);
                failed++;
            }
        }

        selectionMode = false;
        notifyDataSetChanged();
        notifySelectionChanged();
        notifyListChanged();

        String message = failed == 0
                ? (deleted == 1 ? "1 video deleted" : deleted + " videos deleted")
                : deleted + " deleted, " + failed + " failed";
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }

    private void notifySelectionChanged() {
        if (selectionChangeListener != null) {
            selectionChangeListener.onSelectionChanged(selectionMode, getSelectedCount());
        }
    }

    private void notifyListChanged() {
        if (listChangeListener != null) {
            listChangeListener.onListChanged(itemList.size());
        }
    }

    // --- File operations ---

    /** Removes the file from disk and from MediaStore. */
    private boolean deleteFile(RecordingItem item) {
        File file = item.getFile();
        if (file == null) return false;

        boolean removed = !file.exists() || file.delete();
        if (!removed) return false;
        VideoGroupStore.remove(context, file.getAbsolutePath());

        try {
            ContentResolver resolver = context.getContentResolver();
            resolver.delete(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    MediaStore.Video.Media.DATA + "=?",
                    new String[]{file.getAbsolutePath()});
        } catch (Exception ignored) {}

        MediaScannerConnection.scanFile(context, new String[]{file.getAbsolutePath()}, null, null);
        return true;
    }

    private void openVideo(RecordingItem item) {
        try {
            File file = item.getFile();
            if (file == null || !file.exists()) {
                Toast.makeText(context, "File no longer exists", Toast.LENGTH_SHORT).show();
                return;
            }

            VideoPlayerDialog.show(context, file, new VideoPlayerDialog.OnVideoActionListener() {
                @Override
                public void onVideoRenamed(File oldFile, File newFile) {
                    PublishManager.get(context).onRenamed(oldFile, newFile);
                    item.setFile(newFile);
                    int pos = itemList.indexOf(item);
                    if (pos >= 0) {
                        notifyItemChanged(pos);
                    }
                }

                @Override
                public void onVideoDeleted(File deletedFile) {
                    int pos = itemList.indexOf(item);
                    if (pos >= 0 && pos < itemList.size()) {
                        itemList.remove(pos);
                        notifyItemRemoved(pos);
                        notifyItemRangeChanged(pos, itemList.size());
                    }
                    notifyListChanged();
                }
            });
        } catch (Exception e) {
        }
    }

    static class RecordingViewHolder extends RecyclerView.ViewHolder {
        MaterialCardView card;
        ImageView ivThumbnail;
        TextView tvDuration;
        TextView tvFilename;
        TextView tvRecordedTime;
        TextView tvFormatTag;
        TextView tvStatus;
        TextView tvPublishStatus;
        ProgressBar pbPublish;
        ImageButton btnMenu;
        View selectionOverlay;
        View processingOverlay;
        View groupAccent;

        public RecordingViewHolder(@NonNull View itemView) {
            super(itemView);
            card = itemView.findViewById(R.id.card_recording);
            ivThumbnail = itemView.findViewById(R.id.iv_thumbnail);
            tvDuration = itemView.findViewById(R.id.tv_duration);
            tvFilename = itemView.findViewById(R.id.tv_filename);
            tvRecordedTime = itemView.findViewById(R.id.tv_recorded_time);
            tvFormatTag = itemView.findViewById(R.id.tv_format_tag);
            tvStatus = itemView.findViewById(R.id.tv_status);
            tvPublishStatus = itemView.findViewById(R.id.tv_publish_status);
            pbPublish = itemView.findViewById(R.id.pb_publish);
            btnMenu = itemView.findViewById(R.id.btn_menu);
            selectionOverlay = itemView.findViewById(R.id.selection_overlay);
            processingOverlay = itemView.findViewById(R.id.processing_overlay);
            groupAccent = itemView.findViewById(R.id.group_accent);
        }
    }
}
