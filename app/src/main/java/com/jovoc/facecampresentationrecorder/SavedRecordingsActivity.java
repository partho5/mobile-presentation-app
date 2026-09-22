package com.jovoc.facecampresentationrecorder;

import android.media.MediaMetadataRetriever;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.jovoc.facecampresentationrecorder.adapter.RecordingsAdapter;
import com.jovoc.facecampresentationrecorder.model.RecordingItem;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class SavedRecordingsActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private LinearLayout emptyStateContainer;
    private TextView tvCountHeader;
    private RecordingsAdapter adapter;
    private final List<RecordingItem> recordingList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_saved_recordings);

        ImageButton btnBack = findViewById(R.id.btn_back);
        tvCountHeader = findViewById(R.id.tv_count_header);
        recyclerView = findViewById(R.id.recycler_recordings);
        emptyStateContainer = findViewById(R.id.empty_state_container);
        MaterialButton btnGoMain = findViewById(R.id.btn_go_main);

        btnBack.setOnClickListener(v -> finish());
        btnGoMain.setOnClickListener(v -> finish());

        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        loadRecordings();
    }

    private void loadRecordings() {
        recordingList.clear();

        String appName = getString(R.string.app_name);
        File dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
        File appDir = new File(dcimDir, appName);

        if (appDir.exists() && appDir.isDirectory()) {
            File[] files = appDir.listFiles((dir, name) -> name.toLowerCase(Locale.US).endsWith(".mp4"));
            if (files != null && files.length > 0) {
                // Sort by last modified descending (newest first)
                Arrays.sort(files, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));

                SimpleDateFormat dateFormat = new SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.US);
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();

                for (File file : files) {
                    long durationMs = 0;
                    String formattedDuration = "00:00";
                    try {
                        retriever.setDataSource(file.getAbsolutePath());
                        String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                        if (durationStr != null) {
                            durationMs = Long.parseLong(durationStr);
                            formattedDuration = formatDuration(durationMs);
                        }
                    } catch (Exception ignored) {}

                    String formattedDate = dateFormat.format(new Date(file.lastModified()));

                    recordingList.add(new RecordingItem(
                            file,
                            file.getName(),
                            file.getAbsolutePath(),
                            durationMs,
                            formattedDuration,
                            formattedDate
                    ));
                }

                try {
                    retriever.release();
                } catch (Exception ignored) {}
            }
        }

        updateUI();
    }

    private void updateUI() {
        int count = recordingList.size();
        tvCountHeader.setText(count == 1 ? "1 video" : count + " videos");

        if (count == 0) {
            recyclerView.setVisibility(View.GONE);
            emptyStateContainer.setVisibility(View.VISIBLE);
        } else {
            emptyStateContainer.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);

            adapter = new RecordingsAdapter(this, recordingList, remainingCount -> {
                tvCountHeader.setText(remainingCount == 1 ? "1 video" : remainingCount + " videos");
                if (remainingCount == 0) {
                    recyclerView.setVisibility(View.GONE);
                    emptyStateContainer.setVisibility(View.VISIBLE);
                }
            });
            recyclerView.setAdapter(adapter);
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
