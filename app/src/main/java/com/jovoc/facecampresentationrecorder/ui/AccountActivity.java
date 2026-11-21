package com.jovoc.facecampresentationrecorder.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.account.AccountApi;
import com.jovoc.facecampresentationrecorder.account.AccountException;
import com.jovoc.facecampresentationrecorder.account.CreditBalance;
import com.jovoc.facecampresentationrecorder.tracker.ActivityTracker;
import com.jovoc.facecampresentationrecorder.auth.AuthException;
import com.jovoc.facecampresentationrecorder.auth.SessionManager;
import com.jovoc.facecampresentationrecorder.entitlements.Entitlements;

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
    private Button btnDelete;
    private ProgressBar progress;
    private BrandRowBinder brandRow;
    private TextView tvPlan;
    private TextView tvCredits;
    private final Entitlements.Listener planListener = this::showPlan;
    private int generation;
    private boolean created;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_account);
        session = SessionManager.get(this);
        if (!session.hasSession()) {
            SessionManager.openSignIn(this, new Intent(getIntent()));
            finish();
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
        brandRow = new BrandRowBinder(this, findViewById(R.id.row_brand), io,
                getString(R.string.acct_workspace_label));
        tvPlan = findViewById(R.id.tv_account_plan);
        tvCredits = findViewById(R.id.tv_account_credits);
        progress = findViewById(R.id.progress_account);
        btnSignOut = findViewById(R.id.btn_sign_out);

        findViewById(R.id.row_plan).setOnClickListener(v -> {
            ActivityTracker.log("click", "account plan");
            PlansSheet.show(this);
        });
        findViewById(R.id.row_devices).setOnClickListener(v -> {
            ActivityTracker.log("click", "account devices");
            startActivity(new Intent(this, DevicesActivity.class));
        });
        findViewById(R.id.row_credits).setOnClickListener(v -> {
            ActivityTracker.log("click", "account credits");
            refresh();
        });

        btnDelete = findViewById(R.id.btn_delete_account);
        btnDelete.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.acct_delete_title)
                .setMessage(R.string.acct_delete_confirm)
                .setPositiveButton(R.string.acct_delete_yes, (d, w) -> deleteAccount())
                .setNegativeButton(android.R.string.cancel, null)
                .show());
        bindLegalLink(R.id.btn_privacy, WebLinks.privacyUrl());
        bindLegalLink(R.id.btn_terms, WebLinks.termsUrl());

        btnSignOut.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.account_sign_out_confirm)
                .setPositiveButton(R.string.account_sign_out, (d, w) -> signOut())
                .setNegativeButton(android.R.string.cancel, null)
                .show());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The brand may have changed on the picker screen, and the balance with it.
        if (created && session.hasSession()) {
            brandRow.refresh();
            refresh();
        }
        if (created) {
            showPlan();
            Entitlements.addListener(planListener);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Entitlements.removeListener(planListener);
    }

    /** The "Plan" row: the name of the plan in force; follows a refresh or a purchase. */
    private void showPlan() {
        tvPlan.setText(Entitlements.planName());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        generation++;
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
    }

    // ---- balance ----

    private void refresh() {
        final int mine = ++generation;
        tvCredits.setText(R.string.acct_credits_loading);
        io.execute(() -> {
            CreditBalance balance = null;
            AccountException error = null;
            try {
                balance = api.balance();
            } catch (AccountException e) {
                error = e;
                ActivityTracker.error("account", "credits failed", e);
            }
            final CreditBalance result = balance;
            final AccountException err = error;
            main.post(() -> {
                if (mine != generation || isFinishing() || isDestroyed()) return;
                if (err == null) {
                    tvCredits.setText(describe(result));
                } else if (err.kind == AccountException.Kind.WORKSPACE_FORBIDDEN) {
                    // The brand refused this phone: the row now asks for a choice; nothing falls back.
                    brandRow.refresh();
                    Toast.makeText(getApplicationContext(), ApiErrors.message(this, err),
                            Toast.LENGTH_LONG).show();
                    tvCredits.setText(R.string.acct_credits_unavailable);
                } else if (err.kind != AccountException.Kind.SIGNED_OUT) {
                    // SIGNED_OUT: the session manager is already opening the sign-in screen.
                    tvCredits.setText(R.string.acct_credits_unavailable);
                }
            });
        });
    }

    private String describe(CreditBalance b) {
        NumberFormat nf = NumberFormat.getInstance();
        String available = nf.format(b.available);
        String text = b.hasReserved()
                ? getString(R.string.acct_credits_available_reserved, available, nf.format(b.reserved))
                : getString(R.string.acct_credits_available, available);
        return b.low ? getString(R.string.acct_credits_low, text) : text;
    }

    /** Shows the link only when its URL is configured. */
    private void bindLegalLink(int viewId, String url) {
        View v = findViewById(viewId);
        if (url == null || url.isEmpty()) {
            v.setVisibility(View.GONE);
            return;
        }
        v.setOnClickListener(x -> WebLinks.open(this, url));
    }

    // ---- delete account ----

    private void deleteAccount() {
        ActivityTracker.log("click", "delete account");
        btnSignOut.setEnabled(false);
        btnDelete.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        io.execute(() -> {
            AccountException error = null;
            try {
                api.deleteAccount();
            } catch (AccountException e) {
                error = e;
                ActivityTracker.error("account", "delete failed", e);
            }
            final AccountException err = error;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (err == null) {
                    // The server ended every session of the account; drop the local copy and go home.
                    session.repository().endLocalSession();
                    Toast.makeText(getApplicationContext(), R.string.acct_delete_done, Toast.LENGTH_LONG).show();
                    SessionManager.openHome(this);
                    return;
                }
                btnSignOut.setEnabled(true);
                btnDelete.setEnabled(true);
                progress.setVisibility(View.GONE);
                // SIGNED_OUT: the session manager is already taking the user home.
                if (err.kind != AccountException.Kind.SIGNED_OUT) {
                    Toast.makeText(this, ApiErrors.message(this, err), Toast.LENGTH_LONG).show();
                }
            });
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
                SessionManager.openHome(AccountActivity.this);
            }

            @Override
            public void onError(AuthException error) {
                SessionManager.openHome(AccountActivity.this);
            }
        });
    }
}
