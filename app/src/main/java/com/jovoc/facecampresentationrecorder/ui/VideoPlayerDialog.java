package com.jovoc.facecampresentationrecorder.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.core.content.FileProvider;

import com.google.android.material.button.MaterialButton;
import com.jovoc.facecampresentationrecorder.R;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class VideoPlayerDialog {

    private static final String TAG = "VideoPlayerDialog";

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

        final Dialog dialog = new Dialog(context);
        dialog.setContentView(R.layout.dialog_video_player);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.75f);
        }

        // Holder for mutable file reference during rename
        final File[] currentFile = new File[]{ initialFile };

        // Views
        ImageButton btnClose = dialog.findViewById(R.id.btn_close_dialog);
        VideoView videoView = dialog.findViewById(R.id.player_video_view);
        ImageButton btnCenterPlayPause = dialog.findViewById(R.id.btn_center_play_pause);
        ImageButton btnBarPlayPause = dialog.findViewById(R.id.btn_bar_play_pause);
        SeekBar seekBar = dialog.findViewById(R.id.player_seek_bar);
        TextView tvCurrentTime = dialog.findViewById(R.id.tv_current_time);
        TextView tvTotalDuration = dialog.findViewById(R.id.tv_total_duration);
        TextView tvFileName = dialog.findViewById(R.id.tv_player_filename);
        TextView tvMetadata = dialog.findViewById(R.id.tv_player_metadata);
        ImageButton btnRename = dialog.findViewById(R.id.btn_rename_video);
        MaterialButton btnDelete = dialog.findViewById(R.id.btn_delete_video);
        MaterialButton btnShare = dialog.findViewById(R.id.btn_share_video);

        // Populate Metadata
        updateMetadataUI(currentFile[0], tvFileName, tvMetadata);

        // Progress Handler
        Handler progressHandler = new Handler(Looper.getMainLooper());
        Runnable progressRunnable = new Runnable() {
            @Override
            public void run() {
                if (videoView != null && videoView.isPlaying()) {
                    int currentPosition = videoView.getCurrentPosition();
                    seekBar.setProgress(currentPosition);
                    tvCurrentTime.setText(formatDuration(currentPosition));
                    progressHandler.postDelayed(this, 250);
                }
            }
        };

        // Video Setup
        videoView.setVideoPath(currentFile[0].getAbsolutePath());

        videoView.setOnPreparedListener(mp -> {
            int duration = mp.getDuration();
            seekBar.setMax(duration);
            tvTotalDuration.setText(formatDuration(duration));

            // Dynamically resize card container to hug video stream aspect ratio tightly
            View cardContainer = dialog.findViewById(R.id.player_card_container);
            int vWidth = mp.getVideoWidth();
            int vHeight = mp.getVideoHeight();
            if (cardContainer != null && vWidth > 0 && vHeight > 0) {
                float videoAspect = (float) vWidth / (float) vHeight;
                int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
                int maxCardWidth = (int) (screenWidth * 0.82f);
                int maxCardHeight = (int) (320 * context.getResources().getDisplayMetrics().density);

                int calcWidth, calcHeight;
                if (videoAspect < (float) maxCardWidth / maxCardHeight) {
                    // Portrait / Screen record format (e.g. 9:16)
                    calcHeight = maxCardHeight;
                    calcWidth = (int) (maxCardHeight * videoAspect);
                } else {
                    // Landscape format (e.g. 16:9)
                    calcWidth = maxCardWidth;
                    calcHeight = (int) (maxCardWidth / videoAspect);
                }

                ViewGroup.LayoutParams params = cardContainer.getLayoutParams();
                if (params != null) {
                    params.width = Math.max(calcWidth, (int) (140 * context.getResources().getDisplayMetrics().density));
                    params.height = calcHeight;
                    cardContainer.setLayoutParams(params);
                }
            }

            videoView.start();
            btnCenterPlayPause.setImageResource(R.drawable.ic_pause);
            btnBarPlayPause.setImageResource(R.drawable.ic_pause);
            progressHandler.post(progressRunnable);
        });

        videoView.setOnCompletionListener(mp -> {
            btnCenterPlayPause.setImageResource(R.drawable.ic_play);
            btnBarPlayPause.setImageResource(R.drawable.ic_play);
            seekBar.setProgress(0);
            tvCurrentTime.setText("00:00");
            progressHandler.removeCallbacks(progressRunnable);
        });

        // Controls Logic
        View.OnClickListener togglePlayPause = v -> {
            if (videoView.isPlaying()) {
                videoView.pause();
                btnCenterPlayPause.setImageResource(R.drawable.ic_play);
                btnBarPlayPause.setImageResource(R.drawable.ic_play);
                progressHandler.removeCallbacks(progressRunnable);
            } else {
                videoView.start();
                btnCenterPlayPause.setImageResource(R.drawable.ic_pause);
                btnBarPlayPause.setImageResource(R.drawable.ic_pause);
                progressHandler.post(progressRunnable);
            }
        };

        btnCenterPlayPause.setOnClickListener(togglePlayPause);
        btnBarPlayPause.setOnClickListener(togglePlayPause);

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) {
                    videoView.seekTo(progress);
                    tvCurrentTime.setText(formatDuration(progress));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                progressHandler.removeCallbacks(progressRunnable);
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                if (videoView.isPlaying()) {
                    progressHandler.post(progressRunnable);
                }
            }
        });

        // Close Dialog
        btnClose.setOnClickListener(v -> dialog.dismiss());

        // Rename Action
        btnRename.setOnClickListener(v -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            builder.setTitle("Rename Video");

            final EditText input = new EditText(context);
            input.setInputType(InputType.TYPE_CLASS_TEXT);

            String name = currentFile[0].getName();
            if (name.endsWith(".mp4")) {
                name = name.substring(0, name.length() - 4);
            }
            input.setText(name);
            input.setSelection(name.length());

            int padding = (int) (16 * context.getResources().getDisplayMetrics().density);
            builder.setView(input);
            input.setPadding(padding, padding, padding, padding);

            builder.setPositiveButton("Rename", (d, which) -> {
                String newName = input.getText().toString().trim();
                if (TextUtils.isEmpty(newName)) {
                    Toast.makeText(context, "Filename cannot be empty", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!newName.endsWith(".mp4")) {
                    newName += ".mp4";
                }

                File oldFile = currentFile[0];
                File parentDir = oldFile.getParentFile();
                File newFile = new File(parentDir, newName);

                if (newFile.exists() && !newFile.equals(oldFile)) {
                    Toast.makeText(context, "A file with this name already exists", Toast.LENGTH_SHORT).show();
                    return;
                }

                boolean success = oldFile.renameTo(newFile);
                if (success) {
                    MediaScannerConnection.scanFile(context, new String[]{oldFile.getAbsolutePath(), newFile.getAbsolutePath()}, null, null);
                    currentFile[0] = newFile;
                    updateMetadataUI(currentFile[0], tvFileName, tvMetadata);
                    if (listener != null) {
                        listener.onVideoRenamed(oldFile, newFile);
                    }
                    Toast.makeText(context, "Renamed successfully", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(context, "Failed to rename file", Toast.LENGTH_SHORT).show();
                }
            });

            builder.setNegativeButton("Cancel", (d, which) -> d.cancel());
            builder.show();
        });

        // Delete Action
        btnDelete.setOnClickListener(v -> {
            new AlertDialog.Builder(context)
                    .setTitle("Delete Recording")
                    .setMessage("Are you sure you want to delete \"" + currentFile[0].getName() + "\"?")
                    .setPositiveButton("Delete", (d, which) -> {
                        if (videoView.isPlaying()) {
                            videoView.stopPlayback();
                        }
                        File fileToDelete = currentFile[0];
                        boolean deleted = false;
                        if (fileToDelete.exists()) {
                            deleted = fileToDelete.delete();
                        }

                        if (deleted || !fileToDelete.exists()) {
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

        // Share Action
        btnShare.setOnClickListener(v -> {
            try {
                Uri uri = FileProvider.getUriForFile(
                        context,
                        context.getPackageName() + ".fileprovider",
                        currentFile[0]
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

        // Cleanup on dismiss
        dialog.setOnDismissListener(d -> {
            progressHandler.removeCallbacks(progressRunnable);
            if (videoView != null) {
                videoView.stopPlayback();
            }
        });

        dialog.show();
        return dialog;
    }

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

        tvMetadata.setText(formattedDate + " • " + formattedSize);
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
