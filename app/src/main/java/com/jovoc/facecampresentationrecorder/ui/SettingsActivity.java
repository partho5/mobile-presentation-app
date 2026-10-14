package com.jovoc.facecampresentationrecorder.ui;

import com.jovoc.facecampresentationrecorder.bgreplace.FaceBgReplaceTask;

import static com.jovoc.facecampresentationrecorder.util.RecordSettings.BG_PREF_NAME;
import static com.jovoc.facecampresentationrecorder.util.RecordSettings.KEY_AUTOCROP_1_1;
import static com.jovoc.facecampresentationrecorder.util.RecordSettings.KEY_AUTOCROP_4_5;
import static com.jovoc.facecampresentationrecorder.util.RecordSettings.KEY_AUTOCROP_9_16;
import static com.jovoc.facecampresentationrecorder.util.RecordSettings.KEY_AUTO_PUBLISH;
import static com.jovoc.facecampresentationrecorder.util.RecordSettings.KEY_AUTO_PUBLISH_UNDO_SECONDS;
import static com.jovoc.facecampresentationrecorder.util.RecordSettings.KEY_BG_ASSET_PATH;
import static com.jovoc.facecampresentationrecorder.util.RecordSettings.KEY_BG_REPLACE_ENABLED;
import static com.jovoc.facecampresentationrecorder.util.RecordSettings.KEY_COUNTDOWN_SECONDS;
import static com.jovoc.facecampresentationrecorder.util.RecordSettings.KEY_RECORD_AUDIO;
import static com.jovoc.facecampresentationrecorder.util.RecordSettings.PREF_NAME;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.auth.SessionManager;
import com.jovoc.facecampresentationrecorder.publish.AutoPublishInfo;
import com.jovoc.facecampresentationrecorder.publish.AutoPublishUndo;
import com.jovoc.facecampresentationrecorder.publish.PublishApi;
import com.jovoc.facecampresentationrecorder.publish.PublishException;
import com.jovoc.facecampresentationrecorder.util.AudioInputSelection;
import com.jovoc.facecampresentationrecorder.util.AudioInputs;
import com.jovoc.facecampresentationrecorder.util.RecordSettings;
import com.jovoc.facecampresentationrecorder.util.UserBackgrounds;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Recording settings: missing permissions, countdown, audio source, auto-crop ratios and the
 * AI face-cam background. Every change is written to SharedPreferences the moment it is made;
 * MainActivity re-reads them in onResume.
 */
public class SettingsActivity extends AppCompatActivity {

    private static final String TAG = "SettingsActivity";
    private static final int ACCENT = 0xFF22D3EE;

    private SharedPreferences prefs;
    private SharedPreferences bgPrefs;
    private AudioDeviceCallback audioDeviceCallback;

    private View permissionsSection;
    private LinearLayout permissionsContainer;
    private SettingsHelpView permissionsHelp;
    private ActivityResultLauncher<String> permissionLauncher;
    private String pendingPermission;

    /** Bundled "bg_samples/..." asset paths in display order (see {@link #BG_PRIORITY}). */
    private final List<String> builtInBackgrounds = new ArrayList<>();
    /** Selectable value of each card, in grid order: an asset path or an uploaded file's absolute path. */
    private final List<String> bgPaths = new ArrayList<>();
    private ActivityResultLauncher<PickVisualMediaRequest> photoPicker;
    private final ExecutorService importExecutor = Executors.newSingleThreadExecutor();
    private boolean importing;
    private final List<View> bgCards = new ArrayList<>();
    private final List<View> bgChecks = new ArrayList<>();

    private TextView autoPublishStatus;
    private final ExecutorService autoPublishExecutor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Bumped per load so a slow, older answer never overwrites a newer one. */
    private final AtomicInteger autoPublishLoad = new AtomicInteger();

    /** A permission this app needs that the user hasn't granted yet. */
    private static final class MissingPermission {
        final int labelRes;
        final String runtimePermission; // null for the "display over other apps" special access
        final SettingsHelp.Block help;

