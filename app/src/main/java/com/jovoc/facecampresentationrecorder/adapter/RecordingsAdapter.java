package com.jovoc.facecampresentationrecorder.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.model.RecordingItem;
import com.jovoc.facecampresentationrecorder.ui.VideoPlayerDialog;

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

        // Click card or 3-dot menu -> Open custom VideoPlayerDialog component
        holder.itemView.setOnClickListener(v -> openVideo(item));
        holder.btnMenu.setOnClickListener(v -> openVideo(item));
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

            VideoPlayerDialog.show(context, file, new VideoPlayerDialog.OnVideoActionListener() {
                @Override
                public void onVideoRenamed(File oldFile, File newFile) {
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
                    if (listChangeListener != null) {
                        listChangeListener.onListChanged(itemList.size());
                    }
                }
            });
        } catch (Exception e) {
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
