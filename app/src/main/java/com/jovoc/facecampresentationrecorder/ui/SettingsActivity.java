package com.jovoc.facecampresentationrecorder.ui;

import static com.jovoc.facecampresentationrecorder.util.RecordSettings.BG_PREF_NAME;
import static com.jovoc.facecampresentationrecorder.util.RecordSettings.KEY_AUTOCROP_1_1;
import static com.jovoc.facecampresentationrecorder.util.RecordSettings.KEY_AUTOCROP_4_5;
import static com.jovoc.facecampresentationrecorder.util.RecordSettings.KEY_AUTOCROP_9_16;
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

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.util.RecordSettings;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

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

    private View permissionsSection;
    private LinearLayout permissionsContainer;
    private ActivityResultLauncher<String> permissionLauncher;
    private String pendingPermission;

    private final List<String> bgAssets = new ArrayList<>();
    private final List<View> bgCards = new ArrayList<>();
    private final List<View> bgChecks = new ArrayList<>();

    /** A permission this app needs that the user hasn't granted yet. */
    private static final class MissingPermission {
        final int labelRes;
        final String runtimePermission; // null for the "display over other apps" special access

        MissingPermission(int labelRes, String runtimePermission) {
            this.labelRes = labelRes;
            this.runtimePermission = runtimePermission;
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

        setupCountdown();
        setupAudio();
        setupAutoCrop();
        setupBackground();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Also covers returning from the system app-settings / overlay screens.
        refreshPermissionRows();
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
        group.setOnCheckedChangeListener((g, id) ->
                prefs.edit().putBoolean(KEY_RECORD_AUDIO, id == R.id.rb_audio_mic).apply());
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

    // ---- AI background ----

    /** Every image in assets/bg_samples/ becomes a card, so adding an image needs no code change. */
    private void setupBackground() {
        bindCheckBox(R.id.cb_bg_replace, bgPrefs, KEY_BG_REPLACE_ENABLED, true);

        try {
            String[] files = getAssets().list("bg_samples");
            if (files != null) {
                Arrays.sort(files);
                for (String f : files) {
                    String lower = f.toLowerCase(Locale.ROOT);
                    if (lower.endsWith(".png") || lower.endsWith(".jpg")
                            || lower.endsWith(".jpeg") || lower.endsWith(".webp")) {
                        bgAssets.add("bg_samples/" + f);
                    }
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Listing bg_samples failed", e);
        }

        GridLayout grid = findViewById(R.id.grid_bg_sample);
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (4 * d);
        int margin = (int) (6 * d);
        for (int i = 0; i < bgAssets.size(); i++) {
            final String assetPath = bgAssets.get(i);

            FrameLayout card = new FrameLayout(this);
            card.setBackgroundResource(R.drawable.bg_bg_sample_card);
            card.setPadding(pad, pad, pad, pad);
            card.setClickable(true);
            card.setFocusable(true);

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            ImageView preview = new ImageView(this);
            preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
            preview.setImageBitmap(loadAssetThumbnail(assetPath, 320));
            col.addView(preview, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (int) (80 * d)));
            TextView name = new TextView(this);
            name.setText(bgDisplayName(assetPath));
            name.setTextColor(0xFF1F1F1F);
            name.setTextSize(12);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(2);
            name.setPadding(0, pad, 0, (int) (2 * d));
            col.addView(name, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            card.addView(col, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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

            final int idx = i;
            card.setOnClickListener(v -> {
                bgPrefs.edit().putString(KEY_BG_ASSET_PATH, assetPath).apply();
                markSelectedBackground(idx);
            });
            bgCards.add(card);
            bgChecks.add(check);

            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
            lp.width = 0;
            lp.setMargins(margin, margin, margin, margin);
            grid.addView(card, lp);
        }

        // Nothing saved (or a removed asset): show the first sample, which is what the dialog did.
        int selected = bgAssets.indexOf(bgPrefs.getString(KEY_BG_ASSET_PATH, ""));
        markSelectedBackground(Math.max(selected, 0));
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

    /** Decodes an asset image downsampled to roughly {@code maxWidth} px wide; null on failure. */
    private Bitmap loadAssetThumbnail(String assetPath, int maxWidth) {
        try (InputStream in = getAssets().open(assetPath)) {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, bounds);
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2;
            try (InputStream in2 = getAssets().open(assetPath)) {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = sample;
                return BitmapFactory.decodeStream(in2, null, opts);
            }
        } catch (Exception e) {
            Log.w(TAG, "Background thumbnail failed: " + assetPath, e);
            return null;
        }
    }

    // ---- permissions ----

    /** Every permission the app asks for anywhere, filtered to the ones not granted. Add new ones here. */
    private List<MissingPermission> getMissingPermissionRows() {
        List<MissingPermission> rows = new ArrayList<>();
        for (String permission : RecordSettings.missingPermissions(this)) {
            if (Manifest.permission.CAMERA.equals(permission)) {
                rows.add(new MissingPermission(R.string.set_perm_camera, permission));
            } else if (Manifest.permission.RECORD_AUDIO.equals(permission)) {
                rows.add(new MissingPermission(R.string.set_perm_mic, permission));
            } else if (Manifest.permission.POST_NOTIFICATIONS.equals(permission)) {
                rows.add(new MissingPermission(R.string.set_perm_notifications, permission));
            }
        }
        if (!Settings.canDrawOverlays(this)) {
            rows.add(new MissingPermission(R.string.set_perm_overlay, null));
        }
        return rows;
    }

    /** One row per missing permission; a granted one disappears and the section hides once none are left. */
    private void refreshPermissionRows() {
        permissionsContainer.removeAllViews();
        List<MissingPermission> missing = getMissingPermissionRows();
        permissionsSection.setVisibility(missing.isEmpty() ? View.GONE : View.VISIBLE);
        float density = getResources().getDisplayMetrics().density;
        for (MissingPermission item : missing) {
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
