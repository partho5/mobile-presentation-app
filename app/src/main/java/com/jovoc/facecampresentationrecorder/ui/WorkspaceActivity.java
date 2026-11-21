package com.jovoc.facecampresentationrecorder.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.TaskStackBuilder;
import androidx.core.content.IntentCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.jovoc.facecampresentationrecorder.MainActivity;
import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.account.AccountApi;
import com.jovoc.facecampresentationrecorder.account.AccountException;
import com.jovoc.facecampresentationrecorder.account.BrandSwitch;
import com.jovoc.facecampresentationrecorder.account.Workspace;
import com.jovoc.facecampresentationrecorder.tracker.ActivityTracker;
import com.jovoc.facecampresentationrecorder.auth.SessionManager;
import com.jovoc.facecampresentationrecorder.auth.TokenStore;
import com.jovoc.facecampresentationrecorder.entitlements.Entitlements;
import com.jovoc.facecampresentationrecorder.entitlements.EntitlementsRefresher;
import com.jovoc.facecampresentationrecorder.entitlements.FeatureSnapshot;
import com.jovoc.facecampresentationrecorder.publish.PublishManager;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lists the user's brands and lets them pick the one this phone works in. The pick is sent as
 * {@code x-workspace-id} on every call and applies to publishing, credits and the plan.
 *
 * <p>A change restarts the app's screens on the new brand: the task is cleared, MainActivity starts,
 * then the screen the picker was opened from ({@link #EXTRA_RETURN_TO}), rebuilt from the intent it
 * was started with. Running uploads and AI edits are services and keep going.
 */
public class WorkspaceActivity extends AppCompatActivity {

    /** The screen to return to after a change: the intent the launching screen was started with. */
    public static final String EXTRA_RETURN_TO = "return_to";
    /** How long a change waits for the new brand's plan; slower or offline, it goes on without it. */
    static final long PLAN_WAIT_MS = 2000L;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private SessionManager session;
    private AccountApi api;
    private ProgressBar progress;
    private View messageBox;
    private TextView tvMessage;
    private LinearLayout container;
    /** The brand the server resolves for this phone while no pick is stored; may be null. */
    private String serverDefaultId;
    /** The screen to return to after a change, or null: then MainActivity alone. */
    private Intent returnTo;
    /** True from a change until the screens restart: taps and Back do nothing meanwhile. */
    private boolean switching;
    private static final String STATE_SWITCHING = "switching";

    /** The picker, opened from a screen that passes its own intent so a change can return there. */
    public static Intent open(Context context, Intent returnTo) {
        Intent intent = new Intent(context, WorkspaceActivity.class);
        if (returnTo != null) intent.putExtra(EXTRA_RETURN_TO, new Intent(returnTo));
        return intent;
    }

    /**
     * For a screen whose brand has refused this phone: opens the picker in place of the screen. After a
     * choice the picker restarts the screens and this one comes back, rebuilt from its intent.
     * @return true when the picker was opened (the caller must stop what it was doing)
     */
    public static boolean openIfAccessLost(AppCompatActivity activity) {
        if (!SessionManager.get(activity).repository().store().workspaceAccessLost()) return false;
        activity.startActivity(open(activity, activity.getIntent()));
        activity.finish();
        return true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_workspace);
        session = SessionManager.get(this);
        if (!session.hasSession()) {
            SessionManager.openSignIn(this, new Intent(getIntent()));
            finish();
            return;
        }
        api = new AccountApi(session.repository());
        returnTo = IntentCompat.getParcelableExtra(getIntent(), EXTRA_RETURN_TO, Intent.class);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        ((ImageButton) findViewById(R.id.btn_back)).setOnClickListener(v -> {
            if (!switching) finish();
        });
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (switching) return;
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        });
        progress = findViewById(R.id.progress_workspaces);
        messageBox = findViewById(R.id.workspaces_message_box);
        tvMessage = findViewById(R.id.tv_workspaces_message);
        container = findViewById(R.id.workspaces_container);
        ((Button) findViewById(R.id.btn_workspaces_retry)).setOnClickListener(v -> load());
        // A rotation during a change: the change's own restart still comes, so no second change may start.
        switching = savedInstanceState != null && savedInstanceState.getBoolean(STATE_SWITCHING, false);
        if (switching) progress.setVisibility(View.VISIBLE);
        else load();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_SWITCHING, switching);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // A change in progress still restarts the screens when its wait ends, so its callback stays,
        // and its wait is not cut short: shutdown lets the running task finish.
        if (!switching) main.removeCallbacksAndMessages(null);
        if (switching) io.shutdown();
        else io.shutdownNow();
    }

    private void load() {
        progress.setVisibility(View.VISIBLE);
        messageBox.setVisibility(View.GONE);
        container.removeAllViews();
        io.execute(() -> {
            List<Workspace> list = null;
            String currentId = session.repository().store().workspaceId();
            AccountException error = null;
            try {
                list = api.listWorkspaces();
                if (currentId == null) {
                    try {
                        currentId = api.currentWorkspaceId();
                    } catch (AccountException e) {
                        if (e.kind == AccountException.Kind.NETWORK) throw e;
                        // Not knowing which one is current only loses the "Current" mark.
                    }
                    serverDefaultId = currentId;
                }
            } catch (AccountException e) {
                error = e;
                ActivityTracker.error("workspace", "list failed", e);
            }
            final List<Workspace> result = list;
            final String current = currentId;
            final AccountException err = error;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                progress.setVisibility(View.GONE);
                if (err != null) {
                    showMessage(ApiErrors.message(this, err));
                } else if (result.isEmpty()) {
                    showMessage(getString(R.string.ws_empty));
                } else {
                    // A brand that refused this phone is not "the one in use": no Current mark.
                    boolean lost = session.repository().store().workspaceAccessLost();
                    showWorkspaces(result, lost ? null : current);
                }
            });
        });
    }

    private void showMessage(String text) {
        tvMessage.setText(text);
        messageBox.setVisibility(View.VISIBLE);
    }

    private void showWorkspaces(List<Workspace> list, String currentId) {
        LayoutInflater inflater = LayoutInflater.from(this);
        for (Workspace w : list) {
            View row = inflater.inflate(R.layout.item_workspace, container, false);
            ((TextView) row.findViewById(R.id.tv_workspace_name)).setText(w.name);
            TextView role = row.findViewById(R.id.tv_workspace_role);
            String roleText = roleLabel(w.role);
            role.setText(roleText);
            role.setVisibility(roleText.isEmpty() ? View.GONE : View.VISIBLE);
            boolean isCurrent = w.id.equals(currentId);
            row.findViewById(R.id.tv_workspace_current).setVisibility(isCurrent ? View.VISIBLE : View.GONE);
            row.setOnClickListener(v -> pick(w, row));
            container.addView(row);
        }
    }

    private String roleLabel(String role) {
        switch (role == null ? "" : role) {
            case "SUPERADMIN":
                return getString(R.string.ws_role_owner);
            case "ADMIN":
                return getString(R.string.ws_role_admin);
            case "USER":
                return getString(R.string.ws_role_member);
            default:
                return "";
        }
    }

    private void pick(Workspace w, View row) {
        if (switching) return;
        TokenStore store = session.repository().store();
        String stored = store.workspaceId();
        BrandSwitch.Outcome outcome = BrandSwitch.decide(stored, w.id, serverDefaultId,
                FeatureSnapshot.recordingNow(), store.workspaceAccessLost());
        if (outcome == BrandSwitch.Outcome.BLOCKED_RECORDING) {
            Toast.makeText(getApplicationContext(), R.string.brand_locked_note, Toast.LENGTH_SHORT).show();
            return;
        }
        // Jobs queued under the server's default must not follow the switch.
        if (stored == null && serverDefaultId != null) {
            PublishManager.get(this).pinDefaultWorkspace(serverDefaultId);
        }
        if (outcome == BrandSwitch.Outcome.SAME) {
            if (stored == null) {
                // The same brand under a new name: its plan token was kept for the default, so fetch it again.
                store.saveWorkspace(w.id, w.name);
                EntitlementsRefresher.refreshNow();
            }
            setResult(RESULT_OK);
            finish();
            return;
        }

        switching = true;
        TextView current = row.findViewById(R.id.tv_workspace_current);
        current.setText(R.string.ws_switching);
        current.setVisibility(View.VISIBLE);
        ActivityTracker.log("click", "switch brand");
        store.saveWorkspace(w.id, w.name);
        // The old brand's plan stops counting now; the new brand's counts once it has been fetched.
        Entitlements.brandChanged();
        io.execute(() -> {
            EntitlementsRefresher.refreshAndWait(PLAN_WAIT_MS);
            main.post(() -> {
                Toast.makeText(getApplicationContext(), getString(R.string.ws_switched, w.name),
                        Toast.LENGTH_SHORT).show();
                restart();
            });
        });
    }

    /** Clears the task, then starts MainActivity and the screen to return to, rebuilt from its own intent. */
    private void restart() {
        Intent home = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        TaskStackBuilder stack = TaskStackBuilder.create(getApplicationContext()).addNextIntent(home);
        if (returnTo != null) stack.addNextIntent(returnTo);
        stack.startActivities();
    }
}
