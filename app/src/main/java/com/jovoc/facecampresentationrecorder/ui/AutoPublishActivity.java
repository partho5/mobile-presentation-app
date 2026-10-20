package com.jovoc.facecampresentationrecorder.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.activitylog.ActivityLog;
import com.jovoc.facecampresentationrecorder.auth.SessionManager;
import com.jovoc.facecampresentationrecorder.entitlements.Entitlements;
import com.jovoc.facecampresentationrecorder.entitlements.Feature;
import com.jovoc.facecampresentationrecorder.publish.AutoPublishInfo;
import com.jovoc.facecampresentationrecorder.publish.AutoPublishUndo;
import com.jovoc.facecampresentationrecorder.publish.Platforms;
import com.jovoc.facecampresentationrecorder.publish.Platforms.ChannelState;
import com.jovoc.facecampresentationrecorder.publish.Platforms.Platform;
import com.jovoc.facecampresentationrecorder.publish.PublishApi;
import com.jovoc.facecampresentationrecorder.publish.PublishException;
import com.jovoc.facecampresentationrecorder.util.RecordSettings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Auto upload to social media": the next step to take, every supported platform with its
 * connection state, and a footer row into the account screen. Opened from the drawer once signed in.
 */
public class AutoPublishActivity extends AppCompatActivity implements PlatformListAdapter.Listener {

    private static final int COLOR_GREY = 0xFFAAAAAA;
    private static final int COLOR_AMBER = 0xFFFFB347;

    /** Set when the screen should open on the upgrade sheet (the plan has no auto upload). */
    private static final String EXTRA_UPGRADE = "upgrade";

    /** Opens this screen, through sign-in first when there is no session. */
    public static void open(Context context) {
        launch(context, new Intent(context, AutoPublishActivity.class));
    }

    /** As {@link #open}, on the upgrade sheet for auto upload. */
    public static void openUpgrade(Context context) {
        launch(context, new Intent(context, AutoPublishActivity.class).putExtra(EXTRA_UPGRADE, true));
    }

