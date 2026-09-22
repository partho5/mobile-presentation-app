package com.jovoc.facecampresentationrecorder.adapter;

import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.provider.MediaStore;
import android.text.InputType;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.model.RecordingItem;

import java.io.File;
import java.util.List;

public class RecordingsAdapter extends RecyclerView.Adapter<RecordingsAdapter.RecordingViewHolder> {

    public interface OnListChangeListener {
        void onListChanged(int remainingCount);
    }

    private final Context context;
    private final List<RecordingItem> itemList;
    private final OnListChangeListener listChangeListener;

    public RecordingsAdapter(Context context, List<RecordingItem> itemList, OnListChangeListener listChangeListener) {
        this.context = context;
        this.itemList = itemList;
        this.listChangeListener = listChangeListener;
    }

    @NonNull
    @Override
    public RecordingViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_recording_card, parent, false);
        return new RecordingViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecordingViewHolder holder, int position) {
        RecordingItem item = itemList.get(position);

        holder.tvFilename.setText(item.getFileName());
        holder.tvRecordedTime.setText(item.getFormattedDate());
        holder.tvDuration.setText(item.getFormattedDuration());

        // Load thumbnail using Glide
        Glide.with(context)
                .load(item.getFile())
                .centerCrop()
                .placeholder(R.drawable.ic_video_library)
                .into(holder.ivThumbnail);

        // Click card -> Open video using default/chosen player via FileProvider
        holder.itemView.setOnClickListener(v -> openVideo(item));

        // Click 3-dot overflow menu -> Popup Menu (Rename / Delete)
        holder.btnMenu.setOnClickListener(v -> showPopupMenu(v, item, holder.getAdapterPosition()));
    }

    @Override
    public int getItemCount() {
        return itemList.size();
    }

    private void openVideo(RecordingItem item) {
        try {
            File file = item.getFile();
            if (file == null || !file.exists()) {
                Toast.makeText(context, "File no longer exists", Toast.LENGTH_SHORT).show();
                return;
            }
            Uri contentUri = FileProvider.getUriForFile(
                    context,
                    context.getPackageName() + ".fileprovider",
                    file
            );

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(contentUri, "video/mp4");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(Intent.createChooser(intent, "Open Recording With"));
        } catch (Exception e) {
            Toast.makeText(context, "Unable to open video: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void showPopupMenu(View view, RecordingItem item, int position) {
        PopupMenu popup = new PopupMenu(context, view);
        android.view.MenuItem renameItem = popup.getMenu().add(0, 1, 0, "Rename");
        renameItem.setIcon(R.drawable.ic_edit);
        android.view.MenuItem deleteItem = popup.getMenu().add(0, 2, 1, "Delete");
        deleteItem.setIcon(R.drawable.ic_delete);

        try {
            java.lang.reflect.Field popupField = PopupMenu.class.getDeclaredField("mPopup");
            popupField.setAccessible(true);
            Object menuPopupHelper = popupField.get(popup);
            Class<?> classPopupHelper = Class.forName(menuPopupHelper.getClass().getName());
            java.lang.reflect.Method setForceIcons = classPopupHelper.getMethod("setForceShowIcon", boolean.class);
            setForceIcons.invoke(menuPopupHelper, true);
        } catch (Exception ignored) {
        }

        popup.setOnMenuItemClickListener(menuItem -> {
            if (menuItem.getItemId() == 1) {
                showRenameDialog(item, position);
                return true;
            } else if (menuItem.getItemId() == 2) {
                showDeleteConfirmDialog(item, position);
                return true;
            }
            return false;
        });
        popup.show();
    }

    private void showRenameDialog(RecordingItem item, int position) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Rename Recording");

        final EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_TEXT);

        String currentName = item.getFileName();
        if (currentName.endsWith(".mp4")) {
            currentName = currentName.substring(0, currentName.length() - 4);
        }
        input.setText(currentName);
        input.setSelection(currentName.length());

        int padding = (int) (16 * context.getResources().getDisplayMetrics().density);
        builder.setView(input);
        input.setPadding(padding, padding, padding, padding);

        builder.setPositiveButton("Rename", (dialog, which) -> {
            String newName = input.getText().toString().trim();
            if (TextUtils.isEmpty(newName)) {
                Toast.makeText(context, "Filename cannot be empty", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!newName.endsWith(".mp4")) {
                newName += ".mp4";
            }

            File oldFile = item.getFile();
            File parentDir = oldFile.getParentFile();
            File newFile = new File(parentDir, newName);

            if (newFile.exists() && !newFile.equals(oldFile)) {
                Toast.makeText(context, "A file with this name already exists", Toast.LENGTH_SHORT).show();
                return;
            }

            boolean success = oldFile.renameTo(newFile);
            if (success) {
                // Update MediaStore & item
                MediaScannerConnection.scanFile(context, new String[]{oldFile.getAbsolutePath(), newFile.getAbsolutePath()}, null, null);
                item.setFile(newFile);
                notifyItemChanged(position);
                Toast.makeText(context, "Renamed successfully", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(context, "Failed to rename file", Toast.LENGTH_SHORT).show();
            }
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void showDeleteConfirmDialog(RecordingItem item, int position) {
        new AlertDialog.Builder(context)
                .setTitle("Delete Recording")
                .setMessage("Are you sure you want to delete \"" + item.getFileName() + "\"?")
                .setPositiveButton("Delete", (dialog, which) -> deleteItem(item, position))
                .setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss())
                .show();
    }

    private void deleteItem(RecordingItem item, int position) {
        File file = item.getFile();
        boolean deleted = false;
        if (file != null && file.exists()) {
            deleted = file.delete();
        }

        if (deleted || (file != null && !file.exists())) {
            // Remove from MediaStore
            try {
                ContentResolver resolver = context.getContentResolver();
                resolver.delete(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        MediaStore.Video.Media.DATA + "=?",
                        new String[]{file.getAbsolutePath()});
            } catch (Exception ignored) {}

            MediaScannerConnection.scanFile(context, new String[]{file.getAbsolutePath()}, null, null);

            if (position >= 0 && position < itemList.size()) {
                itemList.remove(position);
                notifyItemRemoved(position);
                notifyItemRangeChanged(position, itemList.size());
            }

            if (listChangeListener != null) {
                listChangeListener.onListChanged(itemList.size());
            }
            Toast.makeText(context, "Recording deleted", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(context, "Failed to delete file", Toast.LENGTH_SHORT).show();
        }
    }

    static class RecordingViewHolder extends RecyclerView.ViewHolder {
        ImageView ivThumbnail;
        TextView tvDuration;
        TextView tvFilename;
        TextView tvRecordedTime;
        ImageButton btnMenu;

        public RecordingViewHolder(@NonNull View itemView) {
            super(itemView);
            ivThumbnail = itemView.findViewById(R.id.iv_thumbnail);
            tvDuration = itemView.findViewById(R.id.tv_duration);
            tvFilename = itemView.findViewById(R.id.tv_filename);
            tvRecordedTime = itemView.findViewById(R.id.tv_recorded_time);
            btnMenu = itemView.findViewById(R.id.btn_menu);
        }
    }
}
