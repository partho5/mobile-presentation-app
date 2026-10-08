package com.jovoc.facecampresentationrecorder.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.jovoc.facecampresentationrecorder.BuildConfig;
import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.account.AccountApi;
import com.jovoc.facecampresentationrecorder.account.AccountException;
import com.jovoc.facecampresentationrecorder.account.CreditBalance;
import com.jovoc.facecampresentationrecorder.account.Workspace;
import com.jovoc.facecampresentationrecorder.auth.AuthException;
import com.jovoc.facecampresentationrecorder.auth.SessionManager;

import java.text.NumberFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Who is signed in on this device, the workspace and credit balance it works with, the devices
 * list, and sign out.
 */
public class AccountActivity extends AppCompatActivity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private SessionManager session;
    private AccountApi api;
    private Button btnSignOut;
    private ProgressBar progress;
    private TextView tvWorkspace;
    private TextView tvCredits;
    private int generation;
    private boolean created;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_account);
        session = SessionManager.get(this);
        if (!session.hasSession()) {
            SessionManager.openSignIn(this);
            return;
        }
        created = true;
        api = new AccountApi(session.repository());

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        ((ImageButton) findViewById(R.id.btn_back)).setOnClickListener(v -> finish());
        ((TextView) findViewById(R.id.tv_account_email)).setText(session.email());
        ((TextView) findViewById(R.id.tv_account_device)).setText(session.deviceName());
        tvWorkspace = findViewById(R.id.tv_account_workspace);
        tvCredits = findViewById(R.id.tv_account_credits);
        progress = findViewById(R.id.progress_account);
        btnSignOut = findViewById(R.id.btn_sign_out);

        findViewById(R.id.row_workspace).setOnClickListener(
                v -> startActivity(new Intent(this, WorkspaceActivity.class)));
        findViewById(R.id.row_devices).setOnClickListener(
                v -> startActivity(new Intent(this, DevicesActivity.class)));
        findViewById(R.id.row_credits).setOnClickListener(v -> refresh(false));
        setUpBackendRow();

        btnSignOut.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.account_sign_out_confirm)
                .setPositiveButton(R.string.account_sign_out, (d, w) -> signOut())
                .setNegativeButton(android.R.string.cancel, null)
                .show());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The workspace may have changed on the picker screen, and the balance with it.
        if (created && session.hasSession()) refresh(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        generation++;
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
    }

    // ---- workspace + balance ----

    private void refresh(boolean afterForbidden) {
        final int mine = ++generation;
        tvCredits.setText(R.string.acct_credits_loading);
        String picked = session.repository().store().workspaceName();
        if (session.repository().store().workspaceId() != null && picked != null && !picked.isEmpty()) {
            tvWorkspace.setText(picked);
        } else {
            tvWorkspace.setText(R.string.acct_workspace_default);
        }
        io.execute(() -> {
            final String name = resolveDefaultName();
            main.post(() -> {
                if (mine == generation && name != null) tvWorkspace.setText(name);
            });

            CreditBalance balance = null;
            AccountException error = null;
            try {
                balance = api.balance();
            } catch (AccountException e) {
                error = e;
            }
            final CreditBalance result = balance;
            final AccountException err = error;
            main.post(() -> {
                if (mine != generation || isFinishing() || isDestroyed()) return;
                if (err == null) {
                    tvCredits.setText(describe(result));
                } else if (err.kind == AccountException.Kind.WORKSPACE_FORBIDDEN && !afterForbidden) {
                    // The pick was dropped by the client; say so, then show the default workspace.
                    Toast.makeText(getApplicationContext(), ApiErrors.message(this, err),
                            Toast.LENGTH_LONG).show();
                    refresh(true);
                } else if (err.kind != AccountException.Kind.SIGNED_OUT) {
                    // SIGNED_OUT: the session manager is already opening the sign-in screen.
                    tvCredits.setText(R.string.acct_credits_unavailable);
                }
            });
        });
    }

    /**
     * Name of the workspace the server uses when none is picked; null when a pick exists (its
     * name is stored) or the lookup failed (the placeholder stays).
     */
    private String resolveDefaultName() {
        if (session.repository().store().workspaceId() != null) return null;
        try {
            String id = api.currentWorkspaceId();
            for (Workspace w : api.listWorkspaces()) {
                if (w.id.equals(id)) return w.name;
            }
        } catch (AccountException ignored) {
            // the balance call below reports connection problems
        }
        return null;
    }

    private String describe(CreditBalance b) {
        NumberFormat nf = NumberFormat.getInstance();
        String available = nf.format(b.available);
        String text = b.hasReserved()
                ? getString(R.string.acct_credits_available_reserved, available, nf.format(b.reserved))
                : getString(R.string.acct_credits_available, available);
        return b.low ? getString(R.string.acct_credits_low, text) : text;
    }

    // ---- debug-only backend URL ----

    private void setUpBackendRow() {
        View row = findViewById(R.id.row_backend);
        if (!BuildConfig.DEBUG) return;
        row.setVisibility(View.VISIBLE);
        ((TextView) findViewById(R.id.tv_account_backend)).setText(session.baseUrl());
        row.setOnClickListener(v -> {
            EditText input = new EditText(this);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            input.setText(session.baseUrl());
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.acct_backend_title)
                    .setMessage(R.string.acct_backend_message)
                    .setView(input)
                    .setPositiveButton(R.string.acct_backend_save, (d, w) -> {
                        if (session.changeBaseUrl(input.getText().toString())) {
                            SessionManager.openSignIn(this);
                        } else {
                            Toast.makeText(this, R.string.acct_backend_invalid, Toast.LENGTH_LONG).show();
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });
    }

    // ---- sign out ----

    private void signOut() {
        btnSignOut.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        session.signOut(new SessionManager.Callback<Boolean>() {
            @Override
            public void onResult(Boolean revokedOnServer) {
                if (!revokedOnServer) {
                    Toast.makeText(getApplicationContext(), R.string.account_sign_out_offline,
                            Toast.LENGTH_LONG).show();
                }
                SessionManager.openSignIn(AccountActivity.this);
            }

            @Override
            public void onError(AuthException error) {
                SessionManager.openSignIn(AccountActivity.this);
            }
        });
    }
}
