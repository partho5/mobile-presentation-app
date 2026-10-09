package com.jovoc.facecampresentationrecorder.ui;

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

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.account.AccountApi;
import com.jovoc.facecampresentationrecorder.account.AccountException;
import com.jovoc.facecampresentationrecorder.account.Workspace;
import com.jovoc.facecampresentationrecorder.auth.SessionManager;
import com.jovoc.facecampresentationrecorder.auth.TokenStore;
import com.jovoc.facecampresentationrecorder.publish.PublishManager;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lists the user's workspaces and lets them pick the one this phone works in. The pick is sent as
 * {@code x-workspace-id} on every call and applies to publishing and credits.
 */
public class WorkspaceActivity extends AppCompatActivity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private SessionManager session;
    private AccountApi api;
    private ProgressBar progress;
    private View messageBox;
    private TextView tvMessage;
    private LinearLayout container;
    /** The workspace the server resolves for this phone while no pick is stored; may be null. */
    private String serverDefaultId;

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

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        ((ImageButton) findViewById(R.id.btn_back)).setOnClickListener(v -> finish());
        progress = findViewById(R.id.progress_workspaces);
        messageBox = findViewById(R.id.workspaces_message_box);
        tvMessage = findViewById(R.id.tv_workspaces_message);
        container = findViewById(R.id.workspaces_container);
        ((Button) findViewById(R.id.btn_workspaces_retry)).setOnClickListener(v -> load());
        load();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
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
                    showWorkspaces(result, current);
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
            row.setOnClickListener(v -> pick(w, isCurrent));
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

    private void pick(Workspace w, boolean isCurrent) {
        TokenStore store = session.repository().store();
        if (!isCurrent || store.workspaceId() == null) {
            // Jobs queued under the server's default must not follow the switch.
            if (store.workspaceId() == null && serverDefaultId != null) {
                PublishManager.get(this).pinDefaultWorkspace(serverDefaultId);
            }
            store.saveWorkspace(w.id, w.name);
            Toast.makeText(getApplicationContext(), getString(R.string.ws_switched, w.name),
                    Toast.LENGTH_SHORT).show();
        }
        setResult(RESULT_OK);
        finish();
    }
}
