package com.jovoc.facecampresentationrecorder.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.core.content.FileProvider;

import com.google.android.material.button.MaterialButton;
import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.util.VideoFormatHelper;
import com.jovoc.facecampresentationrecorder.util.VideoRenameHelper;

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
            // Inset from the screen edges so the rounded corners and hairline
            // border are actually visible rather than running off the display.
            int dialogWidth = (int) (context.getResources().getDisplayMetrics().widthPixels * 0.92f);
            window.setLayout(dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.82f);
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

            // Hug the video's aspect ratio tightly.
            sizePlayerCard(dialog, context, mp.getVideoWidth(), mp.getVideoHeight());

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

        // Rename Action — the prompt, validation and MediaStore bookkeeping live
        // in VideoRenameHelper; this only saves and restores playback around it.
        btnRename.setOnClickListener(v -> {
            final int[] savedSeekPos = new int[1];
            final boolean[] shouldResume = new boolean[1];

            VideoRenameHelper.promptRename(context, currentFile[0],
                    new VideoRenameHelper.RenameCallback() {
                        @Override
                        public void onBeforeRename() {
                            // Release the open file handle, or the rename fails.
                            try {
                                savedSeekPos[0] = videoView.getCurrentPosition();
                                shouldResume[0] = videoView.isPlaying();
                                progressHandler.removeCallbacks(progressRunnable);
                                videoView.stopPlayback();
                            } catch (Exception ignored) {}
                        }

                        @Override
                        public void onRenamed(File oldFile, File newFile) {
                            currentFile[0] = newFile;
                            updateMetadataUI(currentFile[0], tvFileName, tvMetadata);

                            // Re-bind the VideoView to the new path and resume.
                            videoView.setVideoPath(newFile.getAbsolutePath());
                            videoView.setOnPreparedListener(mp -> {
                                int duration = mp.getDuration();
                                seekBar.setMax(duration);
                                tvTotalDuration.setText(formatDuration(duration));

                                sizePlayerCard(dialog, context, mp.getVideoWidth(), mp.getVideoHeight());

                                if (savedSeekPos[0] > 0) {
                                    videoView.seekTo(savedSeekPos[0]);
                                }

                                if (shouldResume[0]) {
                                    videoView.start();
                                    btnCenterPlayPause.setImageResource(R.drawable.ic_pause);
                                    btnBarPlayPause.setImageResource(R.drawable.ic_pause);
                                    progressHandler.post(progressRunnable);
                                } else {
                                    btnCenterPlayPause.setImageResource(R.drawable.ic_play);
                                    btnBarPlayPause.setImageResource(R.drawable.ic_play);
                                }
                            });

                            if (listener != null) {
                                listener.onVideoRenamed(oldFile, newFile);
                            }
                        }
                    });
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

    /** Resizes the player card so it hugs the video's aspect ratio. */
    private static void sizePlayerCard(Dialog dialog, Context context, int vWidth, int vHeight) {
        View cardContainer = dialog.findViewById(R.id.player_card_container);
        if (cardContainer == null || vWidth <= 0 || vHeight <= 0) return;

        float videoAspect = (float) vWidth / (float) vHeight;
        float density = context.getResources().getDisplayMetrics().density;
        int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        int maxCardWidth = (int) (screenWidth * 0.82f);
        int maxCardHeight = (int) (320 * density);

        int calcWidth, calcHeight;
        if (videoAspect < (float) maxCardWidth / maxCardHeight) {
            // Portrait / screen-record format (e.g. 9:16)
            calcHeight = maxCardHeight;
            calcWidth = (int) (maxCardHeight * videoAspect);
        } else {
            // Landscape format (e.g. 16:9)
            calcWidth = maxCardWidth;
            calcHeight = (int) (maxCardWidth / videoAspect);
        }

        ViewGroup.LayoutParams params = cardContainer.getLayoutParams();
        if (params != null) {
            params.width = Math.max(calcWidth, (int) (140 * density));
            params.height = calcHeight;
            cardContainer.setLayoutParams(params);
        }
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
