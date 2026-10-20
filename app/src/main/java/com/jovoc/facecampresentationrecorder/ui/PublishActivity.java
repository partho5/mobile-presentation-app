package com.jovoc.facecampresentationrecorder.ui;

import android.Manifest;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.pm.PackageManager;
import android.os.Build;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.bumptech.glide.Glide;
import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.auth.SessionManager;
import com.jovoc.facecampresentationrecorder.db.PublishJob;
import com.jovoc.facecampresentationrecorder.publish.AutoPublishInfo;
import com.jovoc.facecampresentationrecorder.publish.Channel;
import com.jovoc.facecampresentationrecorder.publish.PublishApi;
import com.jovoc.facecampresentationrecorder.publish.PublishException;
import com.jovoc.facecampresentationrecorder.publish.PublishLabels;
import com.jovoc.facecampresentationrecorder.publish.PublishManager;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Publish one saved recording: pick channels, write a caption, choose now or a time, see the
 * credit cost, confirm. Confirming only queues the job; the upload runs in the background.
 */
public class PublishActivity extends AppCompatActivity {

    public static final String EXTRA_FILE_PATH = "file_path";

    private static final long COST_DEBOUNCE_MS = 250L;
    private static final long MIN_LEAD_MS = 2 * 60_000L;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private PublishApi api;
    private File file;

    private ProgressBar progressChannels;
    private View channelsErrorBox;
    private Button btnChannelsRetry;
    private Button btnChannelsWeb;
    private boolean showingNoChannels;
    private TextView tvChannelsError;
    private LinearLayout channelsContainer;
    private EditText editCaption;
    private RadioGroup groupWhen;
    private View scheduleRow;
    private Button btnPickDate;
    private Button btnPickTime;
    private TextView tvScheduleError;
    private TextView tvCost;
    private Button btnPublish;

    /** Enabled channels by id, in list order; {@link #selected} holds the ticked ids. */
    private final Map<String, Channel> channelsById = new LinkedHashMap<>();
    private final List<String> selected = new ArrayList<>();
    private final Calendar scheduleAt = Calendar.getInstance();

    private static final int COST_NONE = 0, COST_LOADING = 1, COST_OK = 2, COST_FAILED = 3;
    private int costState = COST_NONE;
    private int costAmount;
    private int costRequest;
    private boolean submitting;

    private final Runnable costTask = this::loadCost;