        MissingPermission(int labelRes, String runtimePermission, SettingsHelp.Block help) {
            this.labelRes = labelRes;
            this.runtimePermission = runtimePermission;
            this.help = help;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        ((ImageButton) findViewById(R.id.btn_back)).setOnClickListener(v -> finish());

        prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        bgPrefs = getSharedPreferences(BG_PREF_NAME, MODE_PRIVATE);

        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    String permission = pendingPermission;
                    pendingPermission = null;
                    if (!granted && permission != null
                            && !ActivityCompat.shouldShowRequestPermissionRationale(this, permission)) {
                        // Denied for good: the system dialog won't appear again, only app settings can help.
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", getPackageName(), null)));
                    }
                    refreshPermissionRows();
                });

        permissionsSection = findViewById(R.id.permissions_section);
        permissionsContainer = findViewById(R.id.permissions_container);
        permissionsHelp = findViewById(R.id.permissions_help);

        setupCountdown();
        setupAudio();
        setupAutoCrop();
        setupBackground();
        setupAutoPublish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Also covers returning from the system app-settings / overlay screens.
        refreshPermissionRows();
        // Also after signing in, or after setting the preset up on the web and coming back.
        loadAutoPublishStatus();
        forEachHelpView(findViewById(R.id.settings_content), SettingsHelpView::onHostResume);
    }

    @Override
    protected void onDestroy() {
        importExecutor.shutdown();
        autoPublishExecutor.shutdownNow();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        // Stops any help video; onDestroy is covered too since pause always precedes it.
        forEachHelpView(findViewById(R.id.settings_content), SettingsHelpView::onHostPause);
        super.onPause();
    }

    private static void forEachHelpView(View root, Consumer<SettingsHelpView> action) {
        if (root instanceof SettingsHelpView) {
            action.accept((SettingsHelpView) root);
        } else if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) forEachHelpView(group.getChildAt(i), action);
        }
    }

    // ---- recording options ----

    private void setupCountdown() {
        RadioGroup group = findViewById(R.id.rg_countdown);
        int seconds = prefs.getInt(KEY_COUNTDOWN_SECONDS, 3);
        group.check(seconds == 0 ? R.id.rb_countdown_0
                : seconds == 5 ? R.id.rb_countdown_5
                : seconds == 10 ? R.id.rb_countdown_10
                : R.id.rb_countdown_3);
        group.setOnCheckedChangeListener((g, id) -> {
            int value = id == R.id.rb_countdown_0 ? 0
                    : id == R.id.rb_countdown_5 ? 5
                    : id == R.id.rb_countdown_10 ? 10
                    : 3;
            prefs.edit().putInt(KEY_COUNTDOWN_SECONDS, value).apply();
        });
    }

    private void setupAudio() {
        RadioGroup group = findViewById(R.id.rg_audio);
        group.check(prefs.getBoolean(KEY_RECORD_AUDIO, true) ? R.id.rb_audio_mic : R.id.rb_audio_mute);
        group.setOnCheckedChangeListener((g, id) -> {
            prefs.edit().putBoolean(KEY_RECORD_AUDIO, id == R.id.rb_audio_mic).apply();
            refreshAudioInputs();
        });
    }

    /**
     * "Microphone to use": the phone mic plus every connected external mic, shown only while one
     * is connected and audio is not muted. The external mic is listed (and preselected) first.
     */
    private void refreshAudioInputs() {
        View section = findViewById(R.id.audio_input_section);
        RadioGroup group = findViewById(R.id.rg_audio_input);
        List<AudioDeviceInfo> external = AudioInputs.externalInputs(this);
        boolean show = !external.isEmpty() && prefs.getBoolean(KEY_RECORD_AUDIO, true);
        section.setVisibility(show ? View.VISIBLE : View.GONE);
        group.setOnCheckedChangeListener(null);
        group.removeAllViews();
        if (!show) return;

        int selected = AudioInputs.selectedId(this);
        for (AudioDeviceInfo device : external) {
            addAudioInputRadio(group, device.getId(), AudioInputs.label(this, device),
                    selected == device.getId());
        }
        addAudioInputRadio(group, AudioInputSelection.PHONE_MIC, getString(R.string.audio_src_phone),
                selected == AudioInputSelection.PHONE_MIC);
        group.setOnCheckedChangeListener((g, checkedId) -> {
            View checked = g.findViewById(checkedId);
            if (checked == null) return;
            int deviceId = (Integer) checked.getTag();
            if (deviceId == AudioInputSelection.PHONE_MIC) {
                AudioInputs.selectPhoneMic();
            } else {
                AudioInputs.selectExternal(deviceId);
            }
        });
    }

    private void addAudioInputRadio(RadioGroup group, int deviceId, String label, boolean checked) {
        RadioButton button = new RadioButton(this);
        button.setId(View.generateViewId());
        button.setTag(deviceId);
        button.setText(label);
        button.setTextColor(0xFFFFFFFF);
        button.setTextSize(14);
        button.setMinHeight((int) (48 * getResources().getDisplayMetrics().density));
        button.setButtonTintList(ColorStateList.valueOf(ACCENT));
        group.addView(button, new RadioGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (checked) group.check(button.getId());
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Mics plugged in or out while this screen is open
        audioDeviceCallback = AudioInputs.register(this, this::refreshAudioInputs);
    }

    @Override
    protected void onStop() {
        if (audioDeviceCallback != null) {
            AudioInputs.unregister(this, audioDeviceCallback);
            audioDeviceCallback = null;
        }
        super.onStop();
    }

    private void setupAutoCrop() {
        bindCheckBox(R.id.cb_crop_9_16, prefs, KEY_AUTOCROP_9_16, true);
        bindCheckBox(R.id.cb_crop_4_5, prefs, KEY_AUTOCROP_4_5, false);
        bindCheckBox(R.id.cb_crop_1_1, prefs, KEY_AUTOCROP_1_1, false);
    }

    private void bindCheckBox(int viewId, SharedPreferences target, String key, boolean defaultValue) {
        CheckBox box = findViewById(viewId);
        box.setChecked(target.getBoolean(key, defaultValue));
        box.setOnCheckedChangeListener((b, checked) -> target.edit().putBoolean(key, checked).apply());
    }

    // ---- auto-publish ----

    private void setupAutoPublish() {
        bindCheckBox(R.id.cb_auto_publish, prefs, KEY_AUTO_PUBLISH, false);
        setupAutoPublishUndo();
        autoPublishStatus = findViewById(R.id.tv_auto_publish_status);
    }

    /** "Undo before publishing": Off / 30 s / 60 s, default 30 s. */
    private void setupAutoPublishUndo() {
        RadioGroup group = findViewById(R.id.rg_auto_undo);
        int seconds = AutoPublishUndo.normalize(
                prefs.getInt(KEY_AUTO_PUBLISH_UNDO_SECONDS, AutoPublishUndo.DEFAULT_SECONDS));
        group.check(seconds == AutoPublishUndo.OFF ? R.id.rb_auto_undo_0
                : seconds == AutoPublishUndo.MAX_SECONDS ? R.id.rb_auto_undo_60
                : R.id.rb_auto_undo_30);
        group.setOnCheckedChangeListener((g, id) -> {
            int value = id == R.id.rb_auto_undo_0 ? AutoPublishUndo.OFF
                    : id == R.id.rb_auto_undo_60 ? AutoPublishUndo.MAX_SECONDS
                    : 30;
            prefs.edit().putInt(KEY_AUTO_PUBLISH_UNDO_SECONDS, value).apply();
        });
    }

    /** Shows the status line for the current sign-in state; asks the server when signed in. */
    private void loadAutoPublishStatus() {
        int load = autoPublishLoad.incrementAndGet();
        SessionManager session = SessionManager.get(this);
        if (!session.hasSession()) {
            showAutoPublishStatus(getString(R.string.set_ap_status_signed_out),
                    v -> SessionManager.openSignIn(this, null));
            return;
        }
        showAutoPublishStatus(getString(R.string.set_ap_status_loading), null);
        PublishApi api = new PublishApi(session.repository());
        try {
            autoPublishExecutor.execute(() -> {
                AutoPublishInfo info = null;
                boolean signedOut = false;
                try {
                    info = api.autoPublishInfo();
                } catch (PublishException e) {
                    signedOut = e.kind == PublishException.Kind.SIGNED_OUT;
                }
                final AutoPublishInfo result = info;
                final boolean ended = signedOut;
                main.post(() -> {
                    if (isFinishing() || isDestroyed() || load != autoPublishLoad.get()) return;
                    applyAutoPublishResult(result, ended);
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // Activity is shutting down.
        }
    }

    private void applyAutoPublishResult(AutoPublishInfo info, boolean signedOut) {
        if (signedOut) {
            showAutoPublishStatus(getString(R.string.set_ap_status_signed_out),
                    v -> SessionManager.openSignIn(this, null));
        } else if (info == null) {
            showAutoPublishStatus(getString(R.string.set_ap_status_failed),
                    v -> loadAutoPublishStatus());
        } else if (!info.configured) {
            String url = WebLinks.isWebUrl(info.setupUrl) ? info.setupUrl : WebLinks.setsUrl();
            showAutoPublishStatus(getString(R.string.set_ap_status_not_set_up),
                    v -> WebLinks.open(this, url));
        } else {
            showAutoPublishStatus(readyLine(info), null);
        }
    }

    private String readyLine(AutoPublishInfo info) {
        List<String> names = info.providerNames();
        if (names.isEmpty()) return getString(R.string.set_ap_status_ready_no_channels);
        String cost = info.cost == 0
                ? getString(R.string.set_ap_cost_free)
                : getResources().getQuantityString(R.plurals.set_ap_cost, plural(info.cost),
                        info.cost);
        return getString(R.string.set_ap_status_ready, String.join(", ", names), cost,
                info.available);
    }

    /** Plural selector that fits an int; amounts beyond it all read as "other". */
    private static int plural(long n) {
        return n > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) n;
    }

    private void showAutoPublishStatus(String text, View.OnClickListener onClick) {
        autoPublishStatus.setText(text);
        autoPublishStatus.setOnClickListener(onClick);
        autoPublishStatus.setClickable(onClick != null);
        autoPublishStatus.setTextColor(onClick != null ? ACCENT : 0xFFAAAAAA);
    }

    // ---- AI background ----

    /**
     * Cards: every image in assets/bg_samples/ (adding one needs no code change), then the user's own
     * photos (deletable, max {@link UserBackgrounds#MAX_UPLOADS}), then an "add your photo" card.
     */
    private void setupBackground() {
        bindCheckBox(R.id.cb_bg_replace, bgPrefs, KEY_BG_REPLACE_ENABLED, true);

        photoPicker = registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
            if (uri != null) importBackground(uri);
        });

        builtInBackgrounds.clear();
        try {
            String[] files = getAssets().list("bg_samples");
            if (files != null) {
                Arrays.sort(files);
                Arrays.sort(files, Comparator.comparingInt(SettingsActivity::bgSortRank));
                for (String f : files) {
                    String lower = f.toLowerCase(Locale.ROOT);
                    if (lower.endsWith(".png") || lower.endsWith(".jpg")
                            || lower.endsWith(".jpeg") || lower.endsWith(".webp")) {
                        builtInBackgrounds.add("bg_samples/" + f);
                    }
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Listing bg_samples failed", e);
        }

        rebuildBackgroundGrid();
    }

    /** Built-in backgrounds shown first, in this order; any others follow alphabetically. */
    private static final String[] BG_PRIORITY = {
            "modern-office", "playful-corner", "sunlit-studio", "modern-lounge"
    };

    private static int bgSortRank(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (int i = 0; i < BG_PRIORITY.length; i++) {
            if (lower.startsWith(BG_PRIORITY[i] + ".")) return i;
        }
        return BG_PRIORITY.length;
    }

    /** Re-creates every card from the built-in list and the user's folder, and restores the selection. */
    private void rebuildBackgroundGrid() {
        GridLayout grid = findViewById(R.id.grid_bg_sample);
        grid.removeAllViews();
        bgPaths.clear();
        bgCards.clear();
        bgChecks.clear();

        for (String assetPath : builtInBackgrounds) {
            addBackgroundCard(grid, assetPath, bgDisplayName(assetPath), null);
        }
        List<File> mine = UserBackgrounds.list(this);
        for (int i = 0; i < mine.size(); i++) {
            File file = mine.get(i);
            addBackgroundCard(grid, file.getAbsolutePath(),
                    getString(R.string.set_bg_mine_name, i + 1), file);
        }
        addUploadCard(grid, mine.size());

        // Nothing saved (or a removed background): show the default sample, which is what gets used.
        String saved = bgPrefs.getString(KEY_BG_ASSET_PATH, FaceBgReplaceTask.DEFAULT_BG_ASSET);
        int selected = bgPaths.indexOf(saved);
        if (selected < 0) {
            selected = bgPaths.indexOf(FaceBgReplaceTask.DEFAULT_BG_ASSET);
        }
        markSelectedBackground(Math.max(selected, 0));
    }

    /** One selectable card; {@code userFile} is non-null for an uploaded photo, which adds a delete button. */
    private void addBackgroundCard(GridLayout grid, String path, String label, File userFile) {
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (4 * d);

        FrameLayout card = newCard(grid);
        ImageView preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.setImageBitmap(loadThumbnail(path, 320));
        addCardContent(card, preview, label);

        ImageView check = new ImageView(this);
        check.setImageResource(R.drawable.ic_check_circle);
        check.setBackgroundResource(R.drawable.bg_circle_nav);
        check.setBackgroundTintList(ColorStateList.valueOf(0xFF6200EE));
        check.setVisibility(View.GONE);
        check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams checkLp = new FrameLayout.LayoutParams(
                (int) (22 * d), (int) (22 * d), Gravity.TOP | Gravity.END);
        checkLp.setMargins(pad, pad, pad, pad);
        card.addView(check, checkLp);

        if (userFile != null) {
            ImageButton delete = new ImageButton(this);
            delete.setImageResource(R.drawable.ic_delete);
            delete.setBackgroundResource(R.drawable.bg_circle_nav);
            delete.setBackgroundTintList(ColorStateList.valueOf(0xF2FFFFFF));
            int inset = (int) (7 * d);
            delete.setPadding(inset, inset, inset, inset);
            delete.setContentDescription(getString(R.string.set_bg_delete_desc));
            delete.setOnClickListener(v -> confirmDeleteBackground(userFile));
            FrameLayout.LayoutParams deleteLp = new FrameLayout.LayoutParams(
                    (int) (36 * d), (int) (36 * d), Gravity.TOP | Gravity.START);
            deleteLp.setMargins(pad, pad, pad, pad);
            card.addView(delete, deleteLp);
        }

        final int idx = bgPaths.size();
        card.setOnClickListener(v -> {
            bgPrefs.edit().putString(KEY_BG_ASSET_PATH, path).apply();
            markSelectedBackground(idx);
        });
        bgPaths.add(path);
        bgCards.add(card);
        bgChecks.add(check);
    }

    /** Last card: "Add your photo" with how many of the allowed uploads are used. */
    private void addUploadCard(GridLayout grid, int uploaded) {
        boolean full = !UserBackgrounds.canAddMore(uploaded);
        float d = getResources().getDisplayMetrics().density;

        FrameLayout card = newCard(grid);
        ImageView plus = new ImageView(this);
        plus.setImageResource(R.drawable.ic_add);
        plus.setColorFilter(0xFF6200EE);
        plus.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int inset = (int) (22 * d);
        plus.setPadding(inset, inset, inset, inset);
        plus.setBackgroundColor(0xFFF3EBFF);
        String label = getString(R.string.set_bg_add) + "\n" + (full
                ? getString(R.string.set_bg_add_full)
                : getString(R.string.set_bg_add_count, uploaded, UserBackgrounds.MAX_UPLOADS));
        addCardContent(card, plus, label);
        card.setAlpha(full ? 0.6f : 1f);
        card.setOnClickListener(v -> {
            if (importing) return;
            if (!UserBackgrounds.canAddMore(UserBackgrounds.list(this).size())) {
                Toast.makeText(this,
                        getString(R.string.set_bg_limit_message, UserBackgrounds.MAX_UPLOADS),
                        Toast.LENGTH_LONG).show();
                return;
            }
            Toast.makeText(this, R.string.set_bg_add_tip, Toast.LENGTH_LONG).show();
            photoPicker.launch(new PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                    .build());
        });
    }

    /** Empty clickable card with the shared border and grid placement. */
    private FrameLayout newCard(GridLayout grid) {
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (4 * d);
        int margin = (int) (6 * d);

        FrameLayout card = new FrameLayout(this);
        card.setBackgroundResource(R.drawable.bg_bg_sample_card);
        card.setPadding(pad, pad, pad, pad);
        card.setClickable(true);
        card.setFocusable(true);

        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
        lp.width = 0;
        lp.setMargins(margin, margin, margin, margin);
        grid.addView(card, lp);
        return card;
    }

    /** Fixed-height image area with the label underneath, inside the card border. */
    private void addCardContent(FrameLayout card, View image, String label) {
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (4 * d);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(image, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (80 * d)));
        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(0xFF1F1F1F);
        name.setTextSize(12);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(2);
        name.setPadding(0, pad, 0, (int) (2 * d));
        col.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** Copies the picked photo into app storage off the main thread, then selects it. */
    private void importBackground(Uri uri) {
        if (importing) return;
        importing = true;
        importExecutor.execute(() -> {
            File imported = null;
            try {
                if (UserBackgrounds.canAddMore(UserBackgrounds.list(this).size())) {
                    imported = UserBackgrounds.importImage(this, uri);
                }
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "Background import failed: " + uri, e);
            }
            final File result = imported;
            runOnUiThread(() -> {
                importing = false;
                if (isFinishing() || isDestroyed()) return;
                if (result == null) {
                    boolean full = !UserBackgrounds.canAddMore(UserBackgrounds.list(this).size());
                    Toast.makeText(this,
                            full ? getString(R.string.set_bg_limit_message, UserBackgrounds.MAX_UPLOADS)
                                    : getString(R.string.set_bg_import_failed),
                            Toast.LENGTH_LONG).show();
                    return;
                }
                bgPrefs.edit().putString(KEY_BG_ASSET_PATH, result.getAbsolutePath()).apply();
                rebuildBackgroundGrid();
            });
        });
    }

    private void confirmDeleteBackground(File file) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.set_bg_delete_title)
                .setMessage(R.string.set_bg_delete_message)
                .setPositiveButton(R.string.set_bg_delete_yes, (d, w) -> deleteBackground(file))
                .setNegativeButton(R.string.set_bg_delete_no, null)
                .show();
    }

    /** Deletes an uploaded photo; if it was selected, the first built-in background takes over. */
    private void deleteBackground(File file) {
        boolean wasSelected = file.getAbsolutePath()
                .equals(bgPrefs.getString(KEY_BG_ASSET_PATH, ""));
        UserBackgrounds.delete(file);
        if (wasSelected) {
            if (builtInBackgrounds.isEmpty()) {
                bgPrefs.edit().remove(KEY_BG_ASSET_PATH).apply();
            } else {
                String fallback = builtInBackgrounds.get(0);
                bgPrefs.edit().putString(KEY_BG_ASSET_PATH, fallback).apply();
                Toast.makeText(this,
                        getString(R.string.set_bg_deleted_fallback, bgDisplayName(fallback)),
                        Toast.LENGTH_LONG).show();
            }
        }
        rebuildBackgroundGrid();
    }

    private void markSelectedBackground(int selected) {
        for (int i = 0; i < bgCards.size(); i++) {
            boolean on = i == selected;
            bgCards.get(i).setSelected(on);
            bgChecks.get(i).setVisibility(on ? View.VISIBLE : View.GONE);
        }
    }

    /** "bg_samples/modern-office_1.png" -> "Modern Office 1". */
    private static String bgDisplayName(String assetPath) {
        String n = assetPath.substring(assetPath.lastIndexOf('/') + 1);
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        StringBuilder sb = new StringBuilder();
        for (String w : n.split("[-_\\s]+")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    /**
     * Decodes a background (bundled asset or uploaded file) downsampled to roughly {@code maxWidth}
     * px wide; null on failure.
     */
    private Bitmap loadThumbnail(String path, int maxWidth) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = openBackground(path)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = UserBackgrounds.sampleSizeFor(bounds.outWidth, bounds.outWidth, maxWidth);
            try (InputStream in = openBackground(path)) {
                return BitmapFactory.decodeStream(in, null, opts);
            }
        } catch (Exception e) {
            Log.w(TAG, "Background thumbnail failed: " + path, e);
            return null;
        }
    }

    private InputStream openBackground(String path) throws IOException {
        return UserBackgrounds.isUserPath(path) ? new FileInputStream(path) : getAssets().open(path);
    }

    // ---- permissions ----

    /** Every permission the app asks for anywhere, filtered to the ones not granted. Add new ones here. */
    private List<MissingPermission> getMissingPermissionRows() {
        List<MissingPermission> rows = new ArrayList<>();
        for (String permission : RecordSettings.missingPermissions(this)) {
            if (Manifest.permission.CAMERA.equals(permission)) {
                rows.add(new MissingPermission(R.string.set_perm_camera, permission,
                        SettingsHelp.PERM_CAMERA));
            } else if (Manifest.permission.RECORD_AUDIO.equals(permission)) {
                rows.add(new MissingPermission(R.string.set_perm_mic, permission,
                        SettingsHelp.PERM_MIC));
            } else if (Manifest.permission.POST_NOTIFICATIONS.equals(permission)) {
                rows.add(new MissingPermission(R.string.set_perm_notifications, permission,
                        SettingsHelp.PERM_NOTIFICATIONS));
            }
        }
        if (!Settings.canDrawOverlays(this)) {
            rows.add(new MissingPermission(R.string.set_perm_overlay, null,
                    SettingsHelp.PERM_OVERLAY));
        }
        return rows;
    }

    /**
     * One row and one help block per missing permission; a granted one disappears and the section
     * hides once none are left.
     */
    private void refreshPermissionRows() {
        permissionsContainer.removeAllViews();
        List<MissingPermission> missing = getMissingPermissionRows();
        permissionsSection.setVisibility(missing.isEmpty() ? View.GONE : View.VISIBLE);
        float density = getResources().getDisplayMetrics().density;
        List<SettingsHelp.Block> help = new ArrayList<>();
        for (MissingPermission item : missing) {
            help.add(item.help);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView label = new TextView(this);
            label.setText(item.labelRes);
            label.setTextColor(0xFFFFFFFF);
            label.setTextSize(14);
            row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button allow = new Button(this);
            allow.setText(R.string.set_allow);
            allow.setAllCaps(false);
            allow.setTextColor(0xFF000000);
            allow.setBackgroundTintList(ColorStateList.valueOf(ACCENT));
            allow.setOnClickListener(v -> requestMissingPermission(item));
            row.addView(allow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(40 * density)));

            permissionsContainer.addView(row);
        }
        permissionsHelp.setBlocks(help);
    }

    private void requestMissingPermission(MissingPermission item) {
        if (item.runtimePermission == null) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.fromParts("package", getPackageName(), null)));
        } else {
            pendingPermission = item.runtimePermission;
            permissionLauncher.launch(item.runtimePermission);
        }
    }
}
