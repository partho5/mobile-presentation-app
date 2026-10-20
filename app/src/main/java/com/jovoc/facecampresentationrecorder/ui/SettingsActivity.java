package com.jovoc.facecampresentationrecorder.ui;

import com.jovoc.facecampresentationrecorder.activitylog.ActivityLog;
import com.jovoc.facecampresentationrecorder.bgreplace.FaceBgReplaceTask;

import static com.jovoc.facecampresentationrecorder.util.RecordSettings.BG_PREF_NAME;
import static com.jovoc.facecampresentationrecorder.util.RecordSettings.KEY_AUTOCROP_1_1;
import static com.jovoc.facecampresentationrecorder.util.RecordSettings.KEY_AUTOCROP_4_5;
import static com.jovoc.facecampresentationrecorder.util.RecordSettings.KEY_AUTOCROP_9_16;
import static com.jovoc.facecampresentationrecorder.util.RecordSettings.KEY_AUTO_PUBLISH;
import static com.jovoc.facecampresentationrecorder.util.RecordSettings.KEY_PUBLISH_WIFI_ONLY;
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
import com.jovoc.facecampresentationrecorder.entitlements.Entitlements;
import com.jovoc.facecampresentationrecorder.entitlements.Feature;
import com.jovoc.facecampresentationrecorder.publish.PublishManager;
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
    /** What bg_custom allowed when the grid was last built; own photos are locked cards without it. */
    private boolean customBackgroundsAllowed;
    private boolean bgGridBuilt;

    private final List<GatedBox> gatedBoxes = new ArrayList<>();
    private final Entitlements.Listener planListener = this::onPlanChanged;

    private TextView autoUploadState;

    private View floatingCamSection;
    private ImageView floatingCamCrown;
    private TextView floatingCamTier;
    /** What floating_cam allowed when the permission rows were last built (the overlay row needs it). */
    private boolean floatingCamAllowed = true;

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
                    ActivityLog.log("permission_result", "perm", permissionLogName(permission),
                            "granted", granted);
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
        setupFloatingCam();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Also covers returning from the system app-settings / overlay screens.
        refreshPermissionRows();
        // Also after signing in, or after changing auto upload on its own screen.
        showAutoUploadState();
        // The plan may have changed while away (billing page, sign-in); also follows changes while open.
        onPlanChanged();
        Entitlements.addListener(planListener);
        forEachHelpView(findViewById(R.id.settings_content), SettingsHelpView::onHostResume);
    }

    @Override
    protected void onDestroy() {
        importExecutor.shutdown();
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        Entitlements.removeListener(planListener);
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
            ActivityLog.log("setting_change", "key", "countdown_s", "value", value);
        });
    }

    private void setupAudio() {
        RadioGroup group = findViewById(R.id.rg_audio);
        group.check(prefs.getBoolean(KEY_RECORD_AUDIO, true) ? R.id.rb_audio_mic : R.id.rb_audio_mute);
        group.setOnCheckedChangeListener((g, id) -> {
            prefs.edit().putBoolean(KEY_RECORD_AUDIO, id == R.id.rb_audio_mic).apply();
            ActivityLog.log("setting_change", "key", "record_audio", "value", id == R.id.rb_audio_mic);
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
            ActivityLog.log("setting_change", "key", "mic_source",
                    "value", deviceId == AudioInputSelection.PHONE_MIC ? "phone" : "external");
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
        bindGatedCheckBox(R.id.cb_crop_9_16, R.id.tv_tier_crop_9_16, prefs, KEY_AUTOCROP_9_16, true,
                Feature.CROP_9_16);
        bindGatedCheckBox(R.id.cb_crop_4_5, R.id.tv_tier_crop_4_5, prefs, KEY_AUTOCROP_4_5, false,
                Feature.CROP_4_5);
        bindGatedCheckBox(R.id.cb_crop_1_1, R.id.tv_tier_crop_1_1, prefs, KEY_AUTOCROP_1_1, false,
                Feature.CROP_1_1);
    }

    /** A toggle behind a plan feature: its stored choice only counts while the plan covers it. */
    private static final class GatedBox {
        final CheckBox box;
        final TextView tier;
        final SharedPreferences target;
        final String key;
        final boolean defaultValue;
        final String feature;
        /** True while the code, not the user, sets the box, so that is not saved as a choice. */
        boolean syncing;

        GatedBox(CheckBox box, TextView tier, SharedPreferences target, String key,
                 boolean defaultValue, String feature) {
            this.box = box;
            this.tier = tier;
            this.target = target;
            this.key = key;
            this.defaultValue = defaultValue;
            this.feature = feature;
        }

        /** Shows the stored choice (off while locked, but kept) and the premium look for the current plan. */
        void sync() {
            show(target.getBoolean(key, defaultValue) && Entitlements.has(feature));
            PremiumMark.apply(box, feature);
            PremiumMark.apply(null, tier, feature);
        }

        void show(boolean checked) {
            syncing = true;
            box.setChecked(checked);
            syncing = false;
        }
    }

    /**
     * A checkbox saved to {@code target}. While {@code feature} is locked it stays off: tapping it
     * opens the upgrade sheet instead, and the tick is applied if the user upgrades. The stored
     * value is never overwritten by a lock, so it comes back with the plan.
     */
    private void bindGatedCheckBox(int boxId, int tierId, SharedPreferences target, String key,
                                   boolean defaultValue, String feature) {
        GatedBox gated = new GatedBox(findViewById(boxId), findViewById(tierId), target, key,
                defaultValue, feature);
        gatedBoxes.add(gated);
        gated.box.setOnCheckedChangeListener((b, checked) -> {
            if (gated.syncing) return;
            if (checked && !Entitlements.has(feature)) {
                gated.show(false);
                Gate.run(this, feature, () -> gated.box.setChecked(true));
                return;
            }
            target.edit().putBoolean(key, checked).apply();
            ActivityLog.log("setting_change", "key", key, "value", checked);
        });
        gated.sync();
    }

    /** The plan changed (or the screen came back): every gated control follows it. */
    private void onPlanChanged() {
        for (GatedBox gated : gatedBoxes) gated.sync();
        if (bgGridBuilt && customBackgroundsAllowed != Entitlements.has(Feature.BG_CUSTOM)) {
            rebuildBackgroundGrid();
        }
        syncFloatingCam();
        showAutoUploadState();
    }

    // ---- face cam over other apps ----

    /**
     * Floating cam has no switch: the face cam follows the user when they leave the app while
     * recording. So this row exists only while the plan lacks it, to say why it does not float and
     * open the upgrade sheet. With the plan it disappears, and the overlay permission row appears.
     */
    private void setupFloatingCam() {
        floatingCamSection = findViewById(R.id.section_floating_cam);
        floatingCamCrown = findViewById(R.id.iv_premium_floating_cam);
        floatingCamTier = findViewById(R.id.tv_tier_floating_cam);
        findViewById(R.id.row_floating_cam).setOnClickListener(
                v -> Gate.run(this, Feature.FLOATING_CAM, this::syncFloatingCam));
        syncFloatingCam();
    }

    private void syncFloatingCam() {
        boolean allowed = Entitlements.has(Feature.FLOATING_CAM);
        floatingCamSection.setVisibility(allowed ? View.GONE : View.VISIBLE);
        PremiumMark.apply(floatingCamCrown, floatingCamTier, Feature.FLOATING_CAM);
        // The overlay permission is asked for only while the plan covers the face cam.
        if (allowed != floatingCamAllowed) refreshPermissionRows();
    }

    // ---- auto upload ----

    /** One row into the Auto upload screen, where auto-publish is set up; plus "Wi-Fi only". */
    private void setupAutoPublish() {
        autoUploadState = findViewById(R.id.tv_auto_upload_state);
        findViewById(R.id.row_auto_upload).setOnClickListener(v -> AutoPublishActivity.open(this));
        setupWifiOnly();
    }

    /** "Upload on Wi-Fi only": applies to new uploads and re-queues the waiting ones. */
    private void setupWifiOnly() {
        CheckBox box = findViewById(R.id.cb_wifi_only);
        box.setChecked(prefs.getBoolean(KEY_PUBLISH_WIFI_ONLY, false));
        box.setOnCheckedChangeListener((b, checked) -> {
            // apply() updates the in-memory prefs at once, so the re-enqueue below reads the new value.
            prefs.edit().putBoolean(KEY_PUBLISH_WIFI_ONLY, checked).apply();
            ActivityLog.log("setting_change", "key", "publish_wifi_only", "value", checked);
            PublishManager.get(this).onWifiOnlyChanged();
        });
    }

    /** The row's subtitle from local state only: the Auto upload screen has the full picture. */
    private void showAutoUploadState() {
        int state = !SessionManager.get(this).hasSession() ? R.string.set_ap_row_not_set_up
                : prefs.getBoolean(KEY_AUTO_PUBLISH, false) && Entitlements.has(Feature.AUTO_PUBLISH)
                ? R.string.set_ap_row_on
                : R.string.set_ap_row_off;
        autoUploadState.setText(state);
    }

    // ---- AI background ----

    /**
     * Cards: every image in assets/bg_samples/ (adding one needs no code change), then the user's own
     * photos (deletable, max {@link UserBackgrounds#MAX_UPLOADS}), then an "add your photo" card.
     */
    private void setupBackground() {
        bindGatedCheckBox(R.id.cb_bg_replace, R.id.tv_tier_bg_replace, bgPrefs, KEY_BG_REPLACE_ENABLED,
                true, Feature.BG_REPLACE);

        photoPicker = registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
            if (uri != null) {
                ActivityLog.log("background_add");
                // The plan may have ended while the picker was open: ask again rather than import
                Gate.run(this, Feature.BG_CUSTOM, () -> importBackground(uri));
            }
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
        customBackgroundsAllowed = Entitlements.has(Feature.BG_CUSTOM);
        bgGridBuilt = true;

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

        // Nothing saved, a removed background, or an own photo the plan no longer covers (kept, but
        // not selectable): show the default sample, which is what gets used.
        String saved = UserBackgrounds.effectivePath(
                bgPrefs.getString(KEY_BG_ASSET_PATH, FaceBgReplaceTask.DEFAULT_BG_ASSET),
                customBackgroundsAllowed, FaceBgReplaceTask.DEFAULT_BG_ASSET);
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
        // An own photo stays on disk when bg_custom is off but cannot be picked: the tap opens the
        // upgrade sheet and, if the user upgrades, picks it.
        boolean locked = userFile != null && !customBackgroundsAllowed;
        if (locked) {
            card.setAlpha(0.6f);
            addPremiumCorner(card, Feature.BG_CUSTOM);
        }
        card.setOnClickListener(v -> {
            Runnable select = () -> {
                bgPrefs.edit().putString(KEY_BG_ASSET_PATH, path).apply();
                ActivityLog.log("setting_change", "key", "background",
                        "value", userFile != null ? "own_photo" : "built_in");
                markSelectedBackground(idx);
            };
            if (userFile != null) Gate.run(this, Feature.BG_CUSTOM, select);
            else select.run();
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
        card.setAlpha(full || !customBackgroundsAllowed ? 0.6f : 1f);
        addPremiumCorner(card, Feature.BG_CUSTOM);
        // Locked: the upgrade sheet first; the picker opens once the user has upgraded
        card.setOnClickListener(v -> Gate.run(this, Feature.BG_CUSTOM, () -> {
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
        }));
    }

    /** The premium symbol in a card's top-right corner: crown, plus the tier name while it is locked. */
    private void addPremiumCorner(FrameLayout card, String feature) {
        if (!PremiumMark.isPremium(feature)) return;
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (4 * d);

        LinearLayout corner = new LinearLayout(this);
        corner.setOrientation(LinearLayout.HORIZONTAL);
        corner.setGravity(Gravity.CENTER_VERTICAL);
        TextView badge = new TextView(this);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeLp.setMarginEnd(pad);
        corner.addView(badge, badgeLp);
        ImageView crown = new ImageView(this);
        corner.addView(crown, new LinearLayout.LayoutParams((int) (20 * d), (int) (20 * d)));
        PremiumMark.apply(crown, badge, feature);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        lp.setMargins(pad, pad, pad, pad);
        card.addView(corner, lp);
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
                .setPositiveButton(R.string.set_bg_delete_yes, (d, w) -> {
                    ActivityLog.log("background_delete");
                    deleteBackground(file);
                })
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
        // Only the face cam over other apps needs this; not worth asking for without the plan.
        floatingCamAllowed = Entitlements.has(Feature.FLOATING_CAM);
        if (floatingCamAllowed && !Settings.canDrawOverlays(this)) {
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

    /** Short name of a permission, for the activity log (null = the overlay special access). */
    private static String permissionLogName(String permission) {
        if (permission == null) return "overlay";
        if (Manifest.permission.CAMERA.equals(permission)) return "camera";
        if (Manifest.permission.RECORD_AUDIO.equals(permission)) return "mic";
        if (Manifest.permission.POST_NOTIFICATIONS.equals(permission)) return "notifications";
        return "other";
    }

    private void requestMissingPermission(MissingPermission item) {
        ActivityLog.log("permission_prompt", "perm", permissionLogName(item.runtimePermission), "source", "settings");
        if (item.runtimePermission == null) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.fromParts("package", getPackageName(), null)));
        } else {
            pendingPermission = item.runtimePermission;
            permissionLauncher.launch(item.runtimePermission);
        }
    }
}