    private final ActivityResultLauncher<String> notificationPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                    granted -> queueJob()); // the upload runs either way; this only shows its notification

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_publish);
        SessionManager session = SessionManager.get(this);
        if (!session.hasSession()) {
            SessionManager.openSignIn(this, new Intent(getIntent()));
            finish();
            return;
        }
        String path = getIntent().getStringExtra(EXTRA_FILE_PATH);
        file = path == null ? null : new File(path);
        if (file == null || !file.isFile()) {
            Toast.makeText(this, R.string.pub_err_file, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        api = new PublishApi(session.repository());

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        ((ImageButton) findViewById(R.id.btn_back)).setOnClickListener(v -> finish());
        ((TextView) findViewById(R.id.tv_publish_file)).setText(file.getName());
        progressChannels = findViewById(R.id.progress_channels);
        channelsErrorBox = findViewById(R.id.channels_error_box);
        tvChannelsError = findViewById(R.id.tv_channels_error);
        channelsContainer = findViewById(R.id.channels_container);
        editCaption = findViewById(R.id.edit_caption);
        groupWhen = findViewById(R.id.group_when);
        scheduleRow = findViewById(R.id.schedule_row);
        btnPickDate = findViewById(R.id.btn_pick_date);
        btnPickTime = findViewById(R.id.btn_pick_time);
        tvScheduleError = findViewById(R.id.tv_schedule_error);
        tvCost = findViewById(R.id.tv_cost);
        btnPublish = findViewById(R.id.btn_publish);

        btnChannelsRetry = findViewById(R.id.btn_channels_retry);
        btnChannelsWeb = findViewById(R.id.btn_channels_web);
        btnChannelsRetry.setOnClickListener(v -> loadChannels());
        btnChannelsWeb.setOnClickListener(v -> WebLinks.open(this, WebLinks.channelsUrl()));
        tvCost.setOnClickListener(v -> {
            if (costState == COST_FAILED) scheduleCostLoad();
        });

        // A sensible default time: an hour from now, on a 5 minute mark.
        scheduleAt.add(Calendar.HOUR_OF_DAY, 1);
        scheduleAt.set(Calendar.MINUTE, (scheduleAt.get(Calendar.MINUTE) / 5 + 1) * 5);
        scheduleAt.set(Calendar.SECOND, 0);
        scheduleAt.set(Calendar.MILLISECOND, 0);
        updateScheduleButtons();

        groupWhen.setOnCheckedChangeListener((g, id) -> {
            scheduleRow.setVisibility(isScheduled() ? View.VISIBLE : View.GONE);
            refreshConfirm();
        });
        btnPickDate.setOnClickListener(v -> new DatePickerDialog(this, (picker, y, m, d) -> {
            scheduleAt.set(y, m, d);
            updateScheduleButtons();
        }, scheduleAt.get(Calendar.YEAR), scheduleAt.get(Calendar.MONTH),
                scheduleAt.get(Calendar.DAY_OF_MONTH)).show());
        btnPickTime.setOnClickListener(v -> new TimePickerDialog(this, (picker, h, min) -> {
            scheduleAt.set(Calendar.HOUR_OF_DAY, h);
            scheduleAt.set(Calendar.MINUTE, min);
            updateScheduleButtons();
        }, scheduleAt.get(Calendar.HOUR_OF_DAY), scheduleAt.get(Calendar.MINUTE),
                android.text.format.DateFormat.is24HourFormat(this)).show());
        btnPublish.setOnClickListener(v -> onPublishClicked());

        // One publish at a time per recording.
        io.execute(() -> {
            PublishJob latest = PublishManager.get(this).latestFor(file.getAbsolutePath());
            if (latest != null && latest.isActive()) {
                main.post(() -> {
                    Toast.makeText(this, R.string.publish_already_running, Toast.LENGTH_LONG).show();
                    finish();
                });
            }
        });
        loadChannels();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Back from the browser after connecting a channel: look again without a manual refresh.
        if (showingNoChannels && api != null) loadChannels();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
    }

    // ---- channels ----

    private void loadChannels() {
        showingNoChannels = false;
        progressChannels.setVisibility(View.VISIBLE);
        channelsErrorBox.setVisibility(View.GONE);
        channelsContainer.removeAllViews();
        io.execute(() -> {
            List<Channel> list = null;
            PublishException error = null;
            try {
                list = api.listChannels();
            } catch (PublishException e) {
                error = e;
            }
            final List<Channel> result = list;
            final PublishException err = error;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                progressChannels.setVisibility(View.GONE);
                if (err != null) {
                    showChannelsError(PublishLabels.reason(this, err), false);
                } else if (result.isEmpty()) {
                    showChannelsError(getString(R.string.publish_no_channels), true);
                } else {
                    showChannels(result);
                }
            });
        });
    }

    /** {@code noChannels}: the account has none, which is fixed on the website, not by retrying. */
    private void showChannelsError(String message, boolean noChannels) {
        tvChannelsError.setText(message);
        showingNoChannels = noChannels;
        btnChannelsWeb.setVisibility(noChannels ? View.VISIBLE : View.GONE);
        btnChannelsRetry.setText(noChannels ? R.string.publish_refresh : R.string.publish_retry);
        channelsErrorBox.setVisibility(View.VISIBLE);
        refreshConfirm();
    }

    private void showChannels(List<Channel> list) {
        channelsById.clear();
        selected.clear();
        channelsContainer.removeAllViews();
        int enabledCount = 0;
        for (Channel c : list) if (!c.disabled) enabledCount++;

        LayoutInflater inflater = LayoutInflater.from(this);
        for (Channel c : list) {
            View row = inflater.inflate(R.layout.item_channel, channelsContainer, false);
            ((TextView) row.findViewById(R.id.tv_channel_name)).setText(c.name);
            TextView provider = row.findViewById(R.id.tv_channel_provider);
            provider.setText(c.disabled ? getString(R.string.publish_channel_disabled)
                    : AutoPublishInfo.providerName(c.identifier));
            CheckBox check = row.findViewById(R.id.check_channel);
            ImageView avatar = row.findViewById(R.id.iv_channel);
            if (c.picture != null && !c.picture.isEmpty()) {
                Glide.with(this).load(c.picture).circleCrop().into(avatar);
            }
            if (c.disabled) {
                row.setAlpha(0.45f);
                row.setEnabled(false);
            } else {
                channelsById.put(c.id, c);
                row.setOnClickListener(v -> {
                    boolean on = !check.isChecked();
                    check.setChecked(on);
                    if (on) selected.add(c.id);
                    else selected.remove(c.id);
                    onSelectionChanged();
                });
                if (enabledCount == 1) { // nothing to choose between
                    check.setChecked(true);
                    selected.add(c.id);
                }
            }
            channelsContainer.addView(row);
        }
        onSelectionChanged();
    }

    private void onSelectionChanged() {
        scheduleCostLoad();
        refreshConfirm();
    }

    // ---- cost ----

    private void scheduleCostLoad() {
        main.removeCallbacks(costTask);
        costRequest++;
        if (selected.isEmpty()) {
            costState = COST_NONE;
            refreshConfirm();
            return;
        }
        costState = COST_LOADING;
        refreshConfirm();
        main.postDelayed(costTask, COST_DEBOUNCE_MS);
    }

    private void loadCost() {
        final int request = costRequest;
        final List<String> ids = new ArrayList<>(selected);
        io.execute(() -> {
            int amount = 0;
            boolean ok = true;
            try {
                amount = api.chargePreview(ids);
            } catch (PublishException e) {
                ok = false;
            }
            final int a = amount;
            final boolean success = ok;
            main.post(() -> {
                if (request != costRequest || isFinishing() || isDestroyed()) return; // selection moved on
                costState = success ? COST_OK : COST_FAILED;
                costAmount = a;
                refreshConfirm();
            });
        });
    }

    private String costText() {
        return costAmount == 1 ? getString(R.string.publish_cost_one_credit)
                : getString(R.string.publish_cost_credits, costAmount);
    }

    // ---- schedule ----

    private boolean isScheduled() {
        return groupWhen.getCheckedRadioButtonId() == R.id.radio_schedule;
    }

    private boolean scheduleIsValid() {
        return !isScheduled() || scheduleAt.getTimeInMillis() >= System.currentTimeMillis() + MIN_LEAD_MS;
    }

    private void updateScheduleButtons() {
        Date d = scheduleAt.getTime();
        btnPickDate.setText(DateFormat.getDateInstance(DateFormat.MEDIUM).format(d));
        btnPickTime.setText(DateFormat.getTimeInstance(DateFormat.SHORT).format(d));
        refreshConfirm();
    }

    // ---- confirm ----

    private void refreshConfirm() {
        if (btnPublish == null) return;
        int n = selected.size();
        boolean scheduleOk = scheduleIsValid();
        tvScheduleError.setVisibility(isScheduled() && !scheduleOk ? View.VISIBLE : View.GONE);

        switch (costState) {
            case COST_LOADING:
                tvCost.setVisibility(View.VISIBLE);
                tvCost.setText(R.string.publish_cost_loading);
                break;
            case COST_FAILED:
                tvCost.setVisibility(View.VISIBLE);
                tvCost.setText(R.string.publish_cost_unavailable);
                break;
            case COST_OK:
                // Free actions show no cost.
                tvCost.setVisibility(costAmount > 0 ? View.VISIBLE : View.GONE);
                tvCost.setText(costText());
                break;
            default:
                tvCost.setVisibility(View.GONE);
        }

        String label;
        if (n == 0) {
            label = getString(R.string.publish_confirm_none);
        } else if (costState == COST_OK && costAmount > 0) {
            label = n == 1 ? getString(R.string.publish_confirm_one_cost, costText())
                    : getString(R.string.publish_confirm_many_cost, n, costText());
        } else {
            label = n == 1 ? getString(R.string.publish_confirm_one)
                    : getString(R.string.publish_confirm_many, n);
        }
        btnPublish.setText(label);
        // The cost must be known before the user can confirm.
        btnPublish.setEnabled(n > 0 && costState == COST_OK && scheduleOk && !submitting);
    }

    private void onPublishClicked() {
        if (submitting || selected.isEmpty()) return;
        submitting = true;
        refreshConfirm();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            queueJob();
        }
    }

    private void queueJob() {
        List<Channel> chosen = new ArrayList<>();
        for (String id : selected) {
            Channel c = channelsById.get(id);
            if (c != null) chosen.add(c);
        }
        PublishJob job = new PublishJob();
        job.filePath = file.getAbsolutePath();
        job.fileName = file.getName();
        job.caption = editCaption.getText().toString().trim();
        job.channelsJson = Channel.toJson(chosen);
        job.mode = isScheduled() ? PublishJob.MODE_SCHEDULE : PublishJob.MODE_NOW;
        job.scheduleAt = isScheduled() ? scheduleAt.getTimeInMillis() : 0L;

        PublishManager.get(this).enqueue(job, () -> main.post(() -> {
            Toast.makeText(getApplicationContext(), R.string.publish_started, Toast.LENGTH_LONG).show();
            finish();
        }));
    }
}
