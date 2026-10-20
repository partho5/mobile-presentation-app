package com.jovoc.facecampresentationrecorder.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.auth.SessionManager;
import com.jovoc.facecampresentationrecorder.publish.Platforms;
import com.jovoc.facecampresentationrecorder.publish.Platforms.ChannelState;
import com.jovoc.facecampresentationrecorder.publish.Platforms.Platform;
import com.jovoc.facecampresentationrecorder.publish.PublishApi;
import com.jovoc.facecampresentationrecorder.publish.PublishException;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Auto upload to social media": the next step to take, every supported platform with its
 * connection state, and a footer row into the account screen. Opened from the drawer once signed in.
 */
public class AutoPublishActivity extends AppCompatActivity implements PlatformListAdapter.Listener {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private SessionManager session;
    private PublishApi api;
    private View nextStepCard;
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
    private boolean refreshing;
    /** A refresh was asked for while one ran; its answer may predate a change on the web. */
    private boolean refreshAgain;
    /** Channel id -> the value being saved; the switch stays disabled until the PUT answers. */
    private final Map<String, Boolean> saving = new HashMap<>();
    /** Identifier of the platform whose web link is being requested; one at a time. */
    private String linkPending;

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

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        ((ImageButton) findViewById(R.id.btn_back)).setOnClickListener(v -> finish());
        nextStepCard = findViewById(R.id.card_next_step);
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
        // The user may come back from the browser after connecting a platform.
        refresh();
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
            final Platforms platforms = result;
            final PublishException err = error;
            main.post(() -> {
                refreshing = false;
                if (isFinishing() || isDestroyed()) return;
                progress.setVisibility(View.GONE);
                if (err != null) {
                    onRefreshFailed(err);
                } else {
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

    /**
     * Called on the main thread whenever the platform list changes: after each successful load,
     * and after a channel switch was saved. The next-step card (M3) is built from here.
     */
    private void onPlatformsLoaded(Platforms platforms) {
        // M3: next-step card.
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
        saving.put(channel.id, enabled);
        adapter.notifyChannel(channel.id);
        String id = channel.id;
        io.execute(() -> {
            boolean ok;
            try {
                api.setChannelAutoPublish(id, enabled);
                ok = true;
            } catch (PublishException e) {
                ok = false;
            }
            final boolean saved = ok;
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
                if (saved && loaded != null) onPlatformsLoaded(loaded);
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
