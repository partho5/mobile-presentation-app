package com.jovoc.facecampresentationrecorder.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
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

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.account.AccountApi;
import com.jovoc.facecampresentationrecorder.account.AccountException;
import com.jovoc.facecampresentationrecorder.account.DeviceSession;
import com.jovoc.facecampresentationrecorder.auth.SessionManager;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Every signed-in device of the account, with sign-out per device and for all the others
 * (api.device-sessions). Signing this phone out returns to the sign-in screen.
 */
public class DevicesActivity extends AppCompatActivity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private SessionManager session;
    private AccountApi api;
    private ProgressBar progress;
    private View messageBox;
    private TextView tvMessage;
    private LinearLayout container;
    private Button btnSignOutOthers;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_devices);
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
        progress = findViewById(R.id.progress_devices);
        messageBox = findViewById(R.id.devices_message_box);
        tvMessage = findViewById(R.id.tv_devices_message);
        container = findViewById(R.id.devices_container);
        btnSignOutOthers = findViewById(R.id.btn_sign_out_others);
        findViewById(R.id.btn_devices_retry).setOnClickListener(v -> load());
        btnSignOutOthers.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.dev_confirm_others)
                .setPositiveButton(R.string.dev_sign_out_others, (d, w) -> revokeOthers())
                .setNegativeButton(android.R.string.cancel, null)
                .show());
        load();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
    }

    // ---- list ----

    private void load() {
        progress.setVisibility(View.VISIBLE);
        messageBox.setVisibility(View.GONE);
        btnSignOutOthers.setVisibility(View.GONE);
        container.removeAllViews();
        io.execute(() -> {
            List<DeviceSession> list = null;
            AccountException error = null;
            try {
                list = api.listDevices();
            } catch (AccountException e) {
                error = e;
            }
            final List<DeviceSession> result = list;
            final AccountException err = error;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                progress.setVisibility(View.GONE);
                if (err != null) {
                    showMessage(ApiErrors.message(this, err));
                } else if (result.isEmpty()) {
                    showMessage(getString(R.string.dev_empty));
                } else {
                    showDevices(result);
                }
            });
        });
    }

    private void showMessage(String text) {
        tvMessage.setText(text);
        messageBox.setVisibility(View.VISIBLE);
    }

    private void showDevices(List<DeviceSession> list) {
        LayoutInflater inflater = LayoutInflater.from(this);
        boolean hasOthers = false;
        for (DeviceSession d : list) {
            View row = inflater.inflate(R.layout.item_device, container, false);
            String name = !d.name.isEmpty() ? d.name : !d.userAgent.isEmpty() ? d.userAgent
                    : getString(R.string.dev_unknown);
            ((TextView) row.findViewById(R.id.tv_device_name)).setText(name);
            ((TextView) row.findViewById(R.id.tv_device_detail)).setText(detail(d));
            row.findViewById(R.id.tv_device_current).setVisibility(d.current ? View.VISIBLE : View.GONE);
            row.findViewById(R.id.btn_device_sign_out).setOnClickListener(v -> confirmRevoke(d, name));
            container.addView(row);
            hasOthers |= !d.current;
        }
        btnSignOutOthers.setVisibility(hasOthers ? View.VISIBLE : View.GONE);
    }

    private String detail(DeviceSession d) {
        StringBuilder sb = new StringBuilder();
        if (!d.ip.isEmpty()) sb.append(d.ip);
        if (d.lastSeenAt > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(getString(R.string.dev_last_active, DateUtils.getRelativeTimeSpanString(
                    d.lastSeenAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)));
        }
        return sb.toString();
    }

    // ---- sign out ----

    private void confirmRevoke(DeviceSession d, String name) {
        if (busy) return;
        new MaterialAlertDialogBuilder(this)
                .setMessage(d.current ? getString(R.string.dev_confirm_this)
                        : getString(R.string.dev_confirm_one, name))
                .setPositiveButton(R.string.dev_sign_out, (dlg, w) -> revoke(d))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void revoke(DeviceSession d) {
        setBusy(true);
        io.execute(() -> {
            AccountException error = null;
            try {
                api.revokeDevice(d.id);
            } catch (AccountException e) {
                error = e;
            }
            final AccountException err = error;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                setBusy(false);
                if (err != null && err.kind != AccountException.Kind.NOT_FOUND) {
                    Toast.makeText(this, ApiErrors.message(this, err), Toast.LENGTH_LONG).show();
                    return;
                }
                if (d.current) {
                    // The server ended this session; drop the local copy and go home.
                    session.repository().endLocalSession();
                    SessionManager.openHome(this);
                    return;
                }
                Toast.makeText(this, R.string.dev_signed_out_one, Toast.LENGTH_SHORT).show();
                load();
            });
        });
    }

    private void revokeOthers() {
        setBusy(true);
        io.execute(() -> {
            AccountException error = null;
            try {
                api.revokeOtherDevices();
            } catch (AccountException e) {
                error = e;
            }
            final AccountException err = error;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                setBusy(false);
                if (err != null) {
                    Toast.makeText(this, ApiErrors.message(this, err), Toast.LENGTH_LONG).show();
                    return;
                }
                Toast.makeText(this, R.string.dev_signed_out_others, Toast.LENGTH_SHORT).show();
                load();
            });
        });
    }

    private void setBusy(boolean value) {
        busy = value;
        progress.setVisibility(value ? View.VISIBLE : View.GONE);
        btnSignOutOthers.setEnabled(!value);
    }
}