    /** For a notification: the screen on the upgrade sheet for auto upload. */
    public static Intent upgradeIntent(Context context) {
        return new Intent(context, AutoPublishActivity.class).putExtra(EXTRA_UPGRADE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private static void launch(Context context, Intent i) {
        if (SessionManager.get(context).hasSession()) {
            if (!(context instanceof android.app.Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } else {
            SessionManager.openSignIn(context, i);
        }
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private SessionManager session;
    private PublishApi api;
    /** Holds the master switch (KEY_AUTO_PUBLISH) and the undo wait the pipeline reads. */
    private SharedPreferences prefs;
    private View nextStepCard;
    private View stepConnect;
    private View stepEnable;
    private View stepSwitch;
    private Button btnConnect;
    private TextView tvConnectBody;
    private SwitchCompat masterSwitch;
    private ImageView masterCrown;
    private TextView masterTier;
    /** True while the code, not the user, sets the master switch, so that is not saved as a choice. */
    private boolean syncingMaster;
    private View onBox;
    private TextView tvOff;
    private TextView tvTargets;
    private TextView tvCredits;
    private RecyclerView platformList;
    private PlatformListAdapter adapter;
    private ProgressBar progress;
    private View errorBox;
    private TextView tvError;
    private TextView tvPresetNote;
    private TextView tvEmail;
    private boolean created;

    /** The last list the server sent; null until the first load succeeds. */
    private Platforms loaded;
    /** Cost and balance for the status line; null until loaded (or when it could not be). */
    private AutoPublishInfo info;
    private boolean refreshing;
    /** A refresh was asked for while one ran; its answer may predate a change on the web. */
    private boolean refreshAgain;
    /** Channel id -> the value being saved; the switch stays disabled until the PUT answers. */
    private final Map<String, Boolean> saving = new HashMap<>();
    /** Identifier of the platform whose web link is being requested; one at a time. */
    private String linkPending;
    private final Entitlements.Listener planListener = this::syncMasterGate;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_auto_publish);
        session = SessionManager.get(this);
        if (!session.hasSession()) {
            SessionManager.openSignIn(this, new Intent(getIntent()));
            finish();
            return;
        }
        created = true;
        api = new PublishApi(session.repository());
        prefs = getSharedPreferences(RecordSettings.PREF_NAME, MODE_PRIVATE);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        ((ImageButton) findViewById(R.id.btn_back)).setOnClickListener(v -> finish());
        setupNextStepCard();
        platformList = findViewById(R.id.rv_platforms);
        platformList.setLayoutManager(new LinearLayoutManager(this));
        adapter = new PlatformListAdapter(this, saving.keySet());
        platformList.setAdapter(adapter);
        // Rows are rebound in place (switch flips, spinners); a cross-fade would only flicker.
        if (platformList.getItemAnimator() instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) platformList.getItemAnimator()).setSupportsChangeAnimations(false);
        }
        tvPresetNote = findViewById(R.id.tv_preset_note);
        progress = findViewById(R.id.progress_auto_publish);
        errorBox = findViewById(R.id.auto_publish_error_box);
        tvError = findViewById(R.id.tv_auto_publish_error);
        tvEmail = findViewById(R.id.tv_auto_publish_email);

        findViewById(R.id.btn_auto_publish_retry).setOnClickListener(v -> refresh());
        findViewById(R.id.row_account).setOnClickListener(
                v -> startActivity(new Intent(this, AccountActivity.class)));

        // From the "needs a plan" notification or card (not again after a rotation).
        if (savedInstanceState == null && getIntent().getBooleanExtra(EXTRA_UPGRADE, false)) {
            main.post(this::showUpgradeSheet);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!created) return;
        // Signed out on the account screen (or the session ended): nothing to show here.
        if (!session.hasSession()) {
            finish();
            return;
        }
        tvEmail.setText(session.email());
        // The plan may have changed while away (billing page); also follows changes while open.
        syncMasterGate();
        Entitlements.addListener(planListener);
        // The user may come back from the browser after connecting a platform.
        refresh();
    }

    @Override
    protected void onPause() {
        Entitlements.removeListener(planListener);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
    }

    // ---- loading ----

    /**
     * Loads the platforms. The spinner shows only on an empty screen; later refreshes (every
     * onResume) replace the list silently.
     */
    private void refresh() {
        if (refreshing) {
            refreshAgain = true;
            return;
        }
        refreshing = true;
        refreshAgain = false;
        errorBox.setVisibility(View.GONE);
        if (loaded == null) progress.setVisibility(View.VISIBLE);
        io.execute(() -> {
            Platforms result = null;
            PublishException error = null;
            try {
                result = api.platforms();
            } catch (PublishException e) {
                error = e;
            }
            // Only for the card's cost line; the screen works without it.
            AutoPublishInfo summary = result == null ? null : loadInfo();
            final Platforms platforms = result;
            final PublishException err = error;
            main.post(() -> {
                refreshing = false;
                if (isFinishing() || isDestroyed()) return;
                progress.setVisibility(View.GONE);
                if (err != null) {
                    onRefreshFailed(err);
                } else {
                    // A failed cost check keeps the last answer rather than blanking the card.
                    if (summary != null) info = summary;
                    bind(platforms);
                }
                if (refreshAgain) refresh();
            });
        });
    }

    private void onRefreshFailed(PublishException e) {
        if (e.kind == PublishException.Kind.SIGNED_OUT && !session.hasSession()) {
            refreshAgain = false;
            SessionManager.openSignIn(this, new Intent(this, AutoPublishActivity.class));
            finish();
            return;
        }
        if (loaded == null) {
            tvError.setText(R.string.ap_load_failed);
            errorBox.setVisibility(View.VISIBLE);
        } else if (!refreshAgain) {
            Toast.makeText(this, R.string.ap_refresh_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void bind(Platforms platforms) {
        // A switch still being saved keeps the value the user chose, not the older server copy.
        for (Platform p : platforms.platforms) {
            for (ChannelState c : p.channels) {
                Boolean pending = saving.get(c.id);
                if (pending != null) c.autoPublish = pending;
            }
        }
        loaded = platforms;
        if (platforms.usesPreset() && !platforms.presetName.isEmpty()) {
            tvPresetNote.setText(getString(R.string.ap_preset_note, platforms.presetName));
            tvPresetNote.setVisibility(View.VISIBLE);
        } else {
            tvPresetNote.setVisibility(View.GONE);
        }
        adapter.submit(platforms);
        onPlatformsLoaded(platforms);
    }

    /** GET /auto-publish on the io thread; null when it fails. */
    @Nullable
    private AutoPublishInfo loadInfo() {
        try {
            return api.autoPublishInfo();
        } catch (PublishException e) {
            return null;
        }
    }

    /**
     * After a channel switch was saved the cost may have changed: refetch it and redraw the card.
     * The executor is single-threaded, so this answer never lands before an older one.
     */
    private void reloadInfo() {
        io.execute(() -> {
            AutoPublishInfo fresh = loadInfo();
            main.post(() -> {
                if (isFinishing() || isDestroyed() || fresh == null || loaded == null) return;
                info = fresh;
                onPlatformsLoaded(loaded);
            });
        });
    }

    // ---- next-step card ----

    private void setupNextStepCard() {
        nextStepCard = findViewById(R.id.card_next_step);
        stepConnect = findViewById(R.id.next_step_connect);
        stepEnable = findViewById(R.id.next_step_enable);
        stepSwitch = findViewById(R.id.next_step_switch);
        btnConnect = findViewById(R.id.btn_next_connect);
        tvConnectBody = findViewById(R.id.tv_next_connect_body);
        masterSwitch = findViewById(R.id.switch_auto_publish);
        masterCrown = findViewById(R.id.iv_premium_auto_publish);
        masterTier = findViewById(R.id.tv_tier_auto_publish);
        onBox = findViewById(R.id.next_on_box);
        tvOff = findViewById(R.id.tv_next_off);
        tvTargets = findViewById(R.id.tv_next_targets);
        tvCredits = findViewById(R.id.tv_next_credits);

        syncMasterGate();
        // The same pref PublishManager.beginAuto reads when a recording stops.
        masterSwitch.setOnCheckedChangeListener((b, checked) -> {
            if (syncingMaster) return;
            // A locked switch stays off: the tap opens the upgrade sheet, and the switch turns on
            // if the user upgrades. The stored choice is never overwritten by a lock.
            if (checked && !Entitlements.has(Feature.AUTO_PUBLISH)) {
                showMaster(false);
                Gate.run(this, Feature.AUTO_PUBLISH, () -> masterSwitch.setChecked(true));
                return;
            }
            prefs.edit().putBoolean(RecordSettings.KEY_AUTO_PUBLISH, checked).apply();
            ActivityLog.log("autopublish_toggle", "on", checked);
            showMasterState(checked);
        });
        // The whole row is the touch target, not just the small switch.
        findViewById(R.id.row_auto_publish_master).setOnClickListener(v -> masterSwitch.toggle());

        // "Undo before publishing": Off / 30 s / 60 s, default 30 s.
        RadioGroup undo = findViewById(R.id.rg_ap_undo);
        int seconds = AutoPublishUndo.normalize(prefs.getInt(
                RecordSettings.KEY_AUTO_PUBLISH_UNDO_SECONDS, AutoPublishUndo.DEFAULT_SECONDS));
        undo.check(seconds == AutoPublishUndo.OFF ? R.id.rb_ap_undo_0
                : seconds == AutoPublishUndo.MAX_SECONDS ? R.id.rb_ap_undo_60
                : R.id.rb_ap_undo_30);
        undo.setOnCheckedChangeListener((g, id) -> {
            int value = id == R.id.rb_ap_undo_0 ? AutoPublishUndo.OFF
                    : id == R.id.rb_ap_undo_60 ? AutoPublishUndo.MAX_SECONDS
                    : AutoPublishUndo.DEFAULT_SECONDS;
            prefs.edit().putInt(RecordSettings.KEY_AUTO_PUBLISH_UNDO_SECONDS, value).apply();
            ActivityLog.log("undo_choice", "seconds", value);
        });
    }

    /**
     * Called on the main thread whenever the platform list changes: after each successful load,
     * and after a channel switch was saved. Shows the one step that moves the user forward:
     * connect a platform, then switch a channel on, then the master switch.
     */
    private void onPlatformsLoaded(Platforms platforms) {
        if (!platforms.anyConnected()) {
            Platform first = firstToConnect(platforms);
            if (first == null) {
                nextStepCard.setVisibility(View.GONE);
                return;
            }
            btnConnect.setText(getString(R.string.ap_next_connect_button, first.name));
            // Recommend YouTube only when the button actually connects it.
            tvConnectBody.setText("youtube".equals(first.identifier)
                    ? R.string.ap_next_connect_body_youtube : R.string.ap_next_connect_body);
            btnConnect.setOnClickListener(v -> startPlatformAction(first));
            showStep(stepConnect);
            return;
        }
        // With a web preset only the server knows where posts go; without its answer, say nothing.
        if (platforms.usesPreset() && info == null) {
            nextStepCard.setVisibility(View.GONE);
            return;
        }
        List<String> targets = targetNames(platforms);
        if (targets.isEmpty()) {
            showStep(stepEnable);
            return;
        }
        boolean wasShown = stepSwitch.getVisibility() == View.VISIBLE
                && nextStepCard.getVisibility() == View.VISIBLE;
        showStep(stepSwitch);
        // Snap a switch that appears; keep the animation of one the user is looking at.
        if (!wasShown) masterSwitch.jumpDrawablesToCurrentState();
        showStatus(targets);
    }

    private void showStep(View step) {
        stepConnect.setVisibility(step == stepConnect ? View.VISIBLE : View.GONE);
        stepEnable.setVisibility(step == stepEnable ? View.VISIBLE : View.GONE);
        stepSwitch.setVisibility(step == stepSwitch ? View.VISIBLE : View.GONE);
        nextStepCard.setVisibility(View.VISIBLE);
    }

    /** YouTube when the server offers it, else the first video platform, else the first one. */
    @Nullable
    private static Platform firstToConnect(Platforms platforms) {
        Platform youtube = platforms.find("youtube");
        if (youtube != null && !youtube.isConnected()) return youtube;
        Platform fallback = null;
        for (Platform p : platforms.platforms) {
            if (p.isConnected()) continue;
            if (p.isVideo()) return p;
            if (fallback == null) fallback = p;
        }
        return fallback;
    }

    /**
     * Platforms a new recording would be posted to: those with a channel switched on that can
     * post (not paused, no reconnect needed). With a web preset, the server's list instead.
     */
    private List<String> targetNames(Platforms platforms) {
        if (platforms.usesPreset()) {
            return info != null && info.configured ? info.providerNames() : new ArrayList<>();
        }
        Set<String> names = new LinkedHashSet<>();
        for (Platform p : platforms.platforms) {
            if (!p.isConnected() || !p.autoPublishSupported) continue;
            for (ChannelState c : p.channels) {
                if (c.autoPublish && !c.disabled && !c.refreshNeeded) {
                    names.add(p.name);
                    break;
                }
            }
        }
        return new ArrayList<>(names);
    }

    private void showMasterState(boolean on) {
        onBox.setVisibility(on ? View.VISIBLE : View.GONE);
        tvOff.setVisibility(on ? View.GONE : View.VISIBLE);
    }

    /** Sets the switch without saving it as a choice. */
    private void showMaster(boolean on) {
        syncingMaster = true;
        masterSwitch.setChecked(on);
        syncingMaster = false;
        showMasterState(on);
    }

    /**
     * The switch shows the stored choice only while the plan covers it (off while locked, but the
     * choice is kept and returns with the plan), with the premium look for the current plan.
     */
    private void syncMasterGate() {
        showMaster(prefs.getBoolean(RecordSettings.KEY_AUTO_PUBLISH, false)
                && Entitlements.has(Feature.AUTO_PUBLISH));
        PremiumMark.apply(masterCrown, masterTier, Feature.AUTO_PUBLISH);
    }

    /** Opened from the "needs a plan" notification or card: say what auto upload needs. */
    private void showUpgradeSheet() {
        if (isFinishing() || isDestroyed()) return;
        PaywallSheet.show(this, Feature.AUTO_PUBLISH, this::syncMasterGate);
    }

    /** "Posts to YouTube, TikTok", then the cost per recording and the balance (amber when short). */
    private void showStatus(List<String> targets) {
        tvTargets.setText(getString(R.string.ap_next_posts_to, String.join(", ", targets)));
        if (info == null) {
            tvCredits.setVisibility(View.GONE);
            return;
        }
        tvCredits.setVisibility(View.VISIBLE);
        if (info.cost == 0) {
            tvCredits.setText(R.string.ap_next_cost_free);
            tvCredits.setTextColor(COLOR_GREY);
            return;
        }
        String each = getResources().getQuantityString(R.plurals.ap_next_cost, plural(info.cost),
                info.cost);
        boolean enough = info.canAfford();
        tvCredits.setText(getString(enough ? R.string.ap_next_credits : R.string.ap_next_credits_short,
                each, info.available));
        tvCredits.setTextColor(enough ? COLOR_GREY : COLOR_AMBER);
    }

    /** Plural selector that fits an int; amounts beyond it all read as "other". */
    private static int plural(long n) {
        return n > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) n;
    }

    /** The last loaded platform list, or null before the first successful load. */
    @Nullable
    public Platforms currentPlatforms() {
        return loaded;
    }

    // ---- actions ----

    @Override
    public void onPlatformAction(Platform platform) {
        startPlatformAction(platform);
    }

    /**
     * Takes the user to the web page that moves {@code platform} forward: the app-keys page when
     * the workspace still needs keys for it, else its connect flow. A member who cannot add keys
     * is told to ask the owner. The link is single use, so it is requested on every tap.
     */
    public void startPlatformAction(Platform platform) {
        if (platform == null || linkPending != null || isFinishing() || isDestroyed()) return;
        boolean credentials = platform.needsCredentials();
        ActivityLog.log("platform_tap", "platform", platform.identifier, "state", platform.status);
        if (credentials && loaded != null && !loaded.canManageCredentials) {
            new MaterialAlertDialogBuilder(this)
                    .setMessage(getString(R.string.ap_ask_owner, platform.name))
                    .setPositiveButton(R.string.ap_ok, null)
                    .show();
            return;
        }
        String target = credentials ? PublishApi.LINK_CREDENTIALS : PublishApi.LINK_CONNECT;
        linkPending = platform.identifier;
        adapter.setBusy(platform.identifier);
        io.execute(() -> {
            String url = null;
            PublishException error = null;
            try {
                url = api.webLink(target, platform.identifier);
            } catch (PublishException e) {
                error = e;
            }
            final String link = url;
            final PublishException err = error;
            main.post(() -> {
                linkPending = null;
                if (isFinishing() || isDestroyed()) return;
                adapter.setBusy(null);
                if (link != null) {
                    WebLinks.open(this, link);
                } else {
                    Toast.makeText(this, linkFailure(err, platform), Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private String linkFailure(PublishException e, Platform platform) {
        if (e != null && e.kind == PublishException.Kind.NETWORK) return getString(R.string.err_offline);
        if (e != null && e.kind == PublishException.Kind.RATE_LIMITED) {
            return getString(R.string.err_rate_limited_unknown);
        }
        return getString(R.string.ap_link_failed, platform.name);
    }

    /** Optimistic: the switch moves at once and goes back if the server does not take it. */
    @Override
    public void onChannelToggled(ChannelState channel, boolean enabled) {
        if (saving.containsKey(channel.id) || channel.autoPublish == enabled) return;
        channel.autoPublish = enabled;
        ActivityLog.log("channel_toggle", "on", enabled);
        saving.put(channel.id, enabled);
        adapter.notifyChannel(channel.id);
        String id = channel.id;
        io.execute(() -> {
            boolean ok;
            boolean gone = false;
            try {
                api.setChannelAutoPublish(id, enabled);
                ok = true;
            } catch (PublishException e) {
                ok = false;
                // 404: the channel was removed on the web.
                gone = e.status == 404;
            }
            final boolean saved = ok;
            final boolean deleted = gone;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                saving.remove(id);
                // A refresh may have replaced the list meanwhile: update the channel shown now.
                ChannelState current = findChannel(id);
                if (current != null) current.autoPublish = saved ? enabled : !enabled;
                if (!saved) {
                    Toast.makeText(this, R.string.ap_save_failed, Toast.LENGTH_SHORT).show();
                }
                adapter.notifyChannel(id);
                if (saved && loaded != null) {
                    onPlatformsLoaded(loaded);
                    reloadInfo();
                }
                // Reload so the deleted channel disappears instead of staying stale.
                if (deleted) refresh();
            });
        });
    }

    @Nullable
    private ChannelState findChannel(String id) {
        if (loaded == null) return null;
        for (Platform p : loaded.platforms) {
            for (ChannelState c : p.channels) if (c.id.equals(id)) return c;
        }
        return null;
    }
}
